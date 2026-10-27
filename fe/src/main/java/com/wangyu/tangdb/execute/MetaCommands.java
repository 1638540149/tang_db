package com.wangyu.tangdb.execute;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.catalog.CatalogException;
import com.wangyu.tangdb.parser.Ast;
import com.wangyu.tangdb.planner.Predicate;
import com.wangyu.tangdb.session.Session;
import com.wangyu.tangdb.session.SessionManager;
import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.DataType;
import com.wangyu.tangdb.storage.IndexMeta;
import com.wangyu.tangdb.storage.TableMeta;
import com.wangyu.tangdb.storage.Values;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 图形客户端元数据自省:SHOW 家族 + {@code information_schema} 虚拟表
 * (SPEC-P4 §4.7 / §5.7 / K92 ~ K95 / K98)。
 *
 * <p>全部数据来自 FE 内存里的 catalog / 会话表 / 配置,**不访问 BE、不新建系统表**
 * (FR-540 / K94)。未知/不适用的值给 {@code NULL},不编造 0(K95)。</p>
 */
public final class MetaCommands {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** SHOW ROUTINES / SHOW PROCEDURE|FUNCTION STATUS 的 11 列(MySQL 5.7 口径,FR-559)。 */
    private static final List<String> ROUTINE_STATUS_COLUMNS = List.of(
            "Db", "Name", "Type", "Definer", "Modified", "Created", "Security_type", "Comment",
            "character_set_client", "collation_connection", "Database Collation");

    private final Catalog catalog;
    private final Executor executor;
    private final SessionManager sessions;
    private final String user;

    public MetaCommands(Catalog catalog, Executor executor, SessionManager sessions, String user) {
        this.catalog = catalog;
        this.executor = executor;
        this.sessions = sessions;
        this.user = user == null ? "" : user;
    }

    // ============================================================ SHOW TABLES

    public Result showTables(Ast.ShowTables show) {
        String db = resolveDb(show.database());
        List<TableMeta> tables = catalog.tables(db);
        if (show.full()) {
            String key = "Tables_in_" + db;
            List<String> columns = List.of(key, "Table_type");
            List<Object[]> rows = new ArrayList<>(tables.size());
            for (TableMeta table : tables) {
                if (!matchesLike(table.name(), show.like())) {
                    continue;
                }
                rows.add(new Object[]{table.name(), "BASE TABLE"});
            }
            return filter(columns, rows, show.where());
        }
        List<String> columns = List.of("table_name", "table_id", "created_at");
        List<Object[]> rows = new ArrayList<>(tables.size());
        for (TableMeta table : tables) {
            if (!matchesLike(table.name(), show.like())) {
                continue;
            }
            rows.add(new Object[]{table.name(), table.id(), table.createdAt()});
        }
        return filter(columns, rows, show.where());
    }

    // ============================================================ SHOW INDEXES(自有)

    public Result showIndexes(Ast.ShowIndexes show) {
        List<Object[]> rows = new ArrayList<>();
        if (show.table() != null) {
            TableMeta table = requireTable(show.database(), show.table());
            appendIndexRows(rows, table);
        } else {
            String db = resolveDb(show.database());
            for (TableMeta table : catalog.tables(db)) {
                appendIndexRows(rows, table);
            }
        }
        return filter(List.of("index_name", "column_name", "is_unique", "state",
                "index_id", "build_cursor"), rows, show.where());
    }

    private void appendIndexRows(List<Object[]> rows, TableMeta table) {
        for (IndexMeta index : catalog.indexes(table.id())) {
            rows.add(new Object[]{index.name(), index.columnName(), index.unique(),
                    index.stateName(), index.indexId(), index.buildCursor()});
        }
    }

    /** SHOW INDEX / SHOW KEYS FROM <t>(FR-532,MySQL 13 列)。 */
    public Result showIndexDetails(Ast.ShowIndexDetails show) {
        TableMeta table = requireTable(show.database(), show.table());
        List<Object[]> rows = new ArrayList<>();
        Column pk = table.primaryKey();
        if (pk != null) {
            rows.add(new Object[]{table.name(), 0L, "PRIMARY", 1L, pk.name(), "A", null,
                    null, null, "", "BTREE", "", ""});
        }
        for (IndexMeta index : catalog.indexes(table.id())) {
            Column column = table.column(index.columnName());
            rows.add(new Object[]{table.name(), index.unique() ? 0L : 1L, index.name(), 1L,
                    index.columnName(), "A", null, null, null,
                    column != null && column.nullable() ? "YES" : "",
                    "BTREE", "", ""});
        }
        return filter(List.of("Table", "Non_unique", "Key_name", "Seq_in_index",
                "Column_name", "Collation", "Cardinality", "Sub_part", "Packed", "Null",
                "Index_type", "Comment", "Index_comment"), rows, show.where());
    }

    // ============================================================ SHOW TABLE STATUS

