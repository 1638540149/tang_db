package com.wangyu.tangdb.metrics;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.client.FeConfig;
import com.wangyu.tangdb.console.ConsoleServer;
import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.execute.SqlEngine;
import com.wangyu.tangdb.server.BackendRegistry;
import com.wangyu.tangdb.session.SessionManager;
import com.wangyu.tangdb.testing.FakeKvStore;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC-P8 B 组(FR-823 ~ FR-830)指标验收:
 * 四个指标族都有值、{@code /metrics} 与 {@code SHOW METRICS} 同一份数据、
 * {@code metrics_enabled=false} 时零采集。
 */
class MetricsP8Test {

    @Test
    void prometheusTextAndShowMetricsAgree() throws Exception {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        SessionManager sessions = new SessionManager(4, 0);
        Metrics metrics = new Metrics(true);
        SqlEngine engine = new SqlEngine(kv, catalog, sessions, new BackendRegistry(3), false,
                "root").withMetrics(metrics, new Metrics(true));

        engine.execute(null, "SELECT 1");
        assertThrows(RuntimeException.class,
                () -> engine.execute(null, "SELECT * FROM no_such_table_xyz"));

        Result result = engine.execute(null, "SHOW METRICS");
        assertTrue(result instanceof Result.Table);
        Result.Table table = (Result.Table) result;
        assertEquals(List.of("metric", "value"), table.columns());
        Map<String, String> rows = new LinkedHashMap<>();
        for (Object[] row : table.rows()) {
            rows.put(String.valueOf(row[0]), String.valueOf(row[1]));
        }

        // 四个族都要有值(SC-449)
        assertTrue(rows.containsKey("fe_query_qps"), "查询族");
        assertTrue(rows.keySet().stream().anyMatch(k -> k.startsWith("fe_query_latency_p9")),
                "延迟分位族");
        assertTrue(rows.containsKey("fe_txn_commit_count"), "事务族");
        assertTrue(rows.keySet().stream().anyMatch(k -> k.startsWith("be_memtable_bytes")),
                "BE 族");
        assertTrue(rows.keySet().stream().anyMatch(k -> k.startsWith("be_vector_segment_count")),
                "向量族");
        assertEquals("1", rows.get("fe_query_error_count"));
        assertTrue(rows.containsKey("fe_query_error_code_count{code=\"1146\"}"));

        int port = freePort();
        Path dist = Files.createTempDirectory("tangdb-console-dist");
        ConsoleServer server = new ConsoleServer(port, dist, metrics);
        server.start();
        try {
            String body = httpGet(port, "/metrics");
            assertTrue(body.contains("# HELP fe_query_qps"), "Prometheus 文本要有 HELP");
            assertTrue(body.contains("# TYPE fe_query_qps gauge"), "Prometheus 文本要有 TYPE");
            for (String name : rows.keySet()) {
                assertTrue(body.contains(name + " "), "两通道必须同源: /metrics 缺 " + name);
            }
            // BE 族是稳定值,必须逐值一致(SC-448)
            for (Map.Entry<String, String> entry : rows.entrySet()) {
                if (!entry.getKey().startsWith("be_")) {
                    continue;
                }
                assertTrue(body.contains(entry.getKey() + " " + entry.getValue()),
                        "两通道数值不一致: " + entry.getKey());
            }
        } finally {
            server.close();
        }

        // SHOW METRICS LIKE 只回匹配项(边界 184:不存在 → 空集)
        Result.Table filtered = (Result.Table) engine.execute(null, "SHOW METRICS LIKE 'be_segment%'");
        assertTrue(filtered.rows().stream()
                .allMatch(row -> String.valueOf(row[0]).startsWith("be_segment")));
        Result.Table empty = (Result.Table) engine.execute(null, "SHOW METRICS LIKE 'no_such_metric%'");
        assertTrue(empty.rows().isEmpty());
    }

    @Test
    void disabledMetricsCollectNothing() throws Exception {
        Metrics disabled = new Metrics(false);
        disabled.recordStatement(10, 0);
        disabled.recordConflict();
        assertTrue(disabled.snapshot().isEmpty());
        assertEquals("", disabled.prometheus());

        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        SqlEngine engine = new SqlEngine(kv, catalog, new SessionManager(2, 0),
                new BackendRegistry(3), false, "root")
                .withMetrics(disabled, disabled);
        Result.Table table = (Result.Table) engine.execute(null, "SHOW METRICS");
        assertTrue(table.rows().isEmpty(), "关闭后 SHOW METRICS 返回空集");

        int port = freePort();
        ConsoleServer server = new ConsoleServer(port,
                Files.createTempDirectory("tangdb-console-dist"), disabled);
        server.start();
        try {
            String body = httpGet(port, "/metrics");
            assertEquals("", body);
        } finally {
            server.close();
        }
    }

    @Test
    void p8ConfigKeysParsed() throws Exception {
        Path file = Files.createTempFile("fe-p8", ".conf");
        Files.writeString(file, String.join("\n",
                "mysql_max_connections = 200",
                "mysql_wait_timeout = 30",
                "metrics_enabled = false",
                "slow_query_log = false",
                "slow_query_threshold_ms = 300",
                "slow_query_max_rows = 500",
                "console_port = 8099",
                "console_enabled = false",
                "backup_dir = tmp/backup"), StandardCharsets.UTF_8);
        FeConfig config = FeConfig.load(file.toString());
        assertEquals(200, config.p8().mysqlMaxConnections());
        assertEquals(30, config.p8().mysqlWaitTimeout());
        assertFalse(config.p8().metricsEnabled());
        assertFalse(config.p8().slowQueryLog());
        assertEquals(300, config.p8().slowQueryThresholdMs());
        assertEquals(500, config.p8().slowQueryMaxRows());
        assertEquals(8099, config.p8().consolePort());
        assertFalse(config.p8().consoleEnabled());
        assertEquals("tmp/backup", config.p8().backupDir());
        assertFalse(config.p8().mysqlSsl(), "默认关闭");

        Path bad = Files.createTempFile("fe-p8-bad", ".conf");
        Files.writeString(bad, "mysql_ssl = true\n", StandardCharsets.UTF_8);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> FeConfig.load(bad.toString()));
        assertTrue(e.getMessage().contains("mysql_ssl"), "边界 169:必须指出是哪个配置项");
        assertTrue(e.getMessage().contains("未实现"), "必须说明未实现 TLS");
    }

    @Test
    void latencyWindowPercentiles() {
        LatencyWindow window = new LatencyWindow(60);
        assertEquals(0, window.quantile(0.99), "边界 183:空窗口返回 0");
        for (int i = 0; i < 100; i++) {
            window.record(5);
        }
        window.record(300);
        assertEquals(5, window.quantile(0.50));
        assertEquals(5, window.quantile(0.99), "101 个样本时 P99 仍是 5ms(第 100 个)");
        assertEquals(300, window.quantile(1.0), "最大值落在 300ms 桶");
        assertEquals(101, window.windowCount());
        assertTrue(window.qps() > 0);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String httpGet(int port, String path) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        assertEquals(200, connection.getResponseCode());
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
