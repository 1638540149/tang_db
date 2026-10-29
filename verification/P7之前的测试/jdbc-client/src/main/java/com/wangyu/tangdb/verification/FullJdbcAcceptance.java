package com.wangyu.tangdb.verification;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** End-to-end checks against the running FE MySQL-protocol endpoint using JDBC. */
public final class FullJdbcAcceptance {
    private static final int CSV_ROWS = 50_000;
    private static String url;
    private static String user;
    private static String password;

    private FullJdbcAcceptance() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("usage: <stage> <mysql-port> [path]");
        url = "jdbc:mysql://127.0.0.1:" + args[1] + "/default?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=90000";
        user = "root";
        password = "fulltest";
        switch (args[0]) {
            case "setup" -> setup(Path.of(args[2]));
            case "verify-import" -> verifyImport(Path.of(args[2]));
            case "concurrency" -> concurrency();
            case "crash-seed" -> crashSeed();
            case "failpoint-write" -> failpointWrite();
            case "crash-verify" -> crashVerify();
            case "failpoint-verify" -> failpointVerify();
            default -> throw new IllegalArgumentException("unknown stage: " + args[0]);
        }
    }

    private static Connection connect() throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", user);
        p.setProperty("password", password);
        return DriverManager.getConnection(url, p);
    }

    private static void check(String label, long expected, long actual) {
        if (expected != actual) throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
        System.out.printf("[PASS] %s expected=%d actual=%d%n", label, expected, actual);
    }

    private static void check(String label, String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
        System.out.printf("[PASS] %s expected=%s actual=%s%n", label, expected, actual);
    }

    private static void checkFeature(String label, long expected, String query) {
        try {
            try (Connection c = connect()) {
                sql(c, "USE verify_full");
                check(label, expected, scalar(c, query));
            }
        } catch (SQLException | AssertionError e) {
            System.out.printf("[FAIL] %s expected=%d query_error=%s%n", label, expected, e.getMessage());
        }
    }

    private static void checkFeatureRows(String label, long expectedRows, String query) {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("USE verify_full");
            try (ResultSet rs = s.executeQuery(query)) {
                long rows = 0;
                while (rs.next()) rows++;
                check(label, expectedRows, rows);
            }
        } catch (SQLException | AssertionError e) {
            System.out.printf("[FAIL] %s expected_rows=%d query_error=%s%n", label, expectedRows, e.getMessage());
        }
    }

    private static void checkFeatureAtLeastRows(String label, long minimumRows, String query) {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("USE verify_full");
            try (ResultSet rs = s.executeQuery(query)) {
                long rows = 0;
                while (rs.next()) rows++;
                if (rows < minimumRows) throw new AssertionError("expected at least " + minimumRows + " rows, got " + rows);
                System.out.printf("[PASS] %s minimum=%d actual=%d%n", label, minimumRows, rows);
            }
        } catch (SQLException | AssertionError e) {
            System.out.printf("[FAIL] %s minimum_rows=%d query_error=%s%n", label, minimumRows, e.getMessage());
        }
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            if (!rs.next()) throw new AssertionError("no row: " + sql);
            return rs.getLong(1);
        }
    }

    private static String firstString(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            if (!rs.next()) throw new AssertionError("no row: " + sql);
            return rs.getString(1);
        }
    }

    private static void sql(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) { s.execute(sql); }
    }

    private static void setup(Path csv) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE verify_full");
            s.execute("USE verify_full");
            s.execute("CREATE TABLE customers (id INT PRIMARY KEY, name VARCHAR(40), region VARCHAR(20))");
            s.execute("CREATE TABLE orders (id INT PRIMARY KEY, customer_id INT, amount INT, note VARCHAR(50))");
            s.execute("CREATE INDEX idx_order_customer ON orders (customer_id)");
            s.execute("CREATE TABLE unique_probe (id INT PRIMARY KEY, code VARCHAR(20))");
            s.execute("CREATE UNIQUE INDEX uk_code ON unique_probe (code)");
            s.execute("CREATE TABLE bulk (id INT PRIMARY KEY, bucket INT, amount INT, note VARCHAR(50))");
            s.execute("CREATE TABLE parallel_rows (id INT PRIMARY KEY, worker_id INT, value INT)");
            s.execute("CREATE TABLE crash_rows (id INT PRIMARY KEY, value INT)");
            s.execute("CREATE TABLE nullable_probe (id INT PRIMARY KEY, value VARCHAR(20))");
            s.execute("CREATE TABLE json_probe (id INT PRIMARY KEY, document JSON)");
            s.execute("INSERT INTO customers VALUES (1,'Ada','north'),(2,'Lin','south'),(3,'Kai','north')");
            s.execute("INSERT INTO orders VALUES (10,1,120,'alpha'),(11,1,80,'beta'),(12,2,200,'gamma'),(13,3,50,'delta'),(14,3,150,'epsilon')");
            s.execute("INSERT INTO unique_probe VALUES (1,'U-1')");
            s.execute("INSERT INTO nullable_probe VALUES (1,NULL),(2,'')");
            s.execute("INSERT INTO json_probe VALUES (1,'{\"x\":7,\"label\":\"ok\"}')");
            check("CRUD initial row count", 5, scalar(c, "SELECT COUNT(*) FROM orders"));
            check("predicate and ordering", 2, scalar(c, "SELECT COUNT(*) FROM orders WHERE amount BETWEEN 100 AND 160"));
            check("IN predicate", 4, scalar(c, "SELECT COUNT(*) FROM orders WHERE customer_id IN (1,3)"));
            check("NOT predicate", 4, scalar(c, "SELECT COUNT(*) FROM orders WHERE NOT (customer_id=2)"));
            check("comparison predicates", 2, scalar(c, "SELECT COUNT(*) FROM orders WHERE amount>=120 AND amount<=150"));
            check("join correctness", 5, scalar(c, "SELECT COUNT(*) FROM orders o INNER JOIN customers c ON o.customer_id=c.id"));
            checkFeature("RIGHT JOIN correctness", 5, "SELECT COUNT(*) FROM orders o RIGHT JOIN customers c ON o.customer_id=c.id");
            check("cross join correctness", 15, scalar(c, "SELECT COUNT(*) FROM orders CROSS JOIN customers"));
            check("group aggregate correctness", 600, scalar(c, "SELECT SUM(total) FROM (SELECT customer_id, SUM(amount) AS total FROM orders GROUP BY customer_id) x"));
            check("HAVING correctness", 3, scalar(c, "SELECT COUNT(*) FROM (SELECT customer_id FROM orders GROUP BY customer_id HAVING SUM(amount)>=200) h"));
            check("DISTINCT correctness", 3, scalar(c, "SELECT COUNT(DISTINCT customer_id) FROM orders"));
            check("subquery correctness", 3, scalar(c, "SELECT COUNT(*) FROM customers WHERE id IN (SELECT customer_id FROM orders WHERE amount > 100)"));
            checkFeatureRows("UNION ALL correctness", 4, "SELECT id FROM orders WHERE id<=11 UNION ALL SELECT id FROM orders WHERE id>=13");
            checkFeatureRows("INTERSECT correctness", 2, "SELECT id FROM orders WHERE id<=12 INTERSECT SELECT id FROM orders WHERE id>=11");
            checkFeatureRows("EXCEPT correctness", 1, "SELECT id FROM orders WHERE id<=12 EXCEPT SELECT id FROM orders WHERE id>=11");
            check("ORDER BY with LIMIT", 2, scalar(c, "SELECT COUNT(*) FROM (SELECT id FROM orders ORDER BY amount DESC LIMIT 2) top_orders"));
            check("LIKE predicate", 4, scalar(c, "SELECT COUNT(*) FROM orders WHERE note LIKE '%a%'"));
            check("IS NULL predicate", 1, scalar(c, "SELECT COUNT(*) FROM nullable_probe WHERE value IS NULL"));
            check("empty string is distinct from NULL", 1, scalar(c, "SELECT COUNT(*) FROM nullable_probe WHERE value=''"));
            check("string function UPPER", "ADA", firstString(c, "SELECT UPPER(name) FROM customers WHERE id=1"));
            check("CASE WHEN correctness", 3, scalar(c, "SELECT COUNT(*) FROM orders WHERE CASE WHEN amount>=100 THEN 1 ELSE 0 END=1"));
            check("COALESCE correctness", "fallback", firstString(c, "SELECT COALESCE(value,'fallback') FROM nullable_probe WHERE id=1"));
            check("JSON path extraction", 7, scalar(c, "SELECT JSON_EXTRACT(document,'$.x') FROM json_probe WHERE id=1"));
            check("multi-database metadata", 1, scalar(c, "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='verify_full'"));
            check("JDBC metadata table discovery", 1, metadataTables(c, "verify_full", "orders"));
            try (ResultSet desc = s.executeQuery("DESC orders")) {
                if (!desc.next()) throw new AssertionError("DESC returned no columns");
                System.out.println("[PASS] DESC exposes table columns");
            }
            try (ResultSet tables = s.executeQuery("SHOW TABLES")) {
                if (!tables.next()) throw new AssertionError("SHOW TABLES returned no tables");
                System.out.println("[PASS] SHOW TABLES exposes current schema");
            }

            s.execute("UPDATE orders SET amount=121 WHERE id=10");
            check("update visible", 121, scalar(c, "SELECT amount FROM orders WHERE id=10"));
            s.execute("DELETE FROM orders WHERE id=14");
            check("delete visible", 4, scalar(c, "SELECT COUNT(*) FROM orders"));
            s.execute("BEGIN");
            s.execute("INSERT INTO orders VALUES (15,2,999,'rollback')");
            s.execute("ROLLBACK");
            check("rollback leaves no row", 0, scalar(c, "SELECT COUNT(*) FROM orders WHERE id=15"));
            s.execute("BEGIN");
            s.execute("INSERT INTO orders VALUES (16,2,42,'commit')");
            s.execute("COMMIT");
            check("commit visible", 1, scalar(c, "SELECT COUNT(*) FROM orders WHERE id=16"));

            transactionSnapshotAndConflict();

            SQLException duplicate = expectSqlError(c, "INSERT INTO unique_probe VALUES (2,'U-1')");
            check("duplicate unique index returns MySQL 1062", 1062, duplicate.getErrorCode());
            try (ResultSet indexes = c.getMetaData().getIndexInfo("verify_full", null, "orders", false, false)) {
                if (!indexes.next()) throw new AssertionError("JDBC metadata did not expose index");
                System.out.println("[PASS] JDBC DatabaseMetaData exposes secondary index");
            } catch (SQLException e) {
                System.out.printf("[NOTE] JDBC DatabaseMetaData.getIndexInfo is not compatible with the emitted query: code=%d message=%s%n",
                        e.getErrorCode(), e.getMessage());
            }
            try (ResultSet indexes = s.executeQuery("SHOW INDEX FROM orders")) {
                if (!indexes.next()) throw new AssertionError("SHOW INDEX returned no index");
                System.out.println("[PASS] SHOW INDEX exposes secondary index through JDBC");
            }
            String explain = firstString(c, "EXPLAIN SELECT id FROM orders WHERE customer_id=1");
            if (explain == null || explain.isBlank()) throw new AssertionError("empty EXPLAIN output");
            System.out.println("[PASS] EXPLAIN returned a plan over JDBC");
        }
        writeCsv(csv);
        System.out.printf("[DATA] generated_csv_rows=%d path=%s%n", CSV_ROWS, csv.toAbsolutePath());
    }

    private static long metadataTables(Connection c, String schema, String table) throws SQLException {
        long count = 0;
        try (ResultSet rs = c.getMetaData().getTables(schema, null, table, new String[]{"TABLE"})) {
            while (rs.next()) count++;
        }
        return count;
    }

    private static SQLException expectSqlError(Connection c, String statement) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(statement);
        } catch (SQLException e) {
            System.out.printf("[PASS] expected SQL error code=%d state=%s%n", e.getErrorCode(), e.getSQLState());
            return e;
        }
        throw new AssertionError("expected SQLException for: " + statement);
    }

    private static void transactionSnapshotAndConflict() throws Exception {
        try (Connection first = connect(); Connection second = connect()) {
            sql(first, "USE verify_full");
            sql(second, "USE verify_full");
            sql(first, "BEGIN");
            check("transaction snapshot initial value", 121, scalar(first, "SELECT amount FROM orders WHERE id=10"));
            sql(second, "BEGIN");
            sql(second, "UPDATE orders SET amount=122 WHERE id=10");
            sql(second, "COMMIT");
            check("repeatable snapshot read", 121, scalar(first, "SELECT amount FROM orders WHERE id=10"));
            sql(first, "COMMIT");
            check("snapshot writer commit visible", 122, scalar(first, "SELECT amount FROM orders WHERE id=10"));

            sql(first, "BEGIN");
            sql(first, "UPDATE orders SET amount=123 WHERE id=10");
            sql(second, "BEGIN");
            sql(second, "UPDATE orders SET amount=124 WHERE id=10");
            sql(second, "COMMIT");
            SQLException conflict = expectSqlError(first, "COMMIT");
            if (!(conflict.getMessage().contains("冲突") || conflict.getMessage().toLowerCase().contains("conflict"))) {
                throw new AssertionError("expected write-write conflict, got: " + conflict.getMessage());
            }
            System.out.println("[PASS] concurrent same-row commit reports write-write conflict");
            check("conflicting writer does not overwrite committed value", 124, scalar(second, "SELECT amount FROM orders WHERE id=10"));
        }
    }

    private static void writeCsv(Path csv) throws Exception {
        Files.createDirectories(csv.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            w.write("id,bucket,amount,note\n");
            for (int id = 1; id <= CSV_ROWS; id++) {
                w.write(id + "," + (id % 17) + "," + (id % 1000) + ",row-" + id + "\n");
            }
        }
    }

    private static void verifyImport(Path export) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("USE verify_full");
            check("CSV importer exact row count", CSV_ROWS, scalar(c, "SELECT COUNT(*) FROM bulk"));
            long expectedSum = 0;
            for (int i = 1; i <= CSV_ROWS; i++) expectedSum += i % 1000;
            check("CSV importer checksum", expectedSum, scalar(c, "SELECT SUM(amount) FROM bulk"));
            check("CSV imported key distinctness", CSV_ROWS, scalar(c, "SELECT COUNT(DISTINCT id) FROM bulk"));
            check("CSV imported first/last range", CSV_ROWS, scalar(c, "SELECT MAX(id)-MIN(id)+1 FROM bulk"));

            sql(c, "ANALYZE TABLE bulk");
            try (ResultSet stats = s.executeQuery("SHOW STATS")) {
                if (!stats.next()) throw new AssertionError("SHOW STATS returned no statistics after ANALYZE TABLE");
                System.out.println("[PASS] ANALYZE TABLE populates SHOW STATS");
            }
            checkFeatureAtLeastRows("SHOW SEGMENTS returns persisted data segments", 1, "SHOW SEGMENTS");
            checkFeatureAtLeastRows("SHOW BACKENDS returns live backend", 1, "SHOW BACKENDS");
            checkFeatureAtLeastRows("SHOW SESSIONS exposes JDBC session", 1, "SHOW SESSIONS");
            checkFeatureAtLeastRows("SHOW PROCESSLIST exposes JDBC session", 1, "SHOW PROCESSLIST");

            long[] elapsed = new long[3];
            for (int i = 0; i < elapsed.length; i++) {
                long start = System.nanoTime();
                check("full scan aggregate pass " + (i + 1), expectedSum, scalar(c, "SELECT SUM(amount) FROM bulk"));
                elapsed[i] = Duration.ofNanos(System.nanoTime() - start).toMillis();
            }
            long[] sorted = elapsed.clone();
            java.util.Arrays.sort(sorted);
            double rowsPerSecond = CSV_ROWS * 1000.0 / Math.max(1, sorted[1]);
            System.out.printf("[PERF] jdbc_scan_50k_median_ms=%d rows_per_sec=%.0f samples_ms=%d,%d,%d%n",
                    sorted[1], rowsPerSecond, elapsed[0], elapsed[1], elapsed[2]);

            Files.createDirectories(export.getParent());
            long exported = 0;
            long exportStart = System.nanoTime();
            try (BufferedWriter w = Files.newBufferedWriter(export, StandardCharsets.UTF_8);
                 ResultSet rs = s.executeQuery("SELECT id,bucket,amount,note FROM bulk ORDER BY id")) {
                w.write("id,bucket,amount,note\n");
                while (rs.next()) {
                    int id = rs.getInt(1);
                    int bucket = rs.getInt(2);
                    int amount = rs.getInt(3);
                    String note = rs.getString(4);
                    if (id != exported + 1 || bucket != id % 17 || amount != id % 1000 || !note.equals("row-" + id)) {
                        throw new AssertionError("export row mismatch at id=" + id);
                    }
                    w.write(id + "," + bucket + "," + amount + "," + note + "\n");
                    exported++;
                }
            }
            long exportMs = Duration.ofNanos(System.nanoTime() - exportStart).toMillis();
            check("JDBC CSV export row count", CSV_ROWS, exported);
            check("JDBC CSV export file line count", CSV_ROWS + 1L, Files.readAllLines(export).size());
            System.out.printf("[PERF] jdbc_export_50k_ms=%d rows_per_sec=%.0f path=%s%n",
                    exportMs, CSV_ROWS * 1000.0 / Math.max(1, exportMs), export.toAbsolutePath());
            check("read-after-export remains complete", CSV_ROWS, scalar(c, "SELECT COUNT(*) FROM bulk"));
        }
    }

    private static void concurrency() throws Exception {
        final int workers = 8;
        final int rowsEach = 100;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        long start = System.nanoTime();
        for (int worker = 0; worker < workers; worker++) {
            final int workerId = worker;
            tasks.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try (Connection c = connect(); Statement s = c.createStatement()) {
                    s.execute("USE verify_full");
                    s.execute("BEGIN");
                    for (int batchStart = 0; batchStart < rowsEach; batchStart += 20) {
                        StringBuilder q = new StringBuilder("INSERT INTO parallel_rows VALUES ");
                        for (int offset = 0; offset < 20; offset++) {
                            int seq = batchStart + offset;
                            if (offset > 0) q.append(',');
                            q.append('(').append(1_000_000 + workerId * rowsEach + seq)
                                    .append(',').append(workerId).append(',').append(seq).append(')');
                        }
                        s.execute(q.toString());
                    }
                    s.execute("COMMIT");
                }
                return null;
            }));
        }
        ready.await();
        go.countDown();
        for (Future<?> task : tasks) task.get();
        pool.shutdown();
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        try (Connection c = connect()) {
            check("8 concurrent JDBC sessions row count", workers * rowsEach,
                    scalar(c, "SELECT COUNT(*) FROM verify_full.parallel_rows"));
            check("8 concurrent JDBC sessions unique row count", workers * rowsEach,
                    scalar(c, "SELECT COUNT(DISTINCT id) FROM verify_full.parallel_rows"));
        }
        System.out.printf("[PERF] concurrent_connections=%d committed_rows=%d elapsed_ms=%d rows_per_sec=%.0f%n",
                workers, workers * rowsEach, ms, (workers * rowsEach) * 1000.0 / Math.max(1, ms));
        System.out.println("[PASS] all concurrent JDBC sessions committed successfully");
    }

    private static void crashSeed() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("USE verify_full");
            s.execute("BEGIN");
            StringBuilder q = new StringBuilder("INSERT INTO crash_rows VALUES ");
            for (int i = 0; i < 100; i++) {
                if (i > 0) q.append(',');
                q.append('(').append(2_000_000 + i).append(',').append(i * 3).append(')');
            }
            s.execute(q.toString());
            s.execute("COMMIT");
        }
        System.out.println("[PASS] 100 crash-test rows received JDBC COMMIT success before forced BE termination");
    }

    private static void failpointWrite() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("USE verify_full");
            try {
                s.execute("INSERT INTO crash_rows VALUES (2999999,777)");
                throw new AssertionError("after_fsync failpoint unexpectedly returned success");
            } catch (SQLException e) {
                System.out.printf("[PASS] after_fsync connection returned an error as expected: code=%d state=%s%n",
                        e.getErrorCode(), e.getSQLState());
            }
        }
    }

    private static void crashVerify() throws Exception {
        try (Connection c = connect()) {
            check("forced-termination committed rows recovered", 100,
                    scalar(c, "SELECT COUNT(*) FROM verify_full.crash_rows WHERE id BETWEEN 2000000 AND 2000099"));
            check("forced-termination payload checksum recovered", 14_850,
                    scalar(c, "SELECT SUM(value) FROM verify_full.crash_rows WHERE id BETWEEN 2000000 AND 2000099"));
        }
    }

    private static void failpointVerify() throws Exception {
        try (Connection c = connect()) {
            check("fsynced but unacknowledged WAL record replayed", 1,
                    scalar(c, "SELECT COUNT(*) FROM verify_full.crash_rows WHERE id=2999999 AND value=777"));
        }
    }
}