    public Result showTableStatus(Ast.ShowTableStatus show) {
        String db = resolveDb(show.database());
        List<Object[]> rows = new ArrayList<>();
        for (TableMeta table : catalog.tables(db)) {
            if (!matchesLike(table.name(), show.like())) {
                continue;
            }
            rows.add(new Object[]{
                    table.name(),                    // Name
                    "InnoDB",                        // Engine(K93:仅为客户端兼容)
                    10L,                             // Version
                    "Dynamic",                       // Row_format
                    null,                            // Rows(不扫全表,K95)
                    null,                            // Avg_row_length
                    null,                            // Data_length
                    null,                            // Max_data_length
                    null,                            // Index_length
                    null,                            // Data_free
                    null,                            // Auto_increment
                    table.createdAt(),               // Create_time
                    null,                            // Update_time
                    null,                            // Check_time
                    "utf8mb4_general_ci",            // Collation
                    null,                            // Checksum
                    "",                              // Create_options
                    ""                               // Comment
            });
        }
        return new Result.Table(List.of("Name", "Engine", "Version", "Row_format", "Rows",
                "Avg_row_length", "Data_length", "Max_data_length", "Index_length", "Data_free",
                "Auto_increment", "Create_time", "Update_time", "Check_time", "Collation",
                "Checksum", "Create_options", "Comment"), rows);
    }

    // ============================================================ SHOW CREATE ...

    public Result showCreateTable(Ast.ShowCreateTable show) {
        TableMeta table = requireTable(show.database(), show.table());
        String ddl = renderCreateTable(table);
        return new Result.Table(List.of("Table", "Create Table"),
                List.<Object[]>of(new Object[]{table.name(), ddl}));
    }

    public Result showCreateDatabase(Ast.ShowCreateDatabase show) {
        String db = show.database() == null ? executor.currentDatabase() : show.database().toLowerCase();
        catalog.requireDatabase(db);
        String ddl = "CREATE DATABASE `" + db + "` /*!40100 DEFAULT CHARACTER SET utf8mb4 */";
        return new Result.Table(List.of("Database", "Create Database"),
                List.<Object[]>of(new Object[]{db, ddl}));
    }

