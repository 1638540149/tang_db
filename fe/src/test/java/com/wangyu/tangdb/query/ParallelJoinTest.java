package com.wangyu.tangdb.query;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.execute.Executor;
import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.parser.Parser;
import com.wangyu.tangdb.testing.FakeKvStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC-P6.1 FR-712:Hash Join 按哈希桶分段并行 build / probe。
 *
 * <p>断言口径是"与单线程逐行同序",包括 LEFT JOIN 的补空行位置 —— 不是集合相等。</p>
 */
class ParallelJoinTest {

    private Executor serial;
    private Executor parallel;

    @BeforeEach
    void setUp() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        serial = new Executor(kv, catalog).withExecution(1, false, 1024);
        parallel = new Executor(kv, catalog).withExecution(8, false, 1024);
        exec(serial, "CREATE TABLE ja (id INT PRIMARY KEY, v INT)");
        exec(serial, "CREATE TABLE jb (id INT PRIMARY KEY, a_id INT, w INT)");
        exec(serial, "CREATE TABLE jc (id INT PRIMARY KEY, b_id INT, x INT)");
        exec(serial, "CREATE TABLE jd (id INT PRIMARY KEY, a_id INT, y INT)");
        insertRows(serial, "ja", 4000, i -> "(" + i + "," + (i % 10) + ")");
        insertRows(serial, "jb", 5000, i -> "(" + i + "," + (i % 4000) + "," + (i % 7) + ")");
        insertRows(serial, "jc", 3000, i -> "(" + i + "," + (i % 5000) + "," + (i % 5) + ")");
        insertRows(serial, "jd", 1200, i -> "(" + i + "," + (i % 1300) + "," + (i % 3) + ")");
        exec(serial, "ANALYZE TABLE ja");
        exec(serial, "ANALYZE TABLE jb");
        exec(serial, "ANALYZE TABLE jc");
        exec(serial, "ANALYZE TABLE jd");
    }

    private static void exec(Executor executor, String sql) {
        executor.execute(Parser.parse(sql));
    }

    /** 批量插入:每 500 行一条 INSERT,减少建表脚本开销。 */
    private static void insertRows(Executor executor, String table, int rows,
                                   java.util.function.IntFunction<String> tuple) {
        StringBuilder head = new StringBuilder("INSERT INTO ").append(table).append(" VALUES ");
        StringBuilder sql = new StringBuilder(head);
        int count = 0;
        for (int i = 1; i <= rows; i++) {
            if (count > 0) {
                sql.append(',');
            }
            sql.append(tuple.apply(i));
            if (++count == 500) {
                exec(executor, sql.toString());
                sql = new StringBuilder(head);
                count = 0;
            }
        }
        if (count > 0) {
            exec(executor, sql.toString());
        }
    }

    private List<Object[]> rows(Executor executor, String sql) {
        Result result = executor.execute(Parser.parse(sql));
        assertInstanceOf(Result.Table.class, result, "期望结果集: " + sql);
        return ((Result.Table) result).rows();
    }

    private void assertSameRows(String sql, List<Object[]> expected, List<Object[]> actual) {
        assertEquals(expected.size(), actual.size(), "行数不一致: " + sql);
        for (int r = 0; r < expected.size(); r++) {
            assertEquals(expected.get(r).length, actual.get(r).length);
            for (int c = 0; c < expected.get(r).length; c++) {
                assertEquals(String.valueOf(expected.get(r)[c]), String.valueOf(actual.get(r)[c]),
                        sql + " 第 " + (r + 1) + " 行第 " + (c + 1) + " 列与单线程不同");
            }
        }
        assertEquals(String.join("|", summarize(expected)), String.join("|", summarize(actual)),
                "行序摘要不一致: " + sql);
    }

    private static List<String> summarize(List<Object[]> rows) {
        List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(rows.size(), 40); i++) {
            out.add(java.util.Arrays.toString(rows.get(i)));
        }
        return out;
    }

    @Test
    void innerJoinMatchesSingleThreadedRowByRow() {
        String sql = "SELECT a.id, b.id, b.w FROM ja a JOIN jb b ON a.id = b.a_id";
        Parallel.resetParallelTaskCount();
        List<Object[]> expected = rows(serial, sql);
        List<Object[]> actual = rows(parallel, sql);
        assertTrue(Parallel.parallelTaskCount() > 0, "该形态必须真的走并行 build/probe");
        assertSameRows(sql, expected, actual);
    }

    @Test
    void leftJoinKeepsNullPaddingPositions() {
        String sql = "SELECT a.id, d.id, d.y FROM ja a LEFT JOIN jd d ON a.id = d.a_id";
        Parallel.resetParallelTaskCount();
        List<Object[]> expected = rows(serial, sql);
        List<Object[]> actual = rows(parallel, sql);
        assertTrue(Parallel.parallelTaskCount() > 0, "LEFT JOIN 也必须并行");
        assertSameRows(sql, expected, actual);
        long nullRows = actual.stream().filter(row -> row[1] == null).count();
        assertTrue(nullRows > 2000, "补空行必须真的存在(实测 " + nullRows + ")");
    }

    @Test
    void smallBuildSideOnTheRightMatchesSingleThreaded() {
        String sql = "SELECT a.id, d.id, d.y FROM ja a JOIN jd d ON a.id = d.a_id";
        Parallel.resetParallelTaskCount();
        List<Object[]> expected = rows(serial, sql);
        List<Object[]> actual = rows(parallel, sql);
        assertTrue(Parallel.parallelTaskCount() > 0, "build 侧可能是右表,也必须并行");
        assertSameRows(sql, expected, actual);
    }

    @Test
    void threeTableJoinMatchesSingleThreaded() {
        String sql = "SELECT a.id, b.id, c.id FROM ja a JOIN jb b ON a.id = b.a_id "
                + "JOIN jc c ON b.id = c.b_id";
        Parallel.resetParallelTaskCount();
        List<Object[]> expected = rows(serial, sql);
        List<Object[]> actual = rows(parallel, sql);
        assertTrue(Parallel.parallelTaskCount() > 0);
        assertSameRows(sql, expected, actual);
    }

    @Test
    void joinWithResidualOnPredicateMatches() {
        String sql = "SELECT a.id, b.id FROM ja a JOIN jb b ON a.id = b.a_id AND b.w >= 3";
        List<Object[]> expected = rows(serial, sql);
        List<Object[]> actual = rows(parallel, sql);
        assertSameRows(sql, expected, actual);
    }
}
