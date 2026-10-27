package com.wangyu.tangdb.query;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.execute.Executor;
import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.parser.Ast;
import com.wangyu.tangdb.parser.Parser;
import com.wangyu.tangdb.storage.KvStore;
import com.wangyu.tangdb.testing.FakeKvStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC-P5 B/C/D/F/I 组:向量化、排序分页、聚合、谓词与函数、表达式。
 */
class QueryEngineTest {

    private FakeKvStore kv;
    private Catalog catalog;
    private Executor executor;

    @BeforeEach
    void setUp() {
        kv = new FakeKvStore();
        catalog = Catalog.open(kv);
        executor = new Executor(kv, catalog);
        exec("CREATE TABLE t (id INT PRIMARY KEY, city VARCHAR(16), amount INT, score DOUBLE, "
                + "note VARCHAR(32), d DATE)");
    }

    private Result exec(String sql) {
        return executor.execute(Parser.parse(sql));
    }

    private Result.Table table(String sql) {
        Result result = executor.execute(Parser.parse(sql));
        assertInstanceOf(Result.Table.class, result, "期望结果集: " + sql);
        return (Result.Table) result;
    }

    private static List<Object> column(Result.Table table, int index) {
        List<Object> out = new ArrayList<>(table.rows().size());
        for (Object[] row : table.rows()) {
            out.add(row[index]);
        }
        return out;
    }

    private void seed() {
        exec("INSERT INTO t VALUES (1, '北京', 10, 1.5, 'a', '2024-02-29')");
        exec("INSERT INTO t VALUES (2, '上海', 20, 2.5, 'b', '2023-12-31')");
        exec("INSERT INTO t VALUES (3, '北京', 30, 3.5, NULL, '2024-01-01')");
        exec("INSERT INTO t VALUES (4, '广州', NULL, NULL, 'a', NULL)");
        exec("INSERT INTO t VALUES (5, '北京', 10, 0.5, 'c', '2024-03-01')");
    }

    // ============================================================ C 组:排序与分页

    @Test
    void multiColumnOrderByWithNullsPerK105() {
        seed();
        Result.Table asc = table("SELECT id FROM t ORDER BY amount ASC");
        // 升序 NULL 在最前(K105)
        assertEquals(Arrays.asList(4, 1, 5, 2, 3), ints(asc));

        Result.Table desc = table("SELECT id FROM t ORDER BY amount DESC");
        assertEquals(Arrays.asList(3, 2, 1, 5, 4), ints(desc));

        Result.Table multi = table("SELECT id FROM t ORDER BY city DESC, amount DESC");
        assertEquals(Arrays.asList(4, 3, 1, 5, 2), ints(multi));
    }

    private static List<Integer> ints(Result.Table table) {
        List<Integer> out = new ArrayList<>();
        for (Object[] row : table.rows()) {
            out.add(((Number) row[0]).intValue());
        }
        return out;
    }

    @Test
    void limitOffsetAfterOrderBy() {
        seed();
        assertEquals(List.of(3, 2), ints(table("SELECT id FROM t ORDER BY amount DESC LIMIT 2")));
        assertEquals(List.of(1, 5), ints(table(
                "SELECT id FROM t ORDER BY amount DESC LIMIT 2 OFFSET 2")));
        assertEquals(List.of(1, 5), ints(table(
                "SELECT id FROM t ORDER BY amount DESC LIMIT 2, 2")));
        assertTrue(table("SELECT id FROM t ORDER BY amount DESC LIMIT 100 OFFSET 50").rows().isEmpty());
        assertTrue(table("SELECT id FROM t ORDER BY amount LIMIT 0").rows().isEmpty());
    }

    @Test
    void topNMatchesFullSortOnLargeInput() {
        exec("CREATE TABLE n (id INT PRIMARY KEY, v INT)");
        for (int i = 0; i < 2000; i++) {
            exec("INSERT INTO n VALUES (" + i + ", " + ((i * 37) % 997) + ")");
        }
        Result.Table top = table("SELECT id FROM n ORDER BY v DESC, id ASC LIMIT 10");
        Result.Table all = table("SELECT id FROM n ORDER BY v DESC, id ASC");
        assertEquals(10, top.rows().size());
        for (int i = 0; i < 10; i++) {
            assertArrayEquals(all.rows().get(i), top.rows().get(i), "TopN 第 " + i + " 行");
        }
    }