    /** SHOW CREATE TABLE 的输出必须能被本项目 parser 原样吃回去(FR-529 / K96 / SC-338)。 */
    public String renderCreateTable(TableMeta table) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE `").append(table.name()).append("` (\n");
        for (Column column : table.columns()) {
            sb.append("  `").append(column.name()).append("` ")
                    .append(column.typeName().toLowerCase(Locale.ROOT))
                    .append(column.nullable() ? " NULL" : " NOT NULL")
                    .append(",\n");
        }
        Column pk = table.primaryKey();
        List<IndexMeta> indexes = catalog.indexes(table.id());
        if (pk != null) {
            sb.append("  PRIMARY KEY (`").append(pk.name()).append("`),\n");
        }
        for (IndexMeta index : indexes) {
            sb.append("  ").append(index.unique() ? "UNIQUE KEY" : "KEY")
                    .append(" `").append(index.name()).append("` (`")
                    .append(index.columnName()).append("`),\n");
        }
        sb.setLength(sb.length() - 2); // 去掉最后一个 ",\n"
        sb.append("\n) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        return sb.toString();
    }

    // ============================================================ SHOW COLUMNS

    public Result showColumns(Ast.ShowColumns show) {
        TableMeta table = requireTable(show.database(), show.table());
        List<Object[]> rows = new ArrayList<>();
        for (Column column : table.columns()) {
            if (!matchesLike(column.name(), show.like())) {
                continue;
            }
            rows.add(new Object[]{
                    column.name(),
                    column.typeName().toLowerCase(Locale.ROOT),
                    column.type() == DataType.VARCHAR ? "utf8mb4_general_ci" : null,
                    column.nullable() ? "YES" : "NO",
                    columnKey(table, column),
                    null,
                    "",
                    "select,insert,update,references",
                    ""
            });
        }
        return new Result.Table(List.of("Field", "Type", "Collation", "Null", "Key",
                "Default", "Extra", "Privileges", "Comment"), rows);
    }

    // ============================================================ SHOW <simple>

    public Result showSimple(Ast.ShowSimple show) {
        return switch (show.kind()) {
            case ENGINES -> showEngines();
            case COLLATION -> showCollation(show.like());
            case CHARACTER_SET -> showCharacterSet(show.like());
            case GRANTS -> showGrants();
            case WARNINGS, ERRORS -> new Result.Table(List.of("Level", "Code", "Message"),
                    new ArrayList<>());
            case WARNING_COUNT -> oneRow("@@session.warning_count", 0L);
            case ERROR_COUNT -> oneRow("@@session.error_count", 0L);
            case TRIGGERS -> new Result.Table(List.of("Trigger", "Event", "Table", "Statement",
                    "Timing", "Created", "sql_mode", "Definer", "character_set_client",
                    "collation_connection", "Database Collation"), new ArrayList<>());
            case EVENTS -> new Result.Table(List.of("Db", "Name", "Definer", "Time zone", "Type",
                    "Execute at", "Interval value", "Interval field", "Starts", "Ends", "Status",
                    "Originator", "character_set_client", "collation_connection",
                    "Database Collation"), new ArrayList<>());
            case ROUTINES, PROCEDURE_STATUS, FUNCTION_STATUS -> routineStatus(show.where());
            case VARIABLES -> showVariables(show.like(), show.where());
            case STATUS -> showStatus(show.like(), show.where());
            case PROCESSLIST -> showProcesslist(false);
            case FULL_PROCESSLIST -> showProcesslist(true);
        };
    }

    /**
     * 本期无存储过程 / 函数:列定义正确、恒为空的 11 列结果集(FR-559 / H-6 / 边界 38)。
     * {@code LIKE} 在空集上没有行可匹配,解析后即可丢弃;{@code Type} 列没有行,不影响输出。
     */
    private Result routineStatus(Ast.Condition where) {
        return filter(ROUTINE_STATUS_COLUMNS, new ArrayList<>(), where);
    }

    private Result showEngines() {
        List<Object[]> rows = List.<Object[]>of(new Object[]{
                "InnoDB",
                "DEFAULT",
                "自研 LSM 存储引擎(报 InnoDB 仅为图形客户端兼容,K93)",
                "YES", "YES", "YES"});
        return new Result.Table(List.of("Engine", "Support", "Comment", "Transactions", "XA",
                "Savepoints"), rows);
    }

    private Result showCollation(String like) {
        List<Object[]> rows = new ArrayList<>();
        if (matchesLike("utf8mb4_general_ci", like)) {
            rows.add(new Object[]{"utf8mb4_general_ci", "utf8mb4", 45L, "Yes", "Yes", 1L});
        }
        if (matchesLike("utf8mb4_bin", like)) {
            rows.add(new Object[]{"utf8mb4_bin", "utf8mb4", 46L, "", "Yes", 1L});
        }
        return new Result.Table(List.of("Collation", "Charset", "Id", "Default", "Compiled",
                "Sortlen"), rows);
    }

    private Result showCharacterSet(String like) {
        List<Object[]> rows = new ArrayList<>();
        if (matchesLike("utf8mb4", like)) {
            rows.add(new Object[]{"utf8mb4", "UTF-8 Unicode", "utf8mb4_general_ci", 4L});
        }
        return new Result.Table(List.of("Charset", "Description", "Default collation", "Maxlen"),
                rows);
    }

    private Result showGrants() {
        String grantee = "'" + user + "'@'%'";
        List<Object[]> rows = List.<Object[]>of(new Object[]{
                "GRANT ALL PRIVILEGES ON *.* TO " + grantee + " WITH GRANT OPTION"});
        return new Result.Table(List.of("Grants for " + user + "@%"), rows);
    }

    /** 常用变量真值(FR-535);未列出的给 NULL 或不出现在列表里,绝不 1064。 */
    private Result showVariables(String like, Ast.Condition where) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("version", SqlEngine.SERVER_VERSION);
        vars.put("version_comment", "tangdb");
        vars.put("character_set_client", "utf8mb4");
        vars.put("character_set_connection", "utf8mb4");
        vars.put("character_set_results", "utf8mb4");
        vars.put("character_set_server", "utf8mb4");
        vars.put("character_set_database", "utf8mb4");
        vars.put("collation_connection", "utf8mb4_general_ci");
        vars.put("collation_server", "utf8mb4_general_ci");
        vars.put("collation_database", "utf8mb4_general_ci");
        vars.put("lower_case_table_names", "1");
        vars.put("sql_mode", "");
        vars.put("max_allowed_packet", "67108864");
        vars.put("autocommit", "1");
        vars.put("transaction_isolation", "REPEATABLE-READ");
        vars.put("tx_isolation", "REPEATABLE-READ");
        vars.put("time_zone", "Asia/Shanghai");
        vars.put("system_time_zone", "CST");
        vars.put("wait_timeout", "28800");
        vars.put("interactive_timeout", "28800");
        vars.put("net_write_timeout", "60");
        vars.put("net_read_timeout", "30");
        vars.put("max_connections", String.valueOf(sessions == null ? 151 : sessions.maxSessions()));
        vars.put("license", "GPL");
        vars.put("version_compile_os", "Win64");
        vars.put("hostname", "localhost");
        vars.put("performance_schema", "0");
        List<Object[]> rows = new ArrayList<>();
        for (Map.Entry<String, String> e : vars.entrySet()) {
            if (matchesLike(e.getKey(), like)) {
                rows.add(new Object[]{e.getKey(), e.getValue()});
            }
        }
        return filter(List.of("Variable_name", "Value"), rows, where);
    }

