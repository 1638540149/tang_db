package com.wangyu.tangdb.stats;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.execute.Executor;
import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.parser.Parser;
import com.wangyu.tangdb.testing.FakeKvStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC-P6 A 组:ANALYZE / SHOW STATS / 直方图 / HyperLogLog / 统计过期(SC-370 ~ SC-373 / SC-388 / SC-389)。
 */
class StatsTest {

    private FakeKvStore kv;
    private Catalog catalog;
    private Executor executor;

    @BeforeEach
    void setUp() {
        kv = new FakeKvStore();
        catalog = Catalog.open(kv);
        executor = new Executor(kv, catalog);
        exec("CREATE TABLE s (id INT PRIMARY KEY, city VARCHAR(16), amount INT)");
        for (int i = 1; i <= 100; i++) {
            exec("INSERT INTO s VALUES (" + i + ", '" + (i % 10 == 0 ? "hot" : "c" + (i % 7))
                    + "', " + (i % 5) + ")");
        }
    }

    private Result exec(String sql) {
        return executor.execute(Parser.parse(sql));
    }

    private Result.Table table(String sql) {
        Result result = executor.execute(Parser.parse(sql));
        assertInstanceOf(Result.Table.class, result, "期望结果集: " + sql);
        return (Result.Table) result;
    }

    private List<Object[]> rows(String sql) {
        return table(sql).rows();
    }

    @Test
    void analyzeStoresRowCountAndColumnStats() {
        Result message = exec("ANALYZE TABLE s");
        assertInstanceOf(Result.Message.class, message);
        assertTrue(((Result.Message) message).text().contains("统计已更新"));

        List<Object[]> stats = rows("SHOW STATS FROM s");
        assertEquals(3, stats.size(), "3 列各一行");
        long rowCount = ((Number) stats.get(0)[3]).longValue();
        assertEquals(100L, rowCount, "row_count 等于 COUNT(*)");
        long count = ((Number) rows("SELECT COUNT(*) FROM s").get(0)[0]).longValue();
        assertEquals(count, rowCount);

        long cityDistinct = stats.stream()
                .filter(r -> "city".equals(r[2]))
                .mapToLong(r -> ((Number) r[12]).longValue())
                .findFirst().orElse(-1);
        assertEquals(8L, cityDistinct, "city 的精确 distinct(hot + c0..c6)");
    }

    @Test
    void analyzeSpecificColumnsOnly() {
        exec("ANALYZE TABLE s (amount)");
        List<Object[]> stats = rows("SHOW STATS FROM s");
        assertEquals(1, stats.size(), "只分析一列就只有一行");
        assertEquals("amount", stats.get(0)[2]);
    }

    @Test
    void analyzeMissingTableAndColumnCodes() {
        RuntimeException table = assertThrows(RuntimeException.class,
                () -> exec("ANALYZE TABLE no_such"));
        assertEquals(SqlError.ER_NO_SUCH_TABLE, SqlError.from(table).code());
        RuntimeException column = assertThrows(RuntimeException.class,
                () -> exec("ANALYZE TABLE s (nope)"));
        assertEquals(SqlError.ER_BAD_FIELD_ERROR, SqlError.from(column).code());
    }

    @Test
    void analyzeInsideTransactionIsRejectedAndRollbackWorks() {
        exec("BEGIN");
        SqlError e = assertThrows(SqlError.class, () -> exec("ANALYZE TABLE s"));
        assertEquals(SqlError.ER_NOT_SUPPORTED_YET, e.code());
        assertTrue(e.getMessage().contains("事务"));
        exec("ROLLBACK");
    }

    @Test
    void emptyTableHasZeroRowsAndEmptyHistogram() {
        exec("CREATE TABLE empty (id INT PRIMARY KEY, v INT)");
        exec("ANALYZE TABLE empty");
        List<Object[]> stats = rows("SHOW STATS FROM empty");
        assertEquals(2, stats.size());
        assertEquals(0L, ((Number) stats.get(0)[3]).longValue());
        String histogram = (String) stats.get(0)[15];
        assertTrue(histogram.contains("\"bounds\":[]"), histogram);
    }