    @Test
    void orderByAliasAndOrdinalAndUnknownColumn() {
        seed();
        assertEquals(List.of(3, 2, 1, 5, 4),
                ints(table("SELECT id, amount AS a FROM t ORDER BY a DESC")));
        assertEquals(List.of(3, 2, 1, 5, 4),
                ints(table("SELECT id, amount FROM t ORDER BY 2 DESC")));
        SqlError e = assertThrows(SqlError.class, () -> table("SELECT id FROM t ORDER BY nope"));
        assertEquals(SqlError.ER_BAD_FIELD_ERROR, e.code());
        assertTrue(e.getMessage().contains("nope"));
    }

    @Test
    void externalSortProducesSameOrder() {
        exec("CREATE TABLE big (id INT PRIMARY KEY, v INT)");
        for (int i = 0; i < 500; i++) {
            exec("INSERT INTO big VALUES (" + i + ", " + ((i * 13) % 71) + ")");
        }
        // 把溢写阈值调到 1KB,强制外部归并(FR-580)
        executor.withQueryOptions(64, 1024, "tmp/test-fe-sort");
        Result.Table spilled = table("SELECT id FROM big ORDER BY v DESC, id ASC");
        executor.withQueryOptions(64, 64L * 1024 * 1024, "tmp/test-fe-sort");
        Result.Table inMemory = table("SELECT id FROM big ORDER BY v DESC, id ASC");
        assertEquals(inMemory.rows().size(), spilled.rows().size());
        for (int i = 0; i < inMemory.rows().size(); i++) {
            assertArrayEquals(inMemory.rows().get(i), spilled.rows().get(i), "第 " + i + " 行");
        }
    }

    // ============================================================ D 组:聚合

    @Test
    void aggregateEmptyInputReturnsExactlyOneRow() {
        Result.Table empty = table("SELECT COUNT(*), COUNT(amount), SUM(amount), AVG(amount), "
                + "MIN(amount), MAX(amount) FROM t");
        assertEquals(1, empty.rows().size());
        Object[] row = empty.rows().get(0);
        assertEquals(0L, row[0]);
        assertEquals(0L, row[1]);
        assertNull(row[2], "SUM 空输入 → NULL");
        assertNull(row[3], "AVG 空输入 → NULL");
        assertNull(row[4]);
        assertNull(row[5]);
    }

    @Test
    void groupByHavingAndAggregates() {
        seed();
        Result.Table grouped = table("SELECT city, COUNT(*) AS c, SUM(amount) AS s, "
                + "AVG(amount) AS a, MIN(amount) AS mn, MAX(amount) AS mx FROM t "
                + "GROUP BY city ORDER BY s DESC");
        assertEquals(List.of("city", "c", "s", "a", "mn", "mx"), grouped.columns());
        // 北京: 10+30+10=50;广州 NULL;上海 20
        assertEquals("北京", grouped.rows().get(0)[0]);
        assertEquals(3L, grouped.rows().get(0)[1]);
        assertEquals(50L, grouped.rows().get(0)[2]);

        Result.Table having = table("SELECT city, COUNT(*) AS c FROM t GROUP BY city "
                + "HAVING COUNT(*) > 1 ORDER BY city");
        assertEquals(1, having.rows().size());
        assertEquals("北京", having.rows().get(0)[0]);

        Result.Table havingAlias = table("SELECT city, SUM(amount) AS s FROM t GROUP BY city "
                + "HAVING s > 10 ORDER BY city");
        assertEquals(List.of("上海", "北京"), strings(havingAlias));
    }

    private static List<String> strings(Result.Table table) {
        List<String> out = new ArrayList<>();
        for (Object[] row : table.rows()) {
            out.add((String) row[0]);
        }
        return out;
    }