    private Result showStatus(String like, Ast.Condition where) {
        List<Object[]> rows = new ArrayList<>();
        addStatus(rows, "Uptime", 0L, like);
        addStatus(rows, "Threads_connected", (long) (sessions == null ? 0 : sessions.size()), like);
        addStatus(rows, "Threads_running", 0L, like);
        addStatus(rows, "Queries", 0L, like);
        addStatus(rows, "Slow_queries", 0L, like);
        return filter(List.of("Variable_name", "Value"), rows, where);
    }

    private static void addStatus(List<Object[]> rows, String name, long value, String like) {
        if (matchesLike(name, like)) {
            rows.add(new Object[]{name, value});
        }
    }

    private Result showProcesslist(boolean full) {
        List<Object[]> rows = new ArrayList<>();
        if (sessions != null) {
            for (Session session : sessions.all()) {
                Object[] row = new Object[]{
                        session.id(),
                        user,
                        session.source(),
                        session.currentDatabase(),
                        "Query",
                        Math.max(0, (System.currentTimeMillis() - session.startTimeMillis()) / 1000),
                        "",
                        null
                };
                if (full) {
                    row = java.util.Arrays.copyOf(row, row.length + 1);
                    row[row.length - 1] = 0L; // Progress
                }
                rows.add(row);
            }
        }
        List<String> columns = full
                ? List.of("Id", "User", "Host", "db", "Command", "Time", "State", "Info", "Progress")
                : List.of("Id", "User", "Host", "db", "Command", "Time", "State", "Info");
        return new Result.Table(columns, rows);
    }

    // ============================================================ information_schema

    /**
     * 执行一条 `FROM information_schema.*` 的 SELECT(FR-537 / K98 / K99):
     * 投影裁剪 + WHERE + LIMIT(含 offset)+ 多列 ORDER BY + COUNT(*)。
     * JOIN / 子查询 / GROUP BY / DISTINCT 明确报 1235(边界 28/29)。
     */
    public Result informationSchema(Ast.Select select) {
        String tableName = select.table().toLowerCase(Locale.ROOT);
        VTable table = virtualTable(tableName);
        if (table == null) {
            throw new SqlError(SqlError.ER_NO_SUCH_TABLE, "42S02",
                    "information_schema." + tableName + " 不存在");
        }

        List<Object[]> filtered = filterRows(table, select.where());

        if (select.countStar()) {
            // FR-555:别名即结果列名;没有别名时列名仍是 COUNT(*)。
            String column = select.alias() == null ? "COUNT(*)" : select.alias();
            return new Result.Table(List.of(column),
                    List.<Object[]>of(new Object[]{(long) filtered.size()}));
        }

        if (!select.orderBy().isEmpty()) {
            // 多列排序(K99):按列序依次比较,前一列相等才看下一列;每列独立 ASC/DESC。
            Comparator<Object[]> cmp = null;
            for (Ast.Order order : select.orderBy()) {
                int idx = table.indexOf(order.column());
                if (idx < 0) {
                    throw new CatalogException("列 " + order.column() + " 不存在(information_schema."
                            + tableName + ")", SqlError.ER_BAD_FIELD_ERROR);
                }
                Comparator<Object[]> byColumn = (a, b) -> compareValues(a[idx], b[idx]);
                if (order.descending()) {
                    byColumn = byColumn.reversed();
                }
                cmp = cmp == null ? byColumn : cmp.thenComparing(byColumn);
            }
            filtered.sort(cmp);
        }

        // 与用户表同口径(FR-551):先过滤(已完成)→ 再跳过 offset → 再截断 count。
        if (select.offset() != null || select.limit() != null) {
            filtered = page(filtered, select.offset(), select.limit());
        }

        int[] projection;
        List<String> names = new ArrayList<>();
        if (select.star()) {
            projection = new int[table.columns().size()];
            for (int i = 0; i < projection.length; i++) {
                projection[i] = i;
                names.add(table.nameAt(i));
            }
        } else {
            projection = new int[select.items().size()];
            for (int i = 0; i < select.items().size(); i++) {
                Ast.Item item = select.items().get(i);
                int idx = table.indexOf(item.column());
                if (idx < 0) {
                    throw new CatalogException("列 " + item.column() + " 不存在(information_schema."
                            + tableName + ")", SqlError.ER_BAD_FIELD_ERROR);
                }
                projection[i] = idx;
                names.add(item.alias() == null ? table.nameAt(idx) : item.alias());
            }
        }

        List<Object[]> rows = new ArrayList<>(filtered.size());
        for (Object[] row : filtered) {
            Object[] projected = new Object[projection.length];
            for (int i = 0; i < projection.length; i++) {
                projected[i] = row[projection[i]];
            }
            rows.add(projected);
        }
        // 投影裁剪:只产出被查询的列(SC-336)。
        return new Result.Table(names, rows);
    }

    static SqlError notSupported(String message) {
        return new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000", message);
    }

