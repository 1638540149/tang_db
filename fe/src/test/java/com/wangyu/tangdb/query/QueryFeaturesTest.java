package com.wangyu.tangdb.query;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.execute.Executor;
import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.parser.Parser;
import com.wangyu.tangdb.testing.FakeKvStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC-P5 E/F/I 组:三值逻辑、表达式与函数、JOIN、子查询、JSON、
 * 向量化 / 逐行对拍(FR-576 / SC-350)、EXPLAIN 读取列(FR-570)。
 */
class QueryFeaturesTest {

    private FakeKvStore kv;
    private Catalog catalog;
    private Executor executor;

    @BeforeEach
    void setUp() {
        kv = new FakeKvStore();
        catalog = Catalog.open(kv);
        executor = new Executor(kv, catalog);
        exec("CREATE TABLE p (id INT PRIMARY KEY, name VARCHAR(16), age INT, city VARCHAR(8))");
        exec("INSERT INTO p VALUES (1, 'Alice', 20, 'bj'), (2, 'bob', 30, 'sh'), "
                + "(3, 'Cathy', NULL, 'bj'), (4, NULL, 40, NULL), (5, 'dave_1', 25, 'gz')");
        exec("CREATE TABLE o (oid INT PRIMARY KEY, pid INT, amount INT)");
        exec("INSERT INTO o VALUES (10, 1, 100), (11, 1, 200), (12, 2, 300), (13, 99, 400), "
                + "(14, NULL, 500)");
        exec("CREATE TABLE c (cid INT PRIMARY KEY, city VARCHAR(8), region VARCHAR(8))");
        exec("INSERT INTO c VALUES (100, 'bj', 'north'), (101, 'sh', 'east'), (102, 'gz', 'south')");
    }

    private Result exec(String sql) {
        return executor.execute(Parser.parse(sql));
    }

    private Result.Table table(String sql) {
        Result result = executor.execute(Parser.parse(sql));
        assertInstanceOf(Result.Table.class, result, "期望结果集: " + sql);
        return (Result.Table) result;
    }

    private static List<Integer> ints(Result.Table table) {
        List<Integer> out = new ArrayList<>();
        for (Object[] row : table.rows()) {
            out.add(((Number) row[0]).intValue());
        }
        return out;
    }

    // ============================================================ F 组:谓词与三值逻辑

    @Test
    void threeValuedLogicFiltersOnlyTrueRows() {
        // NULL 参与比较 → UNKNOWN → 不通过(FR-594)
        assertEquals(List.of(1, 2, 5), ints(table("SELECT id FROM p WHERE age > 10 AND age < 35")));
        assertEquals(List.of(1, 2, 4, 5), ints(table("SELECT id FROM p WHERE NOT (age = 99999)")));
        assertEquals(List.of(4), ints(table("SELECT id FROM p WHERE city IS NULL")));
        assertEquals(List.of(1, 2, 3, 5), ints(table("SELECT id FROM p WHERE city IS NOT NULL")));
        assertTrue(table("SELECT id FROM p WHERE NOT (age > 10)").rows().isEmpty());
        // NOT UNKNOWN 仍是 UNKNOWN
        assertEquals(List.of(), ints(table("SELECT id FROM p WHERE NOT (age > 10) AND age IS NULL")));
    }

    @Test
    void likeIsCaseInsensitiveAndSupportsWildcards() {
        // K106:不区分大小写
        assertEquals(List.of(1), ints(table("SELECT id FROM p WHERE name LIKE 'alice'")));
        assertEquals(List.of(1, 3, 5), ints(table("SELECT id FROM p WHERE name LIKE '%A%'")));
        assertEquals(List.of(1, 2, 3, 5), ints(table("SELECT id FROM p WHERE name LIKE '_%'")));
        // 只有 % → 匹配所有非 NULL 行(边界 44)
        assertEquals(4, table("SELECT id FROM p WHERE name LIKE '%'").rows().size());
        // NULL 不匹配 LIKE
        assertEquals(List.of(5), ints(table("SELECT id FROM p WHERE name LIKE '%\\_1'")));
        assertEquals(List.of(), ints(table("SELECT id FROM p WHERE name LIKE 'ww%'")));
    }

