package com.wangyu.tangdb.console;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wangyu.tangdb.metrics.Metrics;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Web 控制台 HTTP 服务(SPEC-P8 FR-851 / FR-852 / K186)。
 *
 * <p>只用 JDK 内置的 {@code com.sun.net.httpserver},零 Java 新依赖(不引 Spring /
 * Javalin / Vert.x / Netty HTTP)。同一个端口上:</p>
 *
 * <ul>
 *   <li>{@code GET /metrics} —— Prometheus 文本(FR-823);</li>
 *   <li>{@code /api/*} —— 控制台后端接口({@link ConsoleApi});</li>
 *   <li>其余路径 —— {@code console/dist/} 的静态资源(Vue 构建产物),SPA 回退到 index.html。</li>
 * </ul>
 *
 * <p>端口被占用时构造即失败并指出端口(边界 170),不静默换端口。</p>
 */
public final class ConsoleServer implements AutoCloseable {

    private final HttpServer server;
    private final int port;
    private final Path staticRoot;
    private final Metrics metrics;
    private final ExecutorService executor;
    private ConsoleApi api;

    public ConsoleServer(int port, Path staticRoot, Metrics metrics) throws IOException {
        this.port = port;
        this.staticRoot = staticRoot;
        this.metrics = metrics == null ? Metrics.DISABLED : metrics;
        this.server = HttpServer.create(new InetSocketAddress(port), 64);
        // 边界 185:控制台 8 会话并发不得互相阻塞 → 固定线程池,每请求一线程。
        this.executor = Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable, "console-http");
            thread.setDaemon(true);
            return thread;
        });
        this.server.setExecutor(executor);
    }

    /** 挂上控制台 API(FR-853 ~ FR-860);未挂时 /api/* 返回 503。 */
    public ConsoleServer withApi(ConsoleApi api) {
        this.api = api;
        return this;
    }

    public void start() {
        server.createContext("/metrics", this::handleMetrics);
        server.createContext("/api/", this::handleApi);
        server.createContext("/", this::handleStatic);
        server.start();
    }

    public int port() {
        return port;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    // ------------------------------------------------------------ 路由

    /** Prometheus 文本(FR-823);metrics_enabled=false 时返回空文本(不是 404)。 */
    private void handleMetrics(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "text/plain; charset=utf-8", "method not allowed");
                return;
            }
            send(exchange, 200, "text/plain; version=0.0.4; charset=utf-8", metrics.prometheus());
        } finally {
            exchange.close();
        }
    }

    private void handleApi(HttpExchange exchange) throws IOException {
        try {
            ConsoleApi current = api;
            if (current == null) {
                sendJson(exchange, 503, "{\"error\":\"console api 未启用\"}");
                return;
            }
            if (!current.handle(exchange)) {
                sendJson(exchange, 404, "{\"error\":\"unknown api\"}");
            }
        } catch (RuntimeException e) {
            // 边界 172:接口内部异常不得打崩进程,统一回 500 + 文案。
            sendJson(exchange, 500, "{\"error\":\"内部错误: "
                    + ConsoleApi.escape(e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage()) + "\"}");
        } finally {
            exchange.close();
        }
    }

    /** 静态资源托管(FR-851 / K187):dist 目录 + SPA 回退。 */
    private void handleStatic(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
                    && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "text/plain; charset=utf-8", "method not allowed");
                return;
            }
            String rawPath = exchange.getRequestURI().getPath();
            String relative = rawPath == null || rawPath.equals("/") ? "index.html"
                    : rawPath.substring(1);
            Path target = resolve(relative);
            if (target == null || !Files.isRegularFile(target)) {
                String name = target == null ? "" : target.getFileName().toString();
                if (name.contains(".")) {
                    send(exchange, 404, "text/plain; charset=utf-8", "not found");
                    return;
                }
                target = staticRoot.resolve("index.html");
                if (!Files.isRegularFile(target)) {
                    send(exchange, 200, "text/html; charset=utf-8", placeholderHtml());
                    return;
                }
            }
            byte[] body = Files.readAllBytes(target);
            send(exchange, 200, contentType(target.getFileName().toString()), body);
        } finally {
            exchange.close();
        }
    }

    /** 把请求路径限制在 dist 之内(禁止 ../ 逃逸)。 */
    private Path resolve(String relative) {
        try {
            Path base = staticRoot.toAbsolutePath().normalize();
            Path target = base.resolve(relative).normalize();
            return target.startsWith(base) ? target : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String placeholderHtml() {
        return "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
                + "<title>tang_db 控制台</title>"
                + "<body style=\"font-family:sans-serif;padding:2rem\">"
                + "<h1>tang_db 控制台</h1>"
                + "<p>前端构建产物 <code>console/dist/</code> 不存在。请先执行 "
                + "<code>cd console &amp;&amp; npm install &amp;&amp; npm run build</code>。</p>"
                + "<p>指标接口可用:<a href=\"/metrics\">/metrics</a></p></body></html>";
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".ico")) {
            return "image/x-icon";
        }
        if (lower.endsWith(".woff") || lower.endsWith(".woff2")) {
            return "font/woff2";
        }
        return "application/octet-stream";
    }

    static void send(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        send(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8",
                body.getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