    /** 分页:先跳过 offset,再截断 count(FR-551);越界返回空集,不报错(边界 32/33)。 */
    private static List<Object[]> page(List<Object[]> rows, Integer offset, Integer count) {
        int from = offset == null ? 0 : offset;
        if (from >= rows.size()) {
            return new ArrayList<>();
        }
        long end = count == null ? rows.size() : (long) from + count;
        int to = (int) Math.min((long) rows.size(), end);
        return new ArrayList<>(rows.subList(from, to));
    }

    private List<Object[]> filterRows(VTable table, Ast.Condition where) {
        if (where == null) {
            return new ArrayList<>(table.rows());
        }
        Predicate predicate = Predicate.bind(where, table.meta());
        List<Object[]> out = new ArrayList<>();
        for (Object[] row : table.rows()) {
            if (Boolean.TRUE.equals(predicate.test(row))) {
                out.add(row);
            }
        }
        return out;
    }

    /** 单列单行的固定结果(SHOW COUNT(*) WARNINGS / ERRORS,FR-561)。 */
    private static Result oneRow(String column, Object value) {
        return new Result.Table(List.of(column), List.<Object[]>of(new Object[]{value}));
    }

    /** 对 SHOW 的输出做 WHERE 过滤(列名与输出列名一致,SC-335 / FR-527)。 */
    private Result filter(List<String> columns, List<Object[]> rows, Ast.Condition where) {
        if (where == null) {
            return new Result.Table(columns, rows);
        }
        List<Column> cols = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            cols.add(new Column(columns.get(i).toLowerCase(Locale.ROOT), inferType(rows, i), 65535,
                    true, false, i));
        }
        return new VTable("show", columns, cols, rows).filter(rows, where);
    }

    /**
     * SHOW 输出的列类型按首行非 NULL 值推断(FR-562):数值列才能接受
     * {@code WHERE Non_unique = 0} 这类数字比较条件;空集/全 NULL 按 VARCHAR 处理。
     */
    private static DataType inferType(List<Object[]> rows, int column) {
        for (Object[] row : rows) {
            if (column >= row.length) {
                continue;
            }
            Object value = row[column];
            if (value instanceof Long || value instanceof Integer) {
                return DataType.BIGINT;
            }
            if (value instanceof Double || value instanceof Float) {
                return DataType.DOUBLE;
            }
            if (value instanceof Boolean) {
                return DataType.BOOLEAN;
            }
            if (value != null) {
                return DataType.VARCHAR;
            }
        }
        return DataType.VARCHAR;
    }

    // ============================================================ 虚拟表定义

    private VTable virtualTable(String name) {
        return switch (name) {
            case "schemata" -> schemata();
            case "tables" -> tablesTable();
            case "columns" -> columnsTable();
            case "statistics" -> statistics();
            case "key_column_usage" -> keyColumnUsage();
            case "table_constraints" -> tableConstraints();
            case "processlist" -> processlistTable();
            case "character_sets" -> new VTable("CHARACTER_SETS", cols(
                    c("CHARACTER_SET_NAME"), c("DEFAULT_COLLATE_NAME"), c("DESCRIPTION"),
                    n("MAXLEN")), List.<Object[]>of(new Object[]{
                    "utf8mb4", "utf8mb4_general_ci", "UTF-8 Unicode", 4L}));
            case "collations" -> new VTable("COLLATIONS", cols(
                    c("COLLATION_NAME"), c("CHARACTER_SET_NAME"), n("ID"), c("IS_DEFAULT"),
                    c("IS_COMPILED"), n("SORTLEN")), List.<Object[]>of(new Object[]{
                    "utf8mb4_general_ci", "utf8mb4", 45L, "Yes", "Yes", 1L}));
            case "engines" -> new VTable("ENGINES", cols(
                    c("ENGINE"), c("SUPPORT"), c("COMMENT"), c("TRANSACTIONS"), c("XA"),
                    c("SAVEPOINTS")), List.<Object[]>of(new Object[]{
                    "InnoDB", "DEFAULT", "自研 LSM 存储引擎(报 InnoDB 仅为客户端兼容,K93)",
                    "YES", "YES", "YES"}));
            case "partitions" -> new VTable("PARTITIONS", cols(
                    c("TABLE_CATALOG"), c("TABLE_SCHEMA"), c("TABLE_NAME"), c("PARTITION_NAME"),
                    c("SUBPARTITION_NAME"), n("PARTITION_ORDINAL_POSITION"),
                    n("SUBPARTITION_ORDINAL_POSITION"), c("PARTITION_METHOD"),
                    c("SUBPARTITION_METHOD"), c("PARTITION_EXPRESSION"),
                    c("SUBPARTITION_EXPRESSION"), c("PARTITION_DESCRIPTION"), n("TABLE_ROWS"),
                    n("AVG_ROW_LENGTH"), n("DATA_LENGTH"), n("MAX_DATA_LENGTH"), n("INDEX_LENGTH"),
                    n("DATA_FREE"), c("CREATE_TIME"), c("UPDATE_TIME"), c("CHECK_TIME"),
                    n("CHECKSUM"), c("PARTITION_COMMENT"), c("NODEGROUP"), c("TABLESPACE_NAME")),
                    List.of());
            case "referential_constraints" -> new VTable("REFERENTIAL_CONSTRAINTS", cols(
                    c("CONSTRAINT_CATALOG"), c("CONSTRAINT_SCHEMA"), c("CONSTRAINT_NAME"),
                    c("UNIQUE_CONSTRAINT_CATALOG"), c("UNIQUE_CONSTRAINT_SCHEMA"),
                    c("UNIQUE_CONSTRAINT_NAME"), c("MATCH_OPTION"), c("UPDATE_RULE"),
                    c("DELETE_RULE"), c("TABLE_NAME"), c("REFERENCED_TABLE_NAME")), List.of());
            case "views" -> new VTable("VIEWS", cols(
                    c("TABLE_CATALOG"), c("TABLE_SCHEMA"), c("TABLE_NAME"), c("VIEW_DEFINITION"),
                    c("CHECK_OPTION"), c("IS_UPDATABLE"), c("DEFINER"), c("SECURITY_TYPE"),
                    c("CHARACTER_SET_CLIENT"), c("COLLATION_CONNECTION")), List.of());
            case "routines" -> new VTable("ROUTINES", cols(
                    c("SPECIFIC_NAME"), c("ROUTINE_CATALOG"), c("ROUTINE_SCHEMA"),
                    c("ROUTINE_NAME"), c("ROUTINE_TYPE"), c("DATA_TYPE"),
                    c("ROUTINE_DEFINITION"), c("IS_DETERMINISTIC"), c("SQL_DATA_ACCESS"),
                    c("SECURITY_TYPE"), c("CREATED"), c("LAST_ALTERED"), c("SQL_MODE"),
                    c("ROUTINE_COMMENT"), c("DEFINER")), List.of());
            case "triggers" -> new VTable("TRIGGERS", cols(
                    c("TRIGGER_CATALOG"), c("TRIGGER_SCHEMA"), c("TRIGGER_NAME"),
                    c("EVENT_MANIPULATION"), c("EVENT_OBJECT_CATALOG"), c("EVENT_OBJECT_SCHEMA"),
                    c("EVENT_OBJECT_TABLE"), n("ACTION_ORDER"), c("ACTION_CONDITION"),
                    c("ACTION_STATEMENT"), c("ACTION_ORIENTATION"), c("ACTION_TIMING"),
                    c("ACTION_REFERENCE_OLD_TABLE"), c("ACTION_REFERENCE_NEW_TABLE"),
                    c("ACTION_REFERENCE_OLD_ROW"), c("ACTION_REFERENCE_NEW_ROW"), c("CREATED"),
                    c("SQL_MODE"), c("DEFINER")), List.of());
            case "events" -> new VTable("EVENTS", cols(
                    c("EVENT_CATALOG"), c("EVENT_SCHEMA"), c("EVENT_NAME"), c("DEFINER"),
                    c("TIME_ZONE"), c("EVENT_BODY"), c("EVENT_DEFINITION"), c("EVENT_TYPE"),
                    c("EXECUTE_AT"), c("INTERVAL_VALUE"), c("INTERVAL_FIELD"), c("SQL_MODE"),
                    c("STARTS"), c("ENDS"), c("STATUS"), c("ON_COMPLETION"), c("CREATED"),
                    c("LAST_ALTERED"), c("LAST_EXECUTED"), c("EVENT_COMMENT"), n("ORIGINATOR"),
                    c("CHARACTER_SET_CLIENT"), c("COLLATION_CONNECTION"),
                    c("DATABASE_COLLATION")), List.of());
            case "user_privileges" -> new VTable("USER_PRIVILEGES", cols(
                    c("GRANTEE"), c("TABLE_CATALOG"), c("PRIVILEGE_TYPE"), c("IS_GRANTABLE")),
                    List.<Object[]>of(new Object[]{
                            "'" + user + "'@'%'", "def", "ALL PRIVILEGES", "YES"}));
            default -> null;
        };
    }

    private VTable schemata() {
        List<Object[]> rows = new ArrayList<>();
        for (String db : catalog.databases()) {
            rows.add(new Object[]{"def", db, "utf8mb4", "utf8mb4_general_ci", null});
        }
        return new VTable("SCHEMATA", cols(c("CATALOG_NAME"), c("SCHEMA_NAME"),
                c("DEFAULT_CHARACTER_SET_NAME"), c("DEFAULT_COLLATION_NAME"), c("SQL_PATH")), rows);
    }

    private VTable tablesTable() {
        List<Object[]> rows = new ArrayList<>();
        for (String db : catalog.databases()) {
            for (TableMeta table : catalog.tables(db)) {
                rows.add(new Object[]{"def", db, table.name(), "BASE TABLE", "InnoDB",
                        10L, "Dynamic", null, "utf8mb4_general_ci", table.createdAt()});
            }
        }
        return new VTable("TABLES", cols(c("TABLE_CATALOG"), c("TABLE_SCHEMA"), c("TABLE_NAME"),
                c("TABLE_TYPE"), c("ENGINE"), n("VERSION"), c("ROW_FORMAT"), n("TABLE_ROWS"),
                c("TABLE_COLLATION"), c("CREATE_TIME")), rows);
    }

    private VTable columnsTable() {
        List<Object[]> rows = new ArrayList<>();
        for (String db : catalog.databases()) {
            for (TableMeta table : catalog.tables(db)) {
                for (Column column : table.columns()) {
                    rows.add(new Object[]{
                            "def", db, table.name(), column.name(), (long) (column.ordinal() + 1),
                            null, column.nullable() ? "YES" : "NO",
                            mysqlDataType(column), (long) column.length(), null,
                            columnKey(table, column), ""});
                }
            }
        }
        return new VTable("COLUMNS", cols(c("TABLE_CATALOG"), c("TABLE_SCHEMA"), c("TABLE_NAME"),
                c("COLUMN_NAME"), n("ORDINAL_POSITION"), c("COLUMN_DEFAULT"), c("IS_NULLABLE"),
                c("DATA_TYPE"), n("CHARACTER_MAXIMUM_LENGTH"), c("COLUMN_TYPE"),
                c("COLUMN_KEY"), c("EXTRA")), rows);
    }

    private VTable statistics() {
        List<Object[]> rows = new ArrayList<>();
        for (String db : catalog.databases()) {
            for (TableMeta table : catalog.tables(db)) {
                Column pk = table.primaryKey();
                if (pk != null) {
                    rows.add(new Object[]{"def", db, table.name(), 0L, db, "PRIMARY", 1L,
                            pk.name(), "A", null, null, null, pk.nullable() ? "YES" : "",
                            "BTREE", "", ""});
                }
                for (IndexMeta index : catalog.indexes(table.id())) {
                    Column column = table.column(index.columnName());
                    rows.add(new Object[]{"def", db, table.name(), index.unique() ? 0L : 1L, db,
                            index.name(), 1L, index.columnName(), "A", null, null, null,
                            column != null && column.nullable() ? "YES" : "",
                            "BTREE", "", ""});
                }
            }
        }
        return new VTable("STATISTICS", cols(c("TABLE_CATALOG"), c("TABLE_SCHEMA"),
                c("TABLE_NAME"), n("NON_UNIQUE"), c("INDEX_SCHEMA"), c("INDEX_NAME"),
                n("SEQ_IN_INDEX"), c("COLUMN_NAME"), c("COLLATION"), n("CARDINALITY"),
                n("SUB_PART"), c("PACKED"), c("NULLABLE"), c("INDEX_TYPE"), c("COMMENT"),
                c("INDEX_COMMENT")), rows);
    }

    private VTable keyColumnUsage() {
        List<Object[]> rows = new ArrayList<>();
        for (String db : catalog.databases()) {
            for (TableMeta table : catalog.tables(db)) {
                Column pk = table.primaryKey();
                if (pk != null) {
                    rows.add(new Object[]{"def", db, "PRIMARY", "def", db, table.name(),
                            pk.name(), 1L, null, null, null, null});
                }
                for (IndexMeta index : catalog.indexes(table.id())) {
                    rows.add(new Object[]{"def", db, index.name(), "def", db, table.name(),
                            index.columnName(), 1L, null, null, null, null});
                }
            }
        }
        return new VTable("KEY_COLUMN_USAGE", cols(c("CONSTRAINT_CATALOG"),
                c("CONSTRAINT_SCHEMA"), c("CONSTRAINT_NAME"), c("TABLE_CATALOG"),
                c("TABLE_SCHEMA"), c("TABLE_NAME"), c("COLUMN_NAME"), n("ORDINAL_POSITION"),
                n("POSITION_IN_UNIQUE_CONSTRAINT"), c("REFERENCED_TABLE_SCHEMA"),
                c("REFERENCED_TABLE_NAME"), c("REFERENCED_COLUMN_NAME")), rows);
    }

    private VTable tableConstraints() {
        List<Object[]> rows = new ArrayList<>();
        for (String db : catalog.databases()) {
            for (TableMeta table : catalog.tables(db)) {
                rows.add(new Object[]{"def", db, "PRIMARY", db, table.name(), "PRIMARY KEY"});
                for (IndexMeta index : catalog.indexes(table.id())) {
                    if (index.unique()) {
                        rows.add(new Object[]{"def", db, index.name(), db, table.name(), "UNIQUE"});
                    }
                }
            }
        }
        return new VTable("TABLE_CONSTRAINTS", cols(c("CONSTRAINT_CATALOG"),
                c("CONSTRAINT_SCHEMA"), c("CONSTRAINT_NAME"), c("TABLE_SCHEMA"),
                c("TABLE_NAME"), c("CONSTRAINT_TYPE")), rows);
    }

    private VTable processlistTable() {
        List<Object[]> rows = new ArrayList<>();
        if (sessions != null) {
            for (Session session : sessions.all()) {
                rows.add(new Object[]{session.id(), user, session.source(),
                        session.currentDatabase(), "Query",
                        (System.currentTimeMillis() - session.startTimeMillis()) / 1000,
                        "", null});
            }
        }
        return new VTable("PROCESSLIST", cols(n("ID"), c("USER"), c("HOST"), c("DB"),
                c("COMMAND"), n("TIME"), c("STATE"), c("INFO")), rows);
    }

    // ============================================================ 工具

    private String resolveDb(String database) {
        String db = database == null ? executor.currentDatabase() : database.toLowerCase(Locale.ROOT);
        catalog.requireDatabase(db);
        return db;
    }

    private TableMeta requireTable(String database, String table) {
        return catalog.require(resolveDb(database), table);
    }

    private String columnKey(TableMeta table, Column column) {
        if (column.primaryKey()) {
            return "PRI";
        }
        IndexMeta index = null;
        for (IndexMeta candidate : catalog.indexes(table.id())) {
            if (candidate.columnName().equals(column.name())) {
                index = candidate;
                break;
            }
        }
        if (index == null) {
            return "";
        }
        return index.unique() ? "UNI" : "MUL";
    }

    private static String mysqlDataType(Column column) {
        return switch (column.type()) {
            case INT -> "int";
            case BIGINT -> "bigint";
            case DOUBLE -> "double";
            case VARCHAR -> "varchar";
            case DATE -> "date";
            case BOOLEAN -> "tinyint";
        };
    }

    /** LIKE 模式(仅 % 与 _;大小写不敏感,表名/列名本来就小写)。 */
    static boolean matchesLike(String value, String pattern) {
        if (pattern == null) {
            return true;
        }
        StringBuilder regex = new StringBuilder("^");
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '%' -> regex.append(".*");
                case '_' -> regex.append('.');
                default -> regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        regex.append('$');
        return value != null && value.matches("(?is)" + regex);
    }

    static int compareValues(Object a, Object b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        if (a instanceof Boolean ba && b instanceof Boolean bb) {
            return Boolean.compare(ba, bb);
        }
        return java.util.Arrays.compare(
                String.valueOf(a).getBytes(StandardCharsets.UTF_8),
                String.valueOf(b).getBytes(StandardCharsets.UTF_8));
    }

    private static Column c(String name) {
        return new Column(name, DataType.VARCHAR, 65535, true, false, 0);
    }

    private static Column n(String name) {
        return new Column(name, DataType.BIGINT, 0, true, false, 0);
    }

    private static List<Column> cols(Column... columns) {
        List<Column> list = new ArrayList<>(columns.length);
        for (int i = 0; i < columns.length; i++) {
            Column c = columns[i];
            list.add(new Column(c.name(), c.type(), c.length(), c.nullable(), false, i));
        }
        return list;
    }

    /** 内存虚拟表:显示列名 + 列定义(小写,供谓词绑定)+ 行。 */
    private static final class VTable {
        private final String name;
        private final List<String> displayNames;
        private final List<Column> columns;
        private final List<Object[]> rows;

        /** information_schema 用:显示名 = 列定义名的大写(MySQL 5.7 口径)。 */
        VTable(String name, List<Column> columns, List<Object[]> rows) {
            this(name, columns.stream().map(c -> c.name().toUpperCase(Locale.ROOT)).toList(),
                    columns, rows);
        }

        VTable(String name, List<String> displayNames, List<Column> columns, List<Object[]> rows) {
            this.name = name;
            this.displayNames = List.copyOf(displayNames);
            this.columns = List.copyOf(columns);
            this.rows = List.copyOf(rows);
        }

        List<Column> columns() {
            return columns;
        }

        String nameAt(int i) {
            return displayNames.get(i);
        }

        List<Object[]> rows() {
            return new ArrayList<>(rows);
        }

        int indexOf(String columnName) {
            if (columnName == null) {
                return -1;
            }
            for (int i = 0; i < displayNames.size(); i++) {
                if (displayNames.get(i).equalsIgnoreCase(columnName)
                        || columns.get(i).name().equalsIgnoreCase(columnName)) {
                    return i;
                }
            }
            return -1;
        }

        TableMeta meta() {
            return new TableMeta(0, name, TableMeta.STATE_ACTIVE, "", columns, true);
        }

        Result.Table filter(List<Object[]> input, Ast.Condition where) {
            Predicate predicate = Predicate.bind(where, meta());
            List<Object[]> out = new ArrayList<>();
            for (Object[] row : input) {
                if (Boolean.TRUE.equals(predicate.test(row))) {
                    out.add(row);
                }
            }
            return new Result.Table(displayNames, out);
        }
    }

    /** 会话表里"现在几点";供 SHOW PROCESSLIST 复用。 */
    static String nowText() {
        return TIME_FMT.format(Instant.now());
    }
}