    @Test
    void betweenIsInclusiveAndEmptyWhenLowerGreaterThanUpper() {
        assertEquals(List.of(2, 5), ints(table("SELECT id FROM p WHERE age BETWEEN 25 AND 30")));
        assertTrue(table("SELECT id FROM p WHERE age BETWEEN 10 AND 1").rows().isEmpty(), "边界 45");
        assertEquals(List.of(1, 2, 4, 5), ints(table("SELECT id FROM p WHERE age NOT BETWEEN 22 AND 24")));
    }

    @Test
    void inListWithNullFollowsThreeValuedLogic() {
        assertEquals(List.of(1, 2, 3), ints(table("SELECT id FROM p WHERE city IN ('bj', 'sh')")));
        // x IN (NULL) → UNKNOWN → 不通过;x NOT IN (NULL) → 也不通过
        assertTrue(table("SELECT id FROM p WHERE city IN (NULL)").rows().isEmpty());
        assertTrue(table("SELECT id FROM p WHERE city NOT IN (NULL)").rows().isEmpty());
        assertEquals(List.of(1, 2, 3),
                ints(table("SELECT id FROM p WHERE city NOT IN ('gz')")));
    }

    // ============================================================ F 组:表达式与函数

    @Test
    void arithmeticAndDivisionByZero() {
        Result.Table row = table("SELECT 1 + 2, 10 - 3, 4 * 5, 7 / 2, 7 % 2, 1 / 0 FROM p WHERE id = 1");
        Object[] values = row.rows().get(0);
        assertEquals(3L, values[0]);
        assertEquals(7L, values[1]);
        assertEquals(20L, values[2]);
        assertEquals(3.5, values[3]);
        assertEquals(1L, values[4]);
        assertNull(values[5], "除零 → NULL(K109)");

        // BIGINT 溢出 64 位回绕且重复计算一致(边界 58)
        Result.Table overflow = table(
                "SELECT 9223372036854775807 + 1, 9223372036854775807 + 1 FROM p WHERE id = 1");
        assertEquals(Long.MIN_VALUE, overflow.rows().get(0)[0]);
        assertEquals(overflow.rows().get(0)[0], overflow.rows().get(0)[1]);
    }

    @Test
    void caseAndCoalesceAndCast() {
        Result.Table table = table("SELECT id, CASE WHEN age IS NULL THEN -1 ELSE age END AS a, "
                + "CASE city WHEN 'bj' THEN 1 ELSE 0 END AS b, "
                + "COALESCE(city, 'none'), IFNULL(age, 0), CAST('42' AS INT), "
                + "CAST('abc' AS INT) FROM p ORDER BY id");
        assertEquals(5, table.rows().size());
        assertEquals(-1, ((Number) table.rows().get(2)[1]).intValue());
        assertEquals(1, ((Number) table.rows().get(0)[2]).intValue());
        assertEquals("none", table.rows().get(3)[3]);
        assertEquals(0, ((Number) table.rows().get(2)[4]).intValue());
        assertEquals(42, ((Number) table.rows().get(0)[5]).intValue());
        assertNull(table.rows().get(0)[6], "CAST('abc' AS INT) → NULL(边界 59)");
    }

