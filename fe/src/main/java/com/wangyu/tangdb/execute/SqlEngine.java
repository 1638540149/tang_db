package com.wangyu.tangdb.execute;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.parser.Parser;
import com.wangyu.tangdb.proto.HeartbeatRequest;
import com.wangyu.tangdb.server.BackendRegistry;
import com.wangyu.tangdb.session.Session;
import com.wangyu.tangdb.session.SessionManager;
import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.KvStore;
import com.wangyu.tangdb.storage.TableMeta;
import com.wangyu.tangdb.storage.Values;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 唯一的 SQL 执行入口(SPEC-P3.5 FR-447):协议层与(曾经的)CLI 都走这里,
 * 协议层只做分帧 + 编解码 + 命令分发,不出现任何 SQL 语义判断。
 *
 * <p>职责拆成两块:</p>
 * <ul>
 *   <li>普通 SQL → 同一个 {@link Parser} + 每会话 {@link Executor}(事务/库语义);</li>
 *   <li>连接期兼容语句(§5.5 白名单、{@code information_schema} 三视图、SHOW SESSIONS 等)
 *       —— 这些是"兼容层",同样只在这里,不在协议层。</li>
 * </ul>
 */
public final class SqlEngine {

    public static final String SERVER_VERSION = "5.7.99-tangdb";

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final KvStore kv;
    private final Catalog catalog;
    private final SessionManager sessions;
    private final BackendRegistry backends;
    private final boolean heartbeatEnabled;
    private final String user;

    public SqlEngine(KvStore kv, Catalog catalog, SessionManager sessions,
                     BackendRegistry backends, boolean heartbeatEnabled, String user) {
        this.kv = kv;
        this.catalog = catalog;
        this.sessions = sessions;
        this.backends = backends;
        this.heartbeatEnabled = heartbeatEnabled;
        this.user = user == null ? "" : user;
    }

    /** 执行一条 SQL(FR-447)。失败时抛异常,由调用方(协议层 / 测试)映射成错误。 */
    public Result execute(Session session, String sql) {
        if (session != null) {
            session.touch();
        }
        String statement = stripSemicolon(sql == null ? "" : sql.trim());
        statement = stripLeadingComments(statement);
        if (statement.isEmpty()) {
            return new Result.Message("OK");
        }
        String upper = statement.toUpperCase(Locale.ROOT);

        Result compat = handleCompat(session, statement, upper);
        if (compat != null) {
            return compat;
        }

        Executor executor = executor(session);
        return executor.execute(Parser.parse(statement));
    }

    /** 设置会话当前库(COM_INIT_DB / 握手 database),库不存在抛 1049(FR-436 / FR-446)。 */
    public void useDatabase(Session session, String database) {
        executor(session).useDatabase(database);
    }

    private Executor executor(Session session) {
        return session == null ? new Executor(kv, catalog) : session.executor();
    }

    // ------------------------------------------------------------ 连接期兼容语句(§5.5)

    /** @return 命中的兼容语句结果;未命中返回 null(交给 Parser) */
    private Result handleCompat(Session session, String statement, String upper) {
        // SET ...(全部接受并给 OK;K65:autocommit 语义不改变)
        if (upper.startsWith("SET ")) {
            return new Result.Message("OK");
        }
        // USE 也走 Parser(库语义),这里只处理纯兼容层。

        Result atVars = trySelectSystemVariables(session, statement, upper);
        if (atVars != null) {
            return atVars;
        }

        // SHOW VARIABLES / SHOW STATUS / SHOW WARNINGS / SHOW COLLATION / SHOW CHARACTER SET
        if (upper.startsWith("SHOW VARIABLES")) {
            return emptyTwoColumn("Variable_name", "Value");
        }
        if (upper.startsWith("SHOW STATUS")) {
            return emptyTwoColumn("Variable_name", "Value");
        }
        if (upper.startsWith("SHOW WARNINGS")) {
            return emptyResult(List.of("Level", "Code", "Message"));
        }
        if (upper.startsWith("SHOW COLLATION")) {
            return emptyResult(List.of("Collation", "Charset", "Id", "Default", "Compiled", "Sortlen"));
        }
        if (upper.startsWith("SHOW CHARACTER SET")) {
            return emptyResult(List.of("Charset", "Description", "Default collation", "Maxlen"));
        }
        if (upper.startsWith("SHOW SESSIONS")) {
            return showSessions();
        }
        if (upper.startsWith("SHOW PROCESSLIST")) {
            return showProcesslist();
        }
        if (upper.startsWith("SHOW BACKENDS")) {
            return showBackends();
        }

        // information_schema 三张最小只读视图(§12 I9);其余表名报"不支持"。
        if (upper.contains("INFORMATION_SCHEMA.")) {
            return informationSchema(session, statement, upper);
        }
        return null;
    }

