package com.wangyu.tangdb.ai;

import com.sun.net.httpserver.HttpServer;
import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.execute.Executor;
import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.parser.Parser;
import com.wangyu.tangdb.testing.FakeKvStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC-P7 E 组:推理下推(SC-425 / SC-426 / FR-773 ~ FR-782)。
 *
 * <p>用 JDK 自带的 HttpServer 起一个假端点,验证批量、幂等键、缓存、维度校验、
 * 失败不留脏数据、禁用位置(all 1235)。</p>
 */
class AiEmbedTest {

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger lastBatch = new AtomicInteger();
    private volatile int forcedDim = 4;
    private volatile int forcedStatus = 200;
    private FakeKvStore kv;
    private Catalog catalog;
    private Executor executor;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embed", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"texts\"\\s*:\\s*\\[(.*)\\]", java.util.regex.Pattern.DOTALL)
                    .matcher(body);
            String inside = m.find() ? m.group(1).trim() : "";
            List<String> texts = new java.util.ArrayList<>();
            if (!inside.isEmpty()) {
                for (String piece : inside.split(",")) {
                    texts.add(piece.trim().replaceAll("^\"|\"$", ""));
                }
            }
            calls.incrementAndGet();
            lastBatch.set(texts.size());
            byte[] response;
            if (forcedStatus != 200) {
                response = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(forcedStatus, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            StringBuilder json = new StringBuilder();
            json.append("{\"model_version\":\"test-model-v1\",\"dim\":").append(forcedDim)
                    .append(",\"vectors\":[");
            for (int t = 0; t < texts.size(); t++) {
                if (t > 0) {
                    json.append(',');
                }
                json.append('[');
                int base = Math.abs(texts.get(t).hashCode() % 97);
                for (int i = 0; i < forcedDim; i++) {
                    if (i > 0) {
                        json.append(',');
                    }
                    json.append((base + i) / 100.0);
                }
                json.append(']');
            }
            json.append("]}");
            response = json.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            OutputStream os = exchange.getResponseBody();
            os.write(response);
            exchange.close();
        });
        server.start();

        kv = new FakeKvStore();
        catalog = Catalog.open(kv);
        AiService service = new AiService(new AiService.Config(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/embed",
                "test-model-v1", 4, 2000, 1024, 100, 2, 5));
        executor = new Executor(kv, catalog).withAi(service);
        exec("CREATE TABLE raw_docs (id INT PRIMARY KEY, content VARCHAR(200))");
        exec("CREATE TABLE docs (id INT PRIMARY KEY, content VARCHAR(200), embedding VECTOR(4))");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private Result exec(String sql) {
        return executor.execute(Parser.parse(sql));
    }

    private long count(String table) {
        Result.Table table0 = (Result.Table) exec("SELECT COUNT(*) FROM " + table);
        return ((Number) table0.rows().get(0)[0]).longValue();
    }

    @Test
    void insertSelectEmbedsInBatchesAndIsIdempotent() {
        StringBuilder values = new StringBuilder("INSERT INTO raw_docs VALUES ");
        for (int i = 1; i <= 30; i++) {
            if (i > 1) {
                values.append(',');
            }
            values.append('(').append(i).append(",'text").append(i % 3).append("')");
        }
        exec(values.toString());

        Result.Message message = (Result.Message) exec(
                "INSERT INTO docs SELECT id, content, ai_embed(content) FROM raw_docs");
        assertTrue(message.text().contains("30"), message.text());
        assertEquals(30, count("docs"));
        // 30 行里只有 3 种不同文本 → 命中去重后一次批量调用
        assertEquals(1, calls.get(), "同键不同行必须命中缓存/幂等,不重复调用");
        assertEquals(3, lastBatch.get(), "批量调用只应带 3 条不同文本");

        // 再执行一次同一批:目标表主键冲突;但模型不应被再次调用(幂等键命中)
        assertThrows(RuntimeException.class, () -> exec(
                "INSERT INTO docs SELECT id, content, ai_embed(content) FROM raw_docs"));
        assertEquals(1, calls.get(), "同键永不重复调用模型(FR-774)");

        Result.Table vector = (Result.Table) exec("SELECT embedding FROM docs WHERE id = 1");
        float[] vec = (float[]) vector.rows().get(0)[0];
        assertEquals(4, vec.length);
    }

    @Test
    void endpointDimensionMismatchLeavesNoRows() {
        exec("INSERT INTO raw_docs VALUES (1, 'hello')");
        forcedDim = 3; // 端点返回 3 维,与 ai_dim=4 不符
        RuntimeException e = assertThrows(RuntimeException.class, () -> exec(
                "INSERT INTO docs SELECT id, content, ai_embed(content) FROM raw_docs"));
        assertTrue(e.getMessage().contains("维度"), e.getMessage());
        assertEquals(0, count("docs"), "推理失败必须整条语句不写(SC-426)");
    }

    @Test
    void endpointErrorIsRetriedThenFailsWithoutWriting() {
        exec("INSERT INTO raw_docs VALUES (1, 'hello')");
        forcedStatus = 500;
        RuntimeException e = assertThrows(RuntimeException.class, () -> exec(
                "INSERT INTO docs SELECT id, content, ai_embed(content) FROM raw_docs"));
        assertTrue(e.getMessage().contains("推理调用失败"), e.getMessage());
        assertEquals(0, count("docs"));
        AiService.Stats stats = executor.ai().stats();
        assertTrue(stats.failures() >= 1, "失败次数要可观测");
        assertTrue(stats.retries() >= 1, "应按指数退避重试");
    }

    @Test
    void emptyTextIsRejectedWithoutCallingModel() {
        exec("INSERT INTO raw_docs VALUES (1, '   ')");
        RuntimeException e = assertThrows(RuntimeException.class, () -> exec(
                "INSERT INTO docs SELECT id, content, ai_embed(content) FROM raw_docs"));
        assertTrue(e.getMessage().contains("文本为空"), e.getMessage());
        assertEquals(0, calls.get(), "空文本不调模型(FR-782)");
    }

    @Test
    void aiEmbedInPredicateOrGroupByOrOrderByIs1235() {
        sqlTooMany("SELECT id FROM raw_docs WHERE ai_embed(content) = '[1,2,3,4]'");
        sqlTooMany("SELECT id, ai_embed(content) FROM raw_docs ORDER BY ai_embed(content)");
        sqlTooMany("SELECT ai_embed(content), COUNT(*) FROM raw_docs GROUP BY ai_embed(content)");
    }

    private void sqlTooMany(String sql) {
        RuntimeException e = assertThrows(RuntimeException.class, () -> exec(sql), sql);
        assertTrue(e.getMessage().contains("ai_embed") || e.getMessage().contains("1235"),
                e.getMessage());
    }

    @Test
    void selectProjectionCarriesVectors() {
        exec("INSERT INTO raw_docs VALUES (1, 'alpha'), (2, 'beta')");
        Result.Table table = (Result.Table) exec("SELECT id, ai_embed(content) FROM raw_docs");
        assertEquals(2, table.rows().size());
        assertTrue(table.rows().get(0)[1] instanceof float[]);
        assertEquals(4, ((float[]) table.rows().get(0)[1]).length);
        assertEquals(1, calls.get(), "两行不同文本 → 一次批量调用(FR-773)");
    }

    @Test
    void missingEndpointConfigIsExplicit() {
        Executor bare = new Executor(kv, catalog);
        exec("INSERT INTO raw_docs VALUES (1, 'x')");
        RuntimeException e = assertThrows(RuntimeException.class, () -> bare.execute(Parser.parse(
                "SELECT ai_embed(content) FROM raw_docs")));
        assertTrue(e.getMessage().contains("ai_endpoint"), e.getMessage());
    }
}