    @Test
    void scalarFunctionsHandleNormalAndNullInputs() {
        // 每个函数至少一条正例 + NULL 入参(FR-598)
        assertEquals(5L, one("SELECT ABS(-5) FROM p WHERE id = 1"));
        assertNull(one("SELECT ABS(NULL) FROM p WHERE id = 1"));
        assertEquals(3.0, ((Number) one("SELECT ROUND(2.6) FROM p WHERE id = 1")).doubleValue());
        assertNull(one("SELECT ROUND(NULL) FROM p WHERE id = 1"));
        assertEquals(3L, one("SELECT CEIL(2.1) FROM p WHERE id = 1"));
        assertEquals(2L, one("SELECT FLOOR(2.9) FROM p WHERE id = 1"));
        assertEquals(1L, one("SELECT MOD(7, 3) FROM p WHERE id = 1"));
        assertNull(one("SELECT MOD(7, 0) FROM p WHERE id = 1"));
        assertEquals(8L, one("SELECT LENGTH('中国ab') FROM p WHERE id = 1"));
        assertNull(one("SELECT LENGTH(NULL) FROM p WHERE id = 1"));
        assertEquals("ABC", one("SELECT UPPER('abc') FROM p WHERE id = 1"));
        assertEquals("abc", one("SELECT LOWER('ABC') FROM p WHERE id = 1"));
        assertEquals("bcd", one("SELECT SUBSTR('abcdef', 2, 3) FROM p WHERE id = 1"));
        assertEquals("ef", one("SELECT SUBSTR('abcdef', -2) FROM p WHERE id = 1"));
        assertEquals("ab-cd", one("SELECT CONCAT('ab', '-', 'cd') FROM p WHERE id = 1"));
        assertNull(one("SELECT CONCAT('ab', NULL) FROM p WHERE id = 1"));
        assertEquals("ab", one("SELECT TRIM('  ab  ') FROM p WHERE id = 1"));
        assertEquals("aXc", one("SELECT REPLACE('abc', 'b', 'X') FROM p WHERE id = 1"));
        assertEquals(3, ((Number) one("SELECT COALESCE(NULL, NULL, 3) FROM p WHERE id = 1")).intValue());
        assertNull(one("SELECT COALESCE(NULL, NULL) FROM p WHERE id = 1"));
        assertEquals(7, ((Number) one("SELECT IFNULL(NULL, 7) FROM p WHERE id = 1")).intValue());
        assertEquals(2024L, one("SELECT YEAR('2024-02-29') FROM p WHERE id = 1"));
        assertEquals(2L, one("SELECT MONTH('2024-02-29') FROM p WHERE id = 1"));
        assertEquals(29L, one("SELECT DAY('2024-02-29') FROM p WHERE id = 1"));
        assertNull(one("SELECT YEAR(NULL) FROM p WHERE id = 1"));
        SqlError now = assertThrows(SqlError.class, () -> table("SELECT NOW() FROM p"));
        assertEquals(SqlError.ER_NOT_SUPPORTED_YET, now.code(), "NOW() 归 P12(SC-369)");
    }

    private Object one(String sql) {
        Result.Table result = table(sql);
        assertEquals(1, result.rows().size(), sql);
        return result.rows().get(0)[0];
    }

    // ============================================================ E 组:JOIN

    @Test
    void innerJoinHashAndThreeTables() {
        Result.Table joined = table("SELECT p.name, o.amount FROM p JOIN o ON p.id = o.pid "
                + "ORDER BY o.oid");
        assertEquals(3, joined.rows().size());
        assertEquals("Alice", joined.rows().get(0)[0]);

        Result.Table qualified = table("SELECT p.name, o.amount FROM p INNER JOIN o ON o.pid = p.id "
                + "ORDER BY o.amount DESC");
        assertEquals(300, ((Number) qualified.rows().get(0)[1]).intValue());

        Result.Table three = table("SELECT p.name, c.region FROM p "
                + "JOIN o ON p.id = o.pid JOIN c ON p.city = c.city "
                + "ORDER BY p.id, o.oid");
        assertEquals(3, three.rows().size());
        assertEquals("north", three.rows().get(0)[1]);
    }

    @Test
    void leftJoinFillsNullsAndNullKeysDoNotMatch() {
        Result.Table left = table("SELECT p.id, o.amount FROM p LEFT JOIN o ON p.id = o.pid "
                + "ORDER BY p.id, o.oid");
        // p 5 行,Alice 2 笔、bob 1 笔,其余 2 行右表 NULL
        assertEquals(6, left.rows().size());
        assertEquals(100, ((Number) left.rows().get(0)[1]).intValue());
        assertEquals(200, ((Number) left.rows().get(1)[1]).intValue());
        assertEquals(300, ((Number) left.rows().get(2)[1]).intValue());
        assertNull(left.rows().get(3)[1]);
        assertNull(left.rows().get(4)[1]);
        assertNull(left.rows().get(5)[1]);

        // o 里 pid = NULL 的行不匹配任何左行(标准三值逻辑)
        Result.Table nullKeys = table("SELECT oid, p.id FROM o JOIN p ON o.pid = p.id");
        assertFalse(ints2(nullKeys).contains(14));
    }

    private static List<Integer> ints2(Result.Table table) {
        List<Integer> out = new ArrayList<>();
        for (Object[] row : table.rows()) {
            out.add(((Number) row[0]).intValue());
        }
        return out;
    }

    @Test
    void nonEquiJoinFallsBackToNestedLoop() {
        Result.Table t = table("SELECT p.id, o.oid FROM p JOIN o ON p.id < o.pid ORDER BY p.id, o.oid");
        assertTrue(t.rows().size() > 0);
        for (Object[] row : t.rows()) {
            assertTrue(((Number) row[0]).intValue() < ((Number) row[1]).intValue());
        }
    }