    private static Result emptyTwoColumn(String colA, String colB) {
        return new Result.Table(List.of(colA, colB), new ArrayList<>());
    }

    private static Result emptyResult(List<String> columns) {
        return new Result.Table(columns, new ArrayList<>());
    }

    // ------------------------------------------------------------ SELECT @@xxx / 函数

    private Result trySelectSystemVariables(Session session, String statement, String upper) {
        if (!upper.startsWith("SELECT ")) {
            return null;
        }
        String expr = upper.substring("SELECT ".length()).trim();
        // 去掉尾部 LIMIT n
        expr = expr.replaceFirst("\\s+LIMIT\\s+\\d+$", "").trim();

        // 简单函数:VERSION() / DATABASE() / SCHEMA() / USER() / CURRENT_USER() / CONNECTION_ID()
        if (expr.equals("VERSION()")) {
            return oneValue("@VERSION", SERVER_VERSION);
        }
        if (expr.equals("DATABASE()") || expr.equals("SCHEMA()")) {
            return oneValue("DATABASE()", executor(session).currentDatabase());
        }
        if (expr.equals("USER()") || expr.equals("CURRENT_USER()")) {
            return oneValue("USER()", user);
        }
        if (expr.equals("CONNECTION_ID()")) {
            return oneValue("CONNECTION_ID()", session == null ? 0 : session.id());
        }
        if (expr.equals("CURRENT_USER")) {
            return oneValue("CURRENT_USER", user);
        }

        // SELECT @@x [, @@y ...]
        if (expr.startsWith("@@")) {
            String[] parts = expr.split("\\s*,\\s*");
            List<String> names = new ArrayList<>();
            List<String> values = new ArrayList<>();
            for (String part : parts) {
                if (!part.startsWith("@@")) {
                    return null; // 混入非 @@ 表达式,交给 Parser(它会报语法错)
                }
                String name = part.substring(2).trim();
                names.add("@" + name);
                values.add(systemVariable(name));
            }
            return new Result.Table(names, List.<Object[]>of(values.toArray()));
        }
        return null;
    }

    private static Result oneValue(String column, Object value) {
        return new Result.Table(List.of(column), List.<Object[]>of(new Object[]{value}));
    }

    /** 常见系统变量取值(未实现的会话变量返回合理默认,不报错,§5.5)。 */
    private String systemVariable(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        // 去掉 session. / global. / local. 前缀
        if (lower.startsWith("session.") || lower.startsWith("global.") || lower.startsWith("local.")) {
            lower = lower.substring(lower.indexOf('.') + 1);
        }
        return switch (lower) {
            case "version" -> SERVER_VERSION;
            case "version_comment" -> "tangdb";
            case "character_set_client", "character_set_connection", "character_set_results",
                    "character_set_server", "character_set_database", "character_set_filesystem",
                    "character_set_system" -> "utf8mb4";
            case "collation_connection", "collation_server", "collation_database" -> "utf8mb4_general_ci";
            case "sql_mode" -> "";
            case "autocommit" -> "1";
            case "tx_isolation", "transaction_isolation" -> "REPEATABLE-READ";
            case "transaction_read_only", "sql_auto_is_null", "performance_schema" -> "0";
            case "max_allowed_packet" -> "67108864";
            case "lower_case_table_names" -> "1";
            case "time_zone" -> "Asia/Shanghai";
            case "system_time_zone" -> "CST";
            case "wait_timeout", "interactive_timeout" -> "28800";
            case "license" -> "GPL";
            case "version_compile_os" -> "Win64";
            case "hostname" -> "localhost";
            case "auto_increment_increment", "auto_increment_offset" -> "1";
            case "net_write_timeout" -> "60";
            case "net_read_timeout" -> "30";
            case "max_connections" -> String.valueOf(sessions == null ? 151 : sessions.maxSessions());
            case "init_connect" -> "";
            case "explicit_defaults_for_timestamp" -> "1";
            case "sql_select_limit", "max_join_size", "optimizer_switch" -> "";
            default -> "";
        };
    }

