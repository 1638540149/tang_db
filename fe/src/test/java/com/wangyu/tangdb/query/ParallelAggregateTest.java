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
 * SPEC-P6.1 FR-713:聚合按键分桶 + 局部聚合 + 二次合并。
 *
 * <p>硬要求:<b>与单线程逐行同序</b>(按首次出现序),所以这里逐行对拍行序;
 * 整数类结果(COUNT / SUM / MIN / MAX / GROUP_CONCAT / COUNT(DISTINCT))要求逐字节相等,
 * 浮点结果(AVG / STDDEV / VARIANCE)要求相对误差内一致 —— 它们的求和顺序变了,
 * 数学上等价但不保证最后一位 bit 相同。</p>
 */
class ParallelAggregateTest {

    private static final int ROWS = 6000;

    private FakeKvStore kv;
    private Catalog catalog;
    private Executor serial;
    private Executor parallel;

    @BeforeEach
    void setUp() {
        kv = new FakeKvStore();
        catalog = Catalog.open(kv);
        serial = new Executor(kv, catalog).withExecution(1, false, 1024);
        parallel = new Executor(kv, catalog).withExecution(8, false, 1024);
        serial.execute(Parser.parse("CREATE TABLE agg (id INT PRIMARY KEY, city VARCHAR(16), "
                + "amount INT, score DOUBLE, note VARCHAR(16))"));
        StringBuilder sql = new StringBuilder();
        int inBatch = 0;
        for (int i = 0; i < ROWS; i++) {
            if (inBatch == 0) {
                sql.setLength(0);
                sql.append("INSERT INTO agg VALUES ");
            } else {
                sql.append(',');
            }
            String city = "c" + (i % 7);
            String amount = (i % 23 == 0) ? "NULL" : Integer.toString(i % 97);
            double score = ((i % 13) - 6) * 0.5;
            sql.append('(').append(i).append(",'").append(city).append("',").append(amount)
                    .append(',').append(score).append(",'n").append(i % 5).append("')");
            if (++inBatch == 500) {
                serial.execute(Parser.parse(sql.toString()));
                inBatch = 0;
            }
        }
        if (inBatch > 0) {
            serial.execute(Parser.parse(sql.toString()));
        }
    }

    private List<Object[]> rows(Executor executor, String sql) {
        Result result = executor.execute(Parser.parse(sql));
        assertInstanceOf(Result.Table.class, result, "期望结果集: " + sql);
        return ((Result.Table) result).rows();
    }

    /** 逐行对拍:行数、每格相等(浮点按相对误差)。 */
    private void assertSameRows(String sql, List<Object[]> expected, List<Object[]> actual) {
        assertEquals(expected.size(), actual.size(), "行数不一致: " + sql);
        for (int r = 0; r < expected.size(); r++) {
            Object[] left = expected.get(r);
            Object[] right = actual.get(r);
            assertEquals(left.length, right.length, "列数不一致: " + sql);
            for (int c = 0; c < left.length; c++) {
                assertEquals(String.valueOf(left[c]), String.valueOf(right[c]),
                        sql + " 第 " + (r + 1) + " 行第 " + (c + 1) + " 列");
            }
        }
    }

    private void assertSameRowsWithinTolerance(String sql, List<Object[]> expected,
                                               List<Object[]> actual) {
        assertEquals(expected.size(), actual.size(), "行数不一致: " + sql);
        for (int r = 0; r < expected.size(); r++) {
            Object[] left = expected.get(r);
            Object[] right = actual.get(r);
            assertEquals(left.length, right.length, "列数不一致: " + sql);
            for (int c = 0; c < left.length; c++) {
                if (left[c] instanceof Double a && right[c] instanceof Double b) {
                    double scale = Math.max(1.0, Math.abs(a));
                    assertTrue(Math.abs(a - b) <= 1e-9 * scale,
                            sql + " 第 " + (r + 1) + " 行第 " + (c + 1)
                                    + " 列浮点漂移过大: " + a + " vs " + b);
                } else {
                    assertEquals(String.valueOf(left[c]), String.valueOf(right[c]),
                            sql + " 第 " + (r + 1) + " 行第 " + (c + 1) + " 列");
                }
            }
        }
    }

    @Test
    void groupedAggregatesMatchSingleThreadedRowByRow() {
        String sql = "SELECT city, COUNT(*), COUNT(amount), SUM(amount), AVG(amount), "
                + "MIN(amount), MAX(amount), STDDEV(amount), VARIANCE(amount), "
                + "GROUP_CONCAT(note) FROM agg GROUP BY city";
        Parallel.resetParallelTaskCount();
        List<Object[]> expected = rows(serial, sql);
        List<Object[]> actual = rows(parallel, sql);
        assertTrue(Parallel.parallelTaskCount() > 0, "该形态必须真的走并行分桶");
        assertSameRowsWithinTolerance(sql, expected, actual);
        assertEquals(7, actual.size(), "7 个分组");
    }

    @Test
    void globalAggregatesMatchSingleThreaded() {
        String sql = "SELECT COUNT(*), COUNT(amount), SUM(amount), AVG(amount), SUM(score), "
                + "AVG(score), STDDEV(score), VARIANCE(score), MIN(score), MAX(score) FROM agg";
        Parallel.resetParallelTaskCount();
        List<Object[]> expected = rows(serial, sql);
        List<Object[]> actual = rows(parallel, sql);
        assertTrue(Parallel.parallelTaskCount() > 0, "无 GROUP BY 的聚合也必须并行分段");
        assertSameRowsWithinTolerance(sql, expected, actual);
    }

    @Test
    void multiKeyGroupingAndCountDistinctMatch() {
        String sql = "SELECT city, amount, COUNT(*), COUNT(DISTINCT note) FROM agg "
                + "GROUP BY city, amount";
        List<Object[]> expected = rows(serial, sql);
        List<Object[]> actual = rows(parallel, sql);
        assertSameRows(sql, expected, actual);

        String distinct = "SELECT COUNT(DISTINCT amount) FROM agg";
        assertSameRows(distinct, rows(serial, distinct), rows(parallel, distinct));
    }

    @Test
    void havingAndGroupOrderAreUnchanged() {
        String sql = "SELECT city, SUM(amount) AS total FROM agg GROUP BY city "
                + "HAVING SUM(amount) > 100000 ORDER BY total DESC";
        List<Object[]> expected = rows(serial, sql);
        List<Object[]> actual = rows(parallel, sql);
        assertSameRows(sql, expected, actual);
    }

    @Test
    void emptyInputStillReturnsOneRow() {
        String sql = "SELECT COUNT(*), SUM(amount), AVG(amount) FROM agg WHERE id < 0";
        List<Object[]> expected = rows(serial, sql);
        List<Object[]> actual = rows(parallel, sql);
        assertSameRows(sql, expected, actual);
        assertEquals(1, actual.size());
        assertEquals(0L, ((Number) actual.get(0)[0]).longValue());
    }
}