    @Test
    void joinOnIncompatibleTypesIsExplicitError() {
        SqlError e = assertThrows(SqlError.class, () ->
                table("SELECT p.id FROM p JOIN o ON p.name = o.amount"));
        assertTrue(e.code() == SqlError.ER_WRONG_ARGUMENTS || e.code() == SqlError.ER_UNKNOWN_ERROR);
    }

    // ============================================================ E 组:子查询

    @Test
    void inSubqueryAndEmptyResult() {
        Result.Table in = table("SELECT id FROM p WHERE id IN (SELECT pid FROM o) ORDER BY id");
        assertEquals(List.of(1, 2), ints(in));
        assertTrue(table("SELECT id FROM p WHERE id IN (SELECT pid FROM o WHERE 1 = 0)")
                .rows().isEmpty(), "空集合 → 空结果集(FR-590)");
        Result.Table notIn = table("SELECT id FROM p WHERE id NOT IN (SELECT pid FROM o "
                + "WHERE pid IS NOT NULL) ORDER BY id");
        assertEquals(List.of(3, 4, 5), ints(notIn));
    }

    @Test
    void scalarSubqueryZeroRowsIsNullAndMultipleRowsIs1242() {
        assertEquals(5L, one("SELECT (SELECT COUNT(*) FROM p) FROM p WHERE id = 1"));
        assertNull(one("SELECT (SELECT id FROM p WHERE id = 999) FROM p WHERE id = 1"));
        SqlError e = assertThrows(SqlError.class,
                () -> table("SELECT (SELECT id FROM p) FROM p WHERE id = 1"));
        assertEquals(SqlError.ER_SUBQUERY_NO_1_ROW, e.code(), "K108");
    }

    @Test
    void correlatedSubqueryIsExplicitlyUnsupported() {
        SqlError e = assertThrows(SqlError.class, () ->
                table("SELECT id FROM p WHERE id IN (SELECT pid FROM o WHERE o.pid = p.id)"));
        assertEquals(SqlError.ER_NOT_SUPPORTED_YET, e.code(), "相关子查询归 P6");
    }

    // ============================================================ I 组:JSON

    @Test
    void jsonRoundTripAndExtract() {
        exec("CREATE TABLE j (id INT PRIMARY KEY, doc JSON)");
        exec("INSERT INTO j VALUES (1, '{\"a\":{\"b\":[10,20]},\"s\":\"x\",\"n\":1.5,\"t\":true}')");
        exec("INSERT INTO j VALUES (2, '[1,2,3]')");
        assertEquals(10L, one("SELECT json_extract(doc, '$.a.b[0]') FROM j WHERE id = 1"));
        assertEquals(20L, one("SELECT json_extract(doc, '$.a.b[1]') FROM j WHERE id = 1"));
        assertEquals("x", one("SELECT json_extract(doc, '$.s') FROM j WHERE id = 1"));
        assertEquals(1.5, one("SELECT json_extract(doc, '$.n') FROM j WHERE id = 1"));
        assertEquals(Boolean.TRUE, one("SELECT json_extract(doc, '$.t') FROM j WHERE id = 1"));
        assertEquals(3L, one("SELECT json_extract(doc, '$[2]') FROM j WHERE id = 2"));
        // 路径不存在 / 取对象当标量 → NULL(边界 62)
        assertNull(one("SELECT json_extract(doc, '$.nope') FROM j WHERE id = 1"));
        assertNull(one("SELECT json_extract(doc, '$.a') FROM j WHERE id = 1"));
        assertNull(one("SELECT json_extract(doc, '$.a.b[9]') FROM j WHERE id = 1"));
        // 读回来仍是合法文档(往返无损)
        assertTrue(com.wangyu.tangdb.storage.Values.display(one("SELECT doc FROM j WHERE id = 1"))
                .contains("\"s\":\"x\""));
    }

    @Test
    void invalidJsonIsRejectedByRowAndColumn() {
        exec("CREATE TABLE bad (id INT PRIMARY KEY, doc JSON)");
        com.wangyu.tangdb.storage.StoreException e = assertThrows(
                com.wangyu.tangdb.storage.StoreException.class,
                () -> exec("INSERT INTO bad VALUES (1, '{\"a\":')"));
        assertTrue(e.getMessage().contains("JSON"), e.getMessage());
        assertTrue(e.getMessage().contains("doc"), "错误信息要指出列名: " + e.getMessage());
        // 没有写入半截值
        assertTrue(table("SELECT id FROM bad").rows().isEmpty());
    }