    // ------------------------------------------------------------ SHOW SESSIONS / PROCESSLIST / BACKENDS

    /** SHOW SESSIONS(FR-410):会话 id / 连接源 / 当前库 / 是否在事务中 / 开始时间。 */
    private Result showSessions() {
        List<Object[]> rows = new ArrayList<>();
        for (Session session : sessions.all()) {
            rows.add(new Object[]{
                    session.id(),
                    session.source(),
                    session.currentDatabase(),
                    session.inTransaction() ? "YES" : "NO",
                    TIME_FMT.format(Instant.ofEpochMilli(session.startTimeMillis()))
            });
        }
        return new Result.Table(List.of("session_id", "source", "database", "in_txn", "started_at"), rows);
    }

    /** SHOW PROCESSLIST(FR-450):最小实现,MySQL 客户端与 DBA 常查。 */
    private Result showProcesslist() {
        List<Object[]> rows = new ArrayList<>();
        for (Session session : sessions.all()) {
            rows.add(new Object[]{
                    session.id(),
                    user,
                    session.source(),
                    session.currentDatabase(),
                    "Query",
                    0,
                    "",
                    null
            });
        }
        return new Result.Table(
                List.of("Id", "User", "Host", "db", "Command", "Time", "State", "Info"), rows);
    }

    /** SHOW BACKENDS:be_id / endpoint / version / status / last_heartbeat / ...(FR-155)。 */
    private Result showBackends() {
        if (!heartbeatEnabled) {
            return new Result.Message(
                    "本会话未启用心跳监听(heartbeat_enabled = false),看不到 BE 状态");
        }
        long now = System.currentTimeMillis();
        List<Object[]> rows = new ArrayList<>();
        for (BackendRegistry.Backend backend : backends.all()) {
            HeartbeatRequest hb = backend.lastHeartbeat();
            rows.add(new Object[]{
                    backend.beId(),
                    backend.endpoint(),
                    backend.version(),
                    backend.statusText(now, backends.offlineCycles()),
                    backend.lastSeenText(),
                    hb == null ? 0 : hb.getMemtableBytes(),
                    hb == null ? 0 : hb.getSegmentCount(),
                    hb == null ? 0 : hb.getPutCount(),
                    hb == null ? 0 : hb.getGetCount()
            });
        }
        return new Result.Table(
                List.of("be_id", "endpoint", "version", "status", "last_heartbeat",
                        "memtable_bytes", "segment_count", "put_count", "get_count"),
                rows);
    }

    // ------------------------------------------------------------ information_schema