    @Test
    void nonGroupedColumnWithAggregateIsRejected() {
        seed();
        SqlError e = assertThrows(SqlError.class, () ->
                table("SELECT city, SUM(amount) FROM t"));
        assertEquals(SqlError.ER_WRONG_FIELD_WITH_GROUP, e.code());
        assertTrue(e.getMessage().contains("city"));

        SqlError e2 = assertThrows(SqlError.class, () ->
                table("SELECT id, SUM(amount) FROM t GROUP BY city"));
        assertEquals(SqlError.ER_WRONG_FIELD_WITH_GROUP, e2.code());

        SqlError e3 = assertThrows(SqlError.class, () ->
                table("SELECT city FROM t GROUP BY city HAVING amount > 1"));
        assertEquals(SqlError.ER_WRONG_FIELD_WITH_GROUP, e3.code());
    }

    @Test
    void countDistinctSkipsNullAndEmptyIsZero() {
        seed();
        assertEquals(3L, table("SELECT COUNT(DISTINCT city) FROM t").rows().get(0)[0]);
        assertEquals(3L, table("SELECT COUNT(DISTINCT amount) FROM t").rows().get(0)[0]);
        exec("CREATE TABLE allnull (id INT PRIMARY KEY, v INT)");
        exec("INSERT INTO allnull VALUES (1, NULL), (2, NULL)");
        assertEquals(0L, table("SELECT COUNT(DISTINCT v) FROM allnull").rows().get(0)[0]);
    }

    @Test
    void stddevAndVarianceUsePopulationSemantics() {
        exec("CREATE TABLE s (id INT PRIMARY KEY, v DOUBLE)");
        exec("INSERT INTO s VALUES (1, 2), (2, 4), (3, 4), (4, 4), (5, 5), (6, 5), (7, 7), (8, 9)");
        // 总体方差 = 4,总体标准差 = 2(手算)
        double variance = ((Number) table("SELECT VARIANCE(v) FROM s").rows().get(0)[0]).doubleValue();
        double stddev = ((Number) table("SELECT STDDEV(v) FROM s").rows().get(0)[0]).doubleValue();
        assertEquals(4.0, variance, 1e-9);
        assertEquals(2.0, stddev, 1e-9);

        exec("CREATE TABLE one (id INT PRIMARY KEY, v INT)");
        exec("INSERT INTO one VALUES (1, 42)");
        assertEquals(0.0, ((Number) table("SELECT VARIANCE(v) FROM one").rows().get(0)[0]).doubleValue(), 1e-9);
        assertEquals(0.0, ((Number) table("SELECT STDDEV(v) FROM one").rows().get(0)[0]).doubleValue(), 1e-9);
        assertNull(table("SELECT VARIANCE(v) FROM t").rows().get(0)[0]);
    }

    @Test
    void groupConcatTruncatesAndWarns() {
        seed();
        Result.Table concat = table("SELECT GROUP_CONCAT(id) FROM t");
        assertEquals("1,2,3,4,5", concat.rows().get(0)[0]);

        exec("CREATE TABLE gc (id INT PRIMARY KEY, v VARCHAR(300))");
        String big = "x".repeat(300);
        for (int i = 0; i < 4000; i++) {
            exec("INSERT INTO gc VALUES (" + i + ", '" + big + "')");
        }
        Result.Table truncated = table("SELECT GROUP_CONCAT(v) FROM gc");
        String value = (String) truncated.rows().get(0)[0];
        assertNotNull(value);
        assertTrue(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= 1024 * 1024, "GROUP_CONCAT 不得超过 1MB");
        assertTrue(executor.warningCount() >= 1, "截断要告警(K118 / 边界 63)");
        Result.Table warnings = table("SHOW WARNINGS");
        assertEquals(1, warnings.rows().size());
        assertTrue(String.valueOf(warnings.rows().get(0)[2]).contains("GROUP_CONCAT"));
        assertEquals((long) executor.warningCount(),
                ((Number) table("SHOW COUNT(*) WARNINGS").rows().get(0)[0]).longValue());
    }
}
