package com.wangyu.tangdb.console;

import com.sun.net.httpserver.HttpExchange;
import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.client.FeConfig;
import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.execute.RowStream;
import com.wangyu.tangdb.execute.SqlEngine;
import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.metrics.Metrics;
import com.wangyu.tangdb.session.Session;
import com.wangyu.tangdb.session.SessionManager;
import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.IndexMeta;
import com.wangyu.tangdb.storage.TableMeta;
import com.wangyu.tangdb.storage.VectorIndexMeta;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 控制台后端接口实现(SPEC-P8 FR-853 ~ FR-860 / K195 / K196)。
 *
 * <ul>
 *   <li>登录走 FE 的账号体系(user/password),签发内存态 token;</li>
 *   <li>所有 SQL 走同一个 {@link SqlEngine}(FR-861),控制台会话登记在独立会话表(FR-862);</li>
 *   <li>写操作必须显式确认,未确认直接拒绝并写审计(K196 / SC-458);</li>
 *   <li>错误回错误码 + 文案 + 会话 id(FR-860 / 边界 172)。</li>
 * </ul>
 */
public final class ConsoleApiImpl implements ConsoleApi {

    /** 单个结果集的行数上限(边界 173:超出提示加 LIMIT)。 */
    private static final int MAX_RESULT_ROWS = 10_000;
    /** token 空闲过期(8 小时)。 */
    private static final long TOKEN_IDLE_MILLIS = 8L * 3600 * 1000;
    /** 登录失败限速:窗口内最多失败次数。 */
    private static final int LOGIN_MAX_FAILURES = 5;
    private static final long LOGIN_WINDOW_MILLIS = 30_000;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final List<String> WRITE_PREFIXES = List.of(
            "INSERT", "UPDATE", "DELETE", "CREATE", "DROP", "TRUNCATE", "ALTER", "RENAME",
            "BACKUP", "RESTORE", "REINDEX", "REBUILD", "LOAD", "REPLACE", "GRANT", "REVOKE");

    private final FeConfig config;
    private final SqlEngine engine;
    private final Catalog catalog;
    private final Metrics metrics;
    private final Metrics consoleMetrics;
    /** FR-862:控制台自己的会话表,与 MySQL 连接额度隔离(边界 185:上限 8)。 */
    private final SessionManager consoleSessions = new SessionManager(8, 0);
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    private final Map<String, FailWindow> loginFailures = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Path auditFile;

    public ConsoleApiImpl(FeConfig config, SqlEngine engine, SessionManager mysqlSessions,
                          Catalog catalog, Metrics metrics, Metrics consoleMetrics) {
        this.config = config;
        this.engine = engine;
        this.catalog = catalog;
        this.metrics = metrics == null ? Metrics.DISABLED : metrics;
        this.consoleMetrics = consoleMetrics == null ? Metrics.DISABLED : consoleMetrics;
        this.auditFile = Path.of("tmp", "console-audit.log");
        sweepTokens();
    }

    private record Token(String value, long sessionId, long createdAt, long lastUsed) {
    }

    private static final class FailWindow {
        long windowStart;
        int failures;
    }

    // ------------------------------------------------------------ 路由