    private Result informationSchema(Session session, String statement, String upper) {
        int idx = upper.indexOf("INFORMATION_SCHEMA.");
        String after = upper.substring(idx + "INFORMATION_SCHEMA.".length()).trim();
        // 取表名(可能是 `tables` 或 tables)
        String tableName = extractIdentifier(after);
        if (tableName == null) {
            throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                    "不支持的 information_schema 查询");
        }
        switch (tableName) {
            case "SCHEMATA" -> {
                return schemataView();
            }
            case "TABLES" -> {
                return tablesView();
            }
            case "COLUMNS" -> {
                return columnsView();
            }
            default -> throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                    "不支持 information_schema." + tableName.toLowerCase(Locale.ROOT));
        }
    }

    private String extractIdentifier(String text) {
        String t = text.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (t.startsWith("`")) {
            int end = t.indexOf('`', 1);
            return end < 0 ? null : t.substring(1, end).toUpperCase(Locale.ROOT);
        }
        int end = 0;
        while (end < t.length() && Character.isLetterOrDigit(t.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return null;
        }
        return t.substring(0, end).toUpperCase(Locale.ROOT);
    }

    private Result schemataView() {
        List<Object[]> rows = new ArrayList<>();
        for (String db : catalog.databases()) {
            rows.add(new Object[]{"def", db, "utf8mb4", "utf8mb4_general_ci", null});
        }
        return new Result.Table(List.of("CATALOG_NAME", "SCHEMA_NAME", "DEFAULT_CHARACTER_SET_NAME",
                "DEFAULT_COLLATION_NAME", "SQL_PATH"), rows);
    }

    private Result tablesView() {
        List<Object[]> rows = new ArrayList<>();
        for (String db : catalog.databases()) {
            for (TableMeta table : catalog.tables(db)) {
                rows.add(new Object[]{"def", db, table.name(), "BASE TABLE", "InnoDB", 0L});
            }
        }
        return new Result.Table(List.of("TABLE_CATALOG", "TABLE_SCHEMA", "TABLE_NAME",
                "TABLE_TYPE", "ENGINE", "TABLE_ROWS"), rows);
    }

    private Result columnsView() {
        List<Object[]> rows = new ArrayList<>();
        for (String db : catalog.databases()) {
            for (TableMeta table : catalog.tables(db)) {
                for (Column column : table.columns()) {
                    rows.add(new Object[]{
                            "def", db, table.name(), column.name(), column.ordinal() + 1,
                            null, column.nullable() ? "YES" : "NO",
                            mysqlTypeName(column), column.typeName(), maxLen(column)
                    });
                }
            }
        }
        return new Result.Table(List.of("TABLE_CATALOG", "TABLE_SCHEMA", "TABLE_NAME",
                "COLUMN_NAME", "ORDINAL_POSITION", "COLUMN_DEFAULT", "IS_NULLABLE",
                "DATA_TYPE", "COLUMN_TYPE", "CHARACTER_MAXIMUM_LENGTH"), rows);
    }

    private static String mysqlTypeName(Column column) {
        return switch (column.type()) {
            case INT -> "int";
            case BIGINT -> "bigint";
            case DOUBLE -> "double";
            case VARCHAR -> "varchar";
            case DATE -> "date";
            case BOOLEAN -> "tinyint";
        };
    }

    private static Object maxLen(Column column) {
        return column.type() == com.wangyu.tangdb.storage.DataType.VARCHAR
                ? column.length() : null;
    }

    // ------------------------------------------------------------ 工具

    private static String stripSemicolon(String sql) {
        String s = sql.trim();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    /**
     * 去掉语句开头的 SQL 注释(行注释与块注释)。官方客户端(Connector/J)连接时会先发
     * 一段版本注释再接 SELECT,必须容错。
     */
    private static String stripLeadingComments(String sql) {
        String s = sql.trim();
        while (true) {
            if (s.startsWith("/*")) {
                int end = s.indexOf("*/", 2);
                if (end < 0) {
                    return ""; // 未闭合的块注释:视为空
                }
                s = s.substring(end + 2).trim();
                continue;
            }
            if (s.startsWith("--")) {
                int nl = s.indexOf('\n');
                if (nl < 0) {
                    return "";
                }
                s = s.substring(nl + 1).trim();
                continue;
            }
            if (s.startsWith("#")) {
                int nl = s.indexOf('\n');
                if (nl < 0) {
                    return "";
                }
                s = s.substring(nl + 1).trim();
                continue;
            }
            return s;
        }
    }

    /** 供测试:引擎当前使用的 catalog(信息视图 / SHOW DATABASES 由它回答)。 */
    public Catalog catalog() {
        return catalog;
    }
}