    @Test
    void histogramShareOfSkewedValueApproximatesSixtyPercent() {
        exec("CREATE TABLE skew (id INT PRIMARY KEY, v INT)");
        for (int i = 0; i < 1000; i++) {
            exec("INSERT INTO skew VALUES (" + i + ", " + (i < 600 ? 7 : i) + ")");
        }
        exec("ANALYZE TABLE skew (v)");
        Object[] row = rows("SHOW STATS FROM skew").get(0);
        Histogram histogram = Histogram.parse((String) row[15], 64);
        long total = histogram.totalCount();
        long share = 0;
        for (int i = 0; i < histogram.bounds().size(); i++) {
            if ("7".equals(histogram.bounds().get(i))) {
                share += histogram.counts().get(i);
            }
        }
        assertEquals(1000, total, "桶计数之和 = 行数");
        double ratio = (double) share / total;
        assertTrue(ratio > 0.5 && ratio < 0.7, "单值占 60% 的桶份额,实际 " + ratio);
    }

    @Test
    void hyperLogLogIsAccurateOnLargeInput() {
        HyperLogLog hll = new HyperLogLog(14);
        for (int i = 0; i < 200_000; i++) {
            hll.add(i);
        }
        long estimate = hll.estimate();
        double error = Math.abs(estimate - 200_000) / 200_000.0;
        assertTrue(error < 0.05, "HLL 相对误差应 ≤5%,实际 " + error + "(estimate=" + estimate + ")");
    }

    @Test
    void statsAreStaleAfterRowCountChangesMoreThanTwentyPercent() {
        exec("ANALYZE TABLE s");
        String before = explainText("SELECT * FROM s WHERE city = 'hot'");
        assertTrue(before.contains("依据: 统计(t="), before);
        for (int i = 101; i <= 130; i++) { // 行数 +30%
            exec("INSERT INTO s VALUES (" + i + ", 'hot', 1)");
        }
        String after = explainText("SELECT * FROM s WHERE city = 'hot'");
        assertTrue(after.contains("统计已过期"), after);
        assertEquals(130L, ((Number) rows("SELECT COUNT(*) FROM s").get(0)[0]).longValue(),
                "结果集不受统计过期影响");
    }

    @Test
    void showStatsHasFixedColumns() {
        exec("ANALYZE TABLE s");
        Result.Table stats = table("SHOW STATS");
        assertEquals(StatsService.SHOW_STATS_COLUMNS, stats.columns());
        assertTrue(stats.rows().size() >= 3);
    }

    @Test
    void analyzeWritesAreReadBackAfterReopen() {
        exec("ANALYZE TABLE s");
        List<Object[]> before = rows("SHOW STATS FROM s");
        // 重新打开 catalog + executor(等价于 fed 重启:统计从系统表读回)
        Catalog reopened = Catalog.open(kv);
        Executor fresh = new Executor(kv, reopened);
        Result result = fresh.execute(Parser.parse("SHOW STATS FROM s"));
        List<Object[]> after = ((Result.Table) result).rows();
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i)[2], after.get(i)[2], "列名一致");
            assertEquals(((Number) before.get(i)[3]).longValue(),
                    ((Number) after.get(i)[3]).longValue(), "row_count 一致");
            assertEquals(before.get(i)[15], after.get(i)[15], "直方图落库后一致");
        }
    }

    private String explainText(String sql) {
        StringBuilder sb = new StringBuilder();
        for (Object[] row : rows("EXPLAIN " + sql)) {
            sb.append(row[0]).append('\n');
        }
        return sb.toString();
    }

    @Test
    void explainShowsCandidatesEstimateAndEvidence() {
        String before = explainText("SELECT * FROM s WHERE city = 'hot'");
        assertTrue(before.contains("候选: "), before);
        assertTrue(before.contains("估算: rows="), before);
        assertTrue(before.contains("依据: 默认(未 ANALYZE)"), before);

        exec("ANALYZE TABLE s");
        String after = explainText("SELECT * FROM s WHERE city = 'hot'");
        assertTrue(after.contains("依据: 统计(t="), after);
        assertEquals(10L, ((Number) rows("SELECT COUNT(*) FROM s WHERE city = 'hot'")
                .get(0)[0]).longValue());
    }

    private static List<String> firstColumn(Result.Table table) {
        List<String> out = new ArrayList<>();
        for (Object[] row : table.rows()) {
            out.add(String.valueOf(row[0]));
        }
        return out;
    }
}