    @Test
    void jsonColumnCannotBeIndexedOrPrimaryKey() {
        exec("CREATE TABLE j2 (id INT PRIMARY KEY, doc JSON)");
        com.wangyu.tangdb.catalog.CatalogException index = assertThrows(
                com.wangyu.tangdb.catalog.CatalogException.class,
                () -> exec("CREATE INDEX idx_doc ON j2 (doc)"));
        assertEquals(SqlError.ER_NOT_SUPPORTED_YET, index.code(), "FR-611");
        com.wangyu.tangdb.catalog.CatalogException pk = assertThrows(
                com.wangyu.tangdb.catalog.CatalogException.class,
                () -> exec("CREATE TABLE j3 (doc JSON PRIMARY KEY)"));
        assertEquals(SqlError.ER_NOT_SUPPORTED_YET, pk.code());
    }

    // ============================================================ B 组:向量化 vs 逐行(FR-576)

    @Test
    void vectorizedAndRowWisePathsAgreeOnTwentyQueries() {
        List<String> queries = List.of(
                "SELECT id FROM p",
                "SELECT id FROM p WHERE age > 20",
                "SELECT id, age * 2 AS a FROM p WHERE city = 'bj'",
                "SELECT id FROM p WHERE name LIKE '%A%'",
                "SELECT id FROM p WHERE age BETWEEN 20 AND 30",
                "SELECT id FROM p WHERE city IS NULL",
                "SELECT id FROM p WHERE NOT (age = 20)",
                "SELECT id FROM p WHERE age IN (20, 40)",
                "SELECT id FROM p WHERE city = 'bj' OR age > 35",
                "SELECT id, COALESCE(city, 'x') FROM p",
                "SELECT id FROM p WHERE UPPER(name) = 'ALICE'",
                "SELECT id FROM p WHERE CAST(age AS DOUBLE) > 25",
                "SELECT id FROM p WHERE CASE WHEN age > 25 THEN 1 ELSE 0 END = 1",
                "SELECT id FROM p WHERE CONCAT(name, '!') = 'Alice!'",
                "SELECT id FROM p WHERE SUBSTR(name, 1, 1) = 'd'",
                "SELECT id FROM p WHERE id IN (SELECT pid FROM o)",
                "SELECT id FROM p WHERE id NOT IN (SELECT pid FROM o WHERE pid IS NOT NULL)",
                "SELECT p.id, o.amount FROM p JOIN o ON p.id = o.pid",
                "SELECT p.id, o.amount FROM p LEFT JOIN o ON p.id = o.pid",
                "SELECT id FROM p ORDER BY city ASC, id DESC");
        for (String sql : queries) {
            executor.withVectorized(true);
            Result.Table vectorized = table(sql);
            executor.withVectorized(false);
            Result.Table rowWise = table(sql);
            assertEquals(rowWise.columns(), vectorized.columns(), sql);
            assertEquals(rowWise.rows().size(), vectorized.rows().size(), sql);
            for (int i = 0; i < vectorized.rows().size(); i++) {
                assertArrayEquals(rowWise.rows().get(i), vectorized.rows().get(i),
                        sql + " 第 " + i + " 行");
            }
        }
        executor.withVectorized(true);
    }

    // ============================================================ EXPLAIN(FR-570)

    @Test
    void explainOfGroupedJoinQuery() {
        exec("CREATE TABLE ex (id INT PRIMARY KEY, city VARCHAR(8), amount INT)");
        exec("INSERT INTO ex VALUES (1, 'bj', 10)");
        Result.Table explain = table("EXPLAIN SELECT city, SUM(amount) AS s FROM ex "
                + "WHERE amount > 0 GROUP BY city HAVING s > 1 ORDER BY s DESC LIMIT 3");
        List<String> lines = new ArrayList<>();
        for (Object[] row : explain.rows()) {
            lines.add((String) row[0]);
        }
        String joined = String.join("\n", lines);
        assertTrue(joined.contains("Hash Aggregate"), joined);
        assertTrue(joined.contains("TopN"), joined);
        assertTrue(joined.contains("实际读取列"), joined);
        assertTrue(joined.contains("amount"), joined);
    }
}
