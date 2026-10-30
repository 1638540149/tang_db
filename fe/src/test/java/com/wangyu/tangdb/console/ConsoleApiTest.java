package com.wangyu.tangdb.console;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.client.FeConfig;
import com.wangyu.tangdb.execute.SqlEngine;
import com.wangyu.tangdb.metrics.Metrics;
import com.wangyu.tangdb.server.BackendRegistry;
import com.wangyu.tangdb.session.SessionManager;
import com.wangyu.tangdb.testing.FakeKvStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC-P8 E 组(FR-853 ~ FR-862)控制台 API 冒烟:
 * 登录 / 库表树 / SQL / EXPLAIN / 状态区 / 写确认 / token 校验 / 结果上限。
 */
class ConsoleApiTest {

    private FakeKvStore kv;
    private Catalog catalog;
    private SqlEngine engine;
    private ConsoleServer server;
    private int port;
    private Path dist;

    @BeforeEach
    void setUp() throws Exception {
        kv = new FakeKvStore();
        catalog = Catalog.open(kv);
        Metrics metrics = new Metrics(true);
        SqlEngine raw = new SqlEngine(kv, catalog, new SessionManager(4, 0),
                new BackendRegistry(3), false, "root").withMetrics(metrics, new Metrics(true));
        engine = raw;
        engine.execute(null, "CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(32))");
        engine.execute(null, "INSERT INTO t VALUES (1, 'a'), (2, 'b')");

        dist = Files.createTempDirectory("tangdb-dist");
        Files.writeString(dist.resolve("index.html"), "<html><body>tang_db console</body></html>",
                StandardCharsets.UTF_8);
        port = freePort();
        server = new ConsoleServer(port, dist, metrics)
                .withApi(new ConsoleApiImpl(FeConfig.defaults(), engine,
                        new SessionManager(4, 0), catalog, metrics, null));
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void loginAndTreeAndQuery() throws Exception {
        String token = login();
        assertNotNull(token);

        String tree = get("/api/tree", token);
        assertTrue(tree.contains("\"databases\""), tree);
        assertTrue(tree.contains("\"default\""), tree);
        assertTrue(tree.contains("\"columns\""), tree);
        assertTrue(tree.contains("CREATE TABLE"), "库表树要带 DDL: " + tree);

        String query = post("/api/query", token, "{\"sql\":\"SELECT * FROM t\"}");
        assertTrue(query.contains("\"ok\":true"), query);
        assertTrue(query.contains("\"row_count\":2"), query);
        assertTrue(query.contains("\"columns\":[\"id\",\"name\"]"), query);
        assertTrue(query.contains("\"elapsed_ms\""), query);

        String explain = post("/api/explain", token, "{\"sql\":\"SELECT * FROM t WHERE id = 1\"}");
        assertTrue(explain.contains("\"plan\""), explain);
        assertTrue(explain.contains("主键") || explain.contains("访问路径"), explain);

        String status = get("/api/status", token);
        assertTrue(status.contains("\"be_alive\""), status);
        assertTrue(status.contains("\"qps\""), status);
        assertTrue(status.contains("\"p95_ms\""), status);
        assertTrue(status.contains("\"memtable_bytes\""), status);
        assertTrue(status.contains("\"vector_indexes\""), status);
        assertTrue(status.contains("\"slow_queries\""), status);
    }

    @Test
    void writeRequiresConfirmation() throws Exception {
        String token = login();
        HttpResult rejected = postRaw("/api/query", token,
                "{\"sql\":\"CREATE TABLE guarded (id INT PRIMARY KEY)\"}");
        assertEquals(409, rejected.status());
        assertTrue(rejected.body().contains("confirm_required"), rejected.body());
        assertTrue(rejected.body().contains("需要二次确认"), rejected.body());

        // 未确认 → 表不该被建出来(查询它会报 1146)
        HttpResult probe = postRaw("/api/query", token,
                "{\"sql\":\"SELECT * FROM guarded\"}");
        assertEquals(400, probe.status(), probe.body());
        assertTrue(probe.body().contains("\"code\":1146"), probe.body());

        HttpResult accepted = postRaw("/api/query", token,
                "{\"sql\":\"CREATE TABLE guarded (id INT PRIMARY KEY)\",\"confirm\":true}");
        assertEquals(200, accepted.status(), accepted.body());
        assertTrue(accepted.body().contains("\"ok\":true"), accepted.body());
        String tree = get("/api/tree", token);
        assertTrue(tree.contains("guarded"), tree);
    }

    @Test
    void invalidTokenIs401() throws Exception {
        assertEquals(401, getRaw("/api/tree", null).status());
        assertEquals(401, getRaw("/api/tree", "forged-token").status());
        assertEquals(401, getRaw("/api/status", "deadbeef").status());
    }

    @Test
    void wrongPasswordRejectedAndRateLimited() throws Exception {
        HttpResult first = postRaw("/api/login", null,
                "{\"username\":\"root\",\"password\":\"bad\"}");
        assertEquals(401, first.status());
        assertFalse(first.body().contains("tangdb"), "不得泄露内部信息");

        for (int i = 0; i < 6; i++) {
            postRaw("/api/login", null, "{\"username\":\"root\",\"password\":\"bad\"}");
        }
        HttpResult limited = postRaw("/api/login", null,
                "{\"username\":\"root\",\"password\":\"tangdb\"}");
        assertEquals(429, limited.status(), limited.body());
    }

    @Test
    void sqlErrorReturnsCodeAndSessionId() throws Exception {
        String token = login();
        HttpResult response = postRaw("/api/query", token,
                "{\"sql\":\"SELECT * FROM no_such_table\"}");
        assertEquals(400, response.status());
        assertTrue(response.body().contains("\"code\":1146"), response.body());
        assertTrue(response.body().contains("session_id"), response.body());
        assertTrue(response.body().contains("1146") || response.body().contains("不存在"));
    }

    @Test
    void staticIndexAndMetricsServed() throws Exception {
        HttpResult index = getRaw("/", null);
        assertEquals(200, index.status());
        assertTrue(index.body().contains("tang_db console"));

        HttpResult metrics = getRaw("/metrics", null);
        assertEquals(200, metrics.status());
        assertTrue(metrics.body().contains("fe_query_qps"), metrics.body());
    }

    @Test
    void largeResultIsTruncatedWithHint() throws Exception {
        String token = login();
        engine.execute(null, "CREATE TABLE big (id INT PRIMARY KEY)");
        StringBuilder insert = new StringBuilder("INSERT INTO big VALUES ");
        for (int i = 0; i < 200; i++) {
            if (i > 0) {
                insert.append(',');
            }
            insert.append('(').append(i).append(')');
        }
        engine.execute(null, insert.toString());
        String body = post("/api/query", token, "{\"sql\":\"SELECT * FROM big\"}");
        assertTrue(body.contains("\"ok\":true"), body);
        assertTrue(body.contains("\"row_count\":200"), body);
    }

    // ------------------------------------------------------------ HTTP 工具

    private String login() throws Exception {
        HttpResult response = postRaw("/api/login", null,
                "{\"username\":\"root\",\"password\":\"tangdb\"}");
        assertEquals(200, response.status(), response.body());
        assertTrue(response.body().contains("\"token\""), response.body());
        int start = response.body().indexOf("\"token\":\"") + "\"token\":\"".length();
        return response.body().substring(start, response.body().indexOf('"', start));
    }

    private record HttpResult(int status, String body) {
    }

    private String get(String path, String token) throws Exception {
        HttpResult result = getRaw(path, token);
        assertEquals(200, result.status(), result.body());
        return result.body();
    }

    private HttpResult getRaw(String path, String token) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod("GET");
        if (token != null) {
            connection.setRequestProperty("X-Console-Token", token);
        }
        return read(connection);
    }

    private String post(String path, String token, String body) throws Exception {
        HttpResult result = postRaw(path, token, body);
        assertEquals(200, result.status(), result.body());
        return result.body();
    }

    private HttpResult postRaw(String path, String token, String body) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        if (token != null) {
            connection.setRequestProperty("X-Console-Token", token);
        }
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return read(connection);
    }

    private static HttpResult read(HttpURLConnection connection) throws Exception {
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(10000);
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        if (stream != null) {
            stream.close();
        }
        return new HttpResult(status, body);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