    @Override
    public boolean handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        if (path == null) {
            return false;
        }
        switch (path) {
            case "/api/login" -> {
                if (!"POST".equals(method)) {
                    ConsoleServer.sendJson(exchange, 405, errorJson(0, "只支持 POST", null));
                    return true;
                }
                login(exchange);
                return true;
            }
            case "/api/logout" -> {
                logout(exchange);
                return true;
            }
            case "/api/tree" -> {
                if (!authorize(exchange)) {
                    return true;
                }
                tree(exchange);
                return true;
            }
            case "/api/query" -> {
                if (!authorize(exchange)) {
                    return true;
                }
                query(exchange);
                return true;
            }
            case "/api/explain" -> {
                if (!authorize(exchange)) {
                    return true;
                }
                explain(exchange);
                return true;
            }
            case "/api/status" -> {
                if (!authorize(exchange)) {
                    return true;
                }
                status(exchange);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // ------------------------------------------------------------ 登录 / 鉴权

    private void login(HttpExchange exchange) throws IOException {
        String remote = remoteAddress(exchange);
        FailWindow window = loginFailures.computeIfAbsent(remote, k -> new FailWindow());
        synchronized (window) {
            long now = System.currentTimeMillis();
            if (now - window.windowStart > LOGIN_WINDOW_MILLIS) {
                window.windowStart = now;
                window.failures = 0;
            }
            if (window.failures >= LOGIN_MAX_FAILURES) {
                ConsoleServer.sendJson(exchange, 429,
                        errorJson(0, "登录失败次数过多,请稍后再试", null));
                return;
            }
        }

        Map<String, Object> body;
        try {
            body = Json.parseObject(readBody(exchange));
        } catch (IllegalArgumentException e) {
            ConsoleServer.sendJson(exchange, 400, errorJson(0, "请求体不是合法 JSON", null));
            return;
        }
        String user = asString(body.get("username"));
        String password = asString(body.get("password"));
        boolean ok = user.equalsIgnoreCase(config.user())
                && MessageDigest.isEqual(
                        password.getBytes(StandardCharsets.UTF_8),
                        config.password().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            synchronized (window) {
                window.failures++;
            }
            audit("LOGIN_FAILED", user, remote, "密码错误");
            // 边界 171:不泄露内部信息。
            ConsoleServer.sendJson(exchange, 401, errorJson(1045, "用户名或口令错误", null));
            return;
        }

        Session session;
        try {
            long id = consoleSessions.allocateId();
            session = consoleSessions.register(id, "console:" + remote,
                    engine.newExecutor(), Session.CHANNEL_CONSOLE);
        } catch (SqlError e) {
            ConsoleServer.sendJson(exchange, 503, errorJson(e.code(), e.getMessage(), null));
            return;
        }
        String token = newToken();
        long now = System.currentTimeMillis();
        tokens.put(token, new Token(token, session.id(), now, now));
        audit("LOGIN", config.user(), remote, "ok");

        Map<String, Object> resp = Json.object();
        resp.put("ok", true);
        resp.put("token", token);
        resp.put("user", config.user());
        resp.put("session_id", session.id());
        resp.put("expires_in", TOKEN_IDLE_MILLIS / 1000);
        ConsoleServer.sendJson(exchange, 200, Json.write(resp));
    }

    private void logout(HttpExchange exchange) throws IOException {
        String token = tokenOf(exchange);
        Token entry = token == null ? null : tokens.remove(token);
        if (entry != null) {
            consoleSessions.remove(entry.sessionId());
            audit("LOGOUT", config.user(), remoteAddress(exchange), "ok");
        }
        Map<String, Object> resp = Json.object();
        resp.put("ok", true);
        ConsoleServer.sendJson(exchange, 200, Json.write(resp));
    }

    private boolean authorize(HttpExchange exchange) throws IOException {
        Token token = resolveToken(exchange);
        if (token == null) {
            // 边界 171:401,不泄露内部信息。
            ConsoleServer.sendJson(exchange, 401, errorJson(1040, "未登录或 token 已失效", null));
            return false;
        }
        return true;
    }

    private Token resolveToken(HttpExchange exchange) {
        sweepTokens();
        String raw = tokenOf(exchange);
        if (raw == null) {
            return null;
        }
        Token token = tokens.get(raw);
        if (token == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        Token refreshed = new Token(token.value(), token.sessionId(), token.createdAt(), now);
        tokens.put(raw, refreshed);
        return refreshed;
    }

    private static String tokenOf(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("X-Console-Token");
        if (header == null || header.isBlank()) {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                header = authorization.substring(7).trim();
            }
        }
        return header == null || header.isBlank() ? null : header.trim();
    }

    private void sweepTokens() {
        long now = System.currentTimeMillis();
        tokens.entrySet().removeIf(entry -> {
            boolean expired = now - entry.getValue().lastUsed() > TOKEN_IDLE_MILLIS;
            if (expired) {
                consoleSessions.remove(entry.getValue().sessionId());
            }
            return expired;
        });
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private Session sessionOf(Token token) {
        return consoleSessions.get(token.sessionId());
    }

    // ------------------------------------------------------------ 库表树(FR-854)

    private void tree(HttpExchange exchange) throws IOException {
        Token token = resolveToken(exchange);
        Session session = token == null ? null : sessionOf(token);
        String dbFilter = queryParam(exchange, "db");
        String tableFilter = queryParam(exchange, "table");

        List<Object> databases = new ArrayList<>();
        for (String db : catalog.databases()) {
            if (dbFilter != null && !dbFilter.isBlank() && !db.equalsIgnoreCase(dbFilter)) {
                continue;
            }
            Map<String, Object> dbNode = Json.object();
            dbNode.put("name", db);
            List<Object> tables = new ArrayList<>();
            for (TableMeta table : catalog.tables(db)) {
                if (tableFilter != null && !tableFilter.isBlank()
                        && !table.name().equalsIgnoreCase(tableFilter)) {
                    continue;
                }
                Map<String, Object> tableNode = Json.object();
                tableNode.put("name", table.name());
                tableNode.put("table_id", table.id());
                tableNode.put("created_at", table.createdAt());
                List<Object> columns = new ArrayList<>();
                for (Column column : table.columns()) {
                    Map<String, Object> columnNode = Json.object();
                    columnNode.put("name", column.name());
                    columnNode.put("type", column.typeName());
                    columnNode.put("nullable", column.nullable());
                    columnNode.put("primary_key", column.primaryKey());
                    columns.add(columnNode);
                }
                tableNode.put("columns", columns);
                List<Object> indexes = new ArrayList<>();
                for (IndexMeta index : catalog.indexes(table.id())) {
                    Map<String, Object> indexNode = Json.object();
                    indexNode.put("name", index.name());
                    indexNode.put("column", index.columnName());
                    indexNode.put("unique", index.unique());
                    indexNode.put("state", index.state());
                    indexes.add(indexNode);
                }
                for (VectorIndexMeta index : catalog.vectorIndexes(table.id())) {
                    Map<String, Object> indexNode = Json.object();
                    indexNode.put("name", index.name());
                    indexNode.put("column", index.columnName());
                    indexNode.put("vector", true);
                    indexNode.put("dim", index.dim());
                    indexNode.put("state", index.state());
                    indexes.add(indexNode);
                }
                tableNode.put("indexes", indexes);
                tableNode.put("ddl", ddl(session, db, table.name()));
                tables.add(tableNode);
            }
            dbNode.put("tables", tables);
            databases.add(dbNode);
        }
        Map<String, Object> resp = Json.object();
        resp.put("ok", true);
        resp.put("databases", databases);
        ConsoleServer.sendJson(exchange, 200, Json.write(resp));
    }

    private String ddl(Session session, String db, String table) {
        try {
            Result result = engine.execute(session, "SHOW CREATE TABLE `" + db + "`.`" + table + "`");
            if (result instanceof Result.Table rows && !rows.rows().isEmpty()
                    && rows.rows().get(0).length > 1) {
                return String.valueOf(rows.rows().get(0)[1]);
            }
        } catch (RuntimeException e) {
            return "-- DDL 获取失败: " + e.getMessage();
        }
        return "";
    }

    // ------------------------------------------------------------ SQL 执行(FR-855 / FR-859)

    private void query(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJsonObject(exchange);
        if (body == null) {
            return;
        }
        String sql = asString(body.get("sql"));
        boolean confirmed = Boolean.TRUE.equals(body.get("confirm"));
        Token token = resolveToken(exchange);
        Session session = token == null ? null : sessionOf(token);
        if (session == null) {
            ConsoleServer.sendJson(exchange, 401, errorJson(1040, "会话已失效,请重新登录", null));
            return;
        }
        if (sql.isBlank()) {
            ConsoleServer.sendJson(exchange, 400, errorJson(1064, "SQL 不能为空", session.id()));
            return;
        }
        String write = writeStatement(sql);
        if (write != null && !confirmed) {
            // FR-859 / SC-458:未确认的写请求被拒绝,并留下审计。
            audit("WRITE_REJECTED", config.user(), remoteAddress(exchange), sql);
            Map<String, Object> resp = Json.object();
            resp.put("ok", false);
            resp.put("confirm_required", true);
            resp.put("statement", write);
            Map<String, Object> error = Json.object();
            error.put("code", 0);
            error.put("message", "写操作(" + write + ")需要二次确认:请带 confirm=true 重新提交");
            error.put("session_id", session.id());
            resp.put("error", error);
            ConsoleServer.sendJson(exchange, 409, Json.write(resp));
            return;
        }
        if (write != null) {
            audit("WRITE_CONFIRMED", config.user(), remoteAddress(exchange), sql);
        } else {
            audit("QUERY", config.user(), remoteAddress(exchange), sql);
        }

        long started = System.nanoTime();
        try {
            List<Result> results = engine.executeBatch(session, sql);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            List<Object> encoded = new ArrayList<>();
            boolean truncated = false;
            for (Result result : results) {
                Map<String, Object> node = encode(result);
                truncated |= Boolean.TRUE.equals(node.get("truncated"));
                encoded.add(node);
            }
            Map<String, Object> resp = Json.object();
            resp.put("ok", true);
            resp.put("elapsed_ms", elapsedMs);
            resp.put("session_id", session.id());
            resp.put("truncated", truncated);
            if (truncated) {
                resp.put("hint", "结果超过 " + MAX_RESULT_ROWS + " 行上限,请加 LIMIT");
            }
            resp.put("results", encoded);
            ConsoleServer.sendJson(exchange, 200, Json.write(resp));
        } catch (SqlError e) {
            ConsoleServer.sendJson(exchange, 400, errorJson(e.code(), e.getMessage(), session.id()));
        } catch (RuntimeException e) {
            SqlError se = SqlError.from(e);
            ConsoleServer.sendJson(exchange, 400, errorJson(se.code(), se.getMessage(), session.id()));
        }
    }

    private Map<String, Object> encode(Result result) {
        Map<String, Object> node = Json.object();
        if (result instanceof Result.Message message) {
            node.put("kind", "message");
            node.put("message", message.text());
            node.put("affected_rows", message.affectedRows());
            return node;
        }
        List<String> columns;
        List<Object[]> rows = new ArrayList<>();
        boolean truncated = false;
        if (result instanceof Result.StreamTable streaming) {
            columns = streaming.columns();
            RowStream stream = streaming.stream();
            try {
                while (true) {
                    List<Object[]> batch = stream.nextBatch();
                    if (batch == null) {
                        break;
                    }
                    for (Object[] row : batch) {
                        if (rows.size() >= MAX_RESULT_ROWS) {
                            truncated = true;
                            break;
                        }
                        rows.add(row);
                    }
                    if (truncated) {
                        break;
                    }
                }
            } finally {
                stream.close();
            }
        } else {
            Result.Table table = (Result.Table) result;
            columns = table.columns();
            rows = table.rows();
            if (rows.size() > MAX_RESULT_ROWS) {
                rows = new ArrayList<>(rows.subList(0, MAX_RESULT_ROWS));
                truncated = true;
            }
        }
        node.put("kind", "rows");
        node.put("columns", columns);
        List<Object> encodedRows = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            List<Object> values = new ArrayList<>(row.length);
            for (Object value : row) {
                values.add(jsonValue(value));
            }
            encodedRows.add(values);
        }
        node.put("rows", encodedRows);
        node.put("row_count", rows.size());
        node.put("truncated", truncated);
        return node;
    }

    private static Object jsonValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return String.valueOf(value);
    }

    /** 返回第一条写语句的关键字(没有写语句返回 null)。 */
    static String writeStatement(String sql) {
        for (String statement : splitTopLevel(sql)) {
            String trimmed = statement.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String upper = trimmed.toUpperCase(Locale.ROOT);
            for (String prefix : WRITE_PREFIXES) {
                if (upper.equals(prefix) || upper.startsWith(prefix + " ")
                        || upper.startsWith(prefix + "(") || upper.startsWith(prefix + "\t")
                        || upper.startsWith(prefix + "\n")) {
                    return prefix;
                }
            }
        }
        return null;
    }

    private static List<String> splitTopLevel(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c;
                buf.append(c);
                i++;
                while (i < n) {
                    char inner = sql.charAt(i);
                    buf.append(inner);
                    if (inner == '\\' && i + 1 < n) {
                        buf.append(sql.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (inner == quote) {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == ';') {
                out.add(buf.toString());
                buf.setLength(0);
                i++;
                continue;
            }
            buf.append(c);
            i++;
        }
        out.add(buf.toString());
        return out;
    }

    // ------------------------------------------------------------ 执行计划(FR-857)

    private void explain(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJsonObject(exchange);
        if (body == null) {
            return;
        }
        String sql = asString(body.get("sql")).trim();
        Token token = resolveToken(exchange);
        Session session = token == null ? null : sessionOf(token);
        if (session == null) {
            ConsoleServer.sendJson(exchange, 401, errorJson(1040, "会话已失效,请重新登录", null));
            return;
        }
        if (sql.isEmpty()) {
            ConsoleServer.sendJson(exchange, 400, errorJson(1064, "SQL 不能为空", session.id()));
            return;
        }
        String trimmed = sql;
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        if (trimmed.toUpperCase(Locale.ROOT).startsWith("EXPLAIN")) {
            trimmed = trimmed.substring("EXPLAIN".length()).trim();
        }
        try {
            Result result = engine.execute(session, "EXPLAIN " + trimmed);
            StringBuilder plan = new StringBuilder();
            if (result instanceof Result.Table table) {
                for (Object[] row : table.rows()) {
                    for (Object value : row) {
                        if (plan.length() > 0) {
                            plan.append('\n');
                        }
                        plan.append(value == null ? "NULL" : String.valueOf(value));
                    }
                }
            } else if (result instanceof Result.Message message) {
                plan.append(message.text());
            }
            Map<String, Object> resp = Json.object();
            resp.put("ok", true);
            resp.put("plan", plan.toString());
            resp.put("session_id", session.id());
            ConsoleServer.sendJson(exchange, 200, Json.write(resp));
        } catch (SqlError e) {
            ConsoleServer.sendJson(exchange, 400, errorJson(e.code(), e.getMessage(), session.id()));
        } catch (RuntimeException e) {
            SqlError se = SqlError.from(e);
            ConsoleServer.sendJson(exchange, 400, errorJson(se.code(), se.getMessage(), session.id()));
        }
    }

    // ------------------------------------------------------------ 状态区(FR-858)

    private void status(HttpExchange exchange) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (Metrics.MetricPoint point : metrics.snapshot()) {
            values.put(point.name(), point.valueText());
        }
        Map<String, Object> resp = Json.object();
        resp.put("ok", true);
        resp.put("be_alive", "1".equals(values.getOrDefault("be_alive", "0")));
        resp.put("memtable_bytes", number(values.get("be_memtable_bytes")));
        resp.put("segment_count", number(values.get("be_segment_count")));
        resp.put("segment_bytes", number(values.get("be_segment_bytes")));
        resp.put("qps", number(values.get("fe_query_qps")));
        resp.put("p50_ms", number(values.get("fe_query_latency_p50_ms")));
        resp.put("p95_ms", number(values.get("fe_query_latency_p95_ms")));
        resp.put("p99_ms", number(values.get("fe_query_latency_p99_ms")));
        resp.put("slow_query_count", number(values.get("fe_slow_query_count")));
        resp.put("active_sessions", number(values.get("fe_session_active_count")));
        resp.put("console_active_sessions",
                number(values.getOrDefault("console_active_sessions", "0")));
        resp.put("uptime_seconds", number(values.get("fe_uptime_seconds")));
        resp.put("metrics", new LinkedHashMap<>(values));
        // 慢查询 Top10(step 3 起有数据;表不存在时给空集)
        List<Object> slow = new ArrayList<>();
        Token token = resolveToken(exchange);
        Session session = token == null ? null : sessionOf(token);
        if (session != null) {
            try {
                Result result = engine.execute(session, "SHOW SLOW QUERIES LIMIT 10");
                if (result instanceof Result.Table table) {
                    for (Object[] row : table.rows()) {
                        Map<String, Object> node = Json.object();
                        for (int i = 0; i < table.columns().size() && i < row.length; i++) {
                            node.put(table.columns().get(i), jsonValue(row[i]));
                        }
                        slow.add(node);
                    }
                }
            } catch (RuntimeException ignored) {
                // 慢查询表不可用不影响状态区其它字段(边界 190 的"不空白"要求)
            }
        }
        resp.put("slow_queries", slow);

        List<Object> vector = new ArrayList<>();
        if (session != null) {
            try {
                Result result = engine.execute(session, "SHOW VECTOR INDEXES");
                if (result instanceof Result.Table table) {
                    List<String> columns = table.columns();
                    for (Object[] row : table.rows()) {
                        Map<String, Object> node = Json.object();
                        for (int i = 0; i < columns.size() && i < row.length; i++) {
                            node.put(columns.get(i), jsonValue(row[i]));
                        }
                        vector.add(node);
                    }
                }
            } catch (RuntimeException ignored) {
                // 无向量索引 / BE 离线:状态区给空数组
            }
        }
        resp.put("vector_indexes", vector);
        resp.put("server_version", SqlEngine.SERVER_VERSION);
        resp.put("current_database", session == null ? "" : engine.currentDatabase(session));
        resp.put("time", TIME_FMT.format(Instant.now()));
        ConsoleServer.sendJson(exchange, 200, Json.write(resp));
    }

    private static Object number(String raw) {
        if (raw == null) {
            return 0;
        }
        try {
            if (raw.contains(".")) {
                return Double.parseDouble(raw);
            }
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    // ------------------------------------------------------------ 审计

    private void audit(String action, String user, String remote, String detail) {
        String line = TIME_FMT.format(Instant.now()) + " | " + action + " | " + user + " | "
                + remote + " | " + (detail == null ? "" : detail.replace('\n', ' ')) + "\n";
        try {
            Files.createDirectories(auditFile.getParent());
            synchronized (ConsoleApiImpl.class) {
                Files.writeString(auditFile, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException ignored) {
            // 审计写失败不阻塞请求(日志仍走 java.util.logging)
        }
        java.util.logging.Logger.getLogger(ConsoleApiImpl.class.getName())
                .info("console audit " + line.trim());
    }

    // ------------------------------------------------------------ HTTP 工具

    private Map<String, Object> readJsonObject(HttpExchange exchange) throws IOException {
        try {
            return Json.parseObject(readBody(exchange));
        } catch (IllegalArgumentException e) {
            ConsoleServer.sendJson(exchange, 400, errorJson(0, "请求体不是合法 JSON", null));
            return null;
        }
    }

    static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (!key.equals(name)) {
                continue;
            }
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        return null;
    }

    static String remoteAddress(HttpExchange exchange) {
        return exchange.getRemoteAddress() == null ? ""
                : exchange.getRemoteAddress().toString();
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static String errorJson(int code, String message, Long sessionId) {
        Map<String, Object> error = Json.object();
        error.put("code", code);
        error.put("message", message);
        if (sessionId != null) {
            error.put("session_id", sessionId);
        }
        Map<String, Object> resp = Json.object();
        resp.put("ok", false);
        resp.put("error", error);
        return Json.write(resp);
    }
}
