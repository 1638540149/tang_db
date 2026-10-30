package com.wangyu.tangdb.slow;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.catalog.SystemTables;
import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.session.Session;
import com.wangyu.tangdb.stats.StatsService;
import com.wangyu.tangdb.stats.TableStats;
import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.IndexMeta;
import com.wangyu.tangdb.storage.KeyCodec;
import com.wangyu.tangdb.storage.KeyValue;
import com.wangyu.tangdb.storage.KvStore;
import com.wangyu.tangdb.storage.RowCodec;
import com.wangyu.tangdb.storage.TableMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 慢查询日志与诊断建议(SPEC-P8 C 组 FR-831 ~ FR-840 / K190 / K191)。
 *
 * <p>超过阈值的语句落系统表 {@code sys_slow_query}(表 ID 10),并在内存里保留有序副本
 * 供 {@code SHOW SLOW QUERIES} 秒回。建议是**纯规则 + 必须带证据**(FR-835 / FR-836):
 * 计划行、统计行数、现有索引清单都是可直接核对的数字,拿不到证据时输出"证据不足"。</p>
 *
 * <p>阈值内的语句只多一次 {@code System.nanoTime()} 与一次比较,不落库、不生成计划(FR-840)。</p>
 */
public final class SlowQueryLog {

    public static final List<String> COLUMNS = List.of(
            "id", "start_time", "duration_ms", "rows_scanned", "rows_returned", "client",
            "db_name", "table_names", "sql_text", "fingerprint", "plan_summary", "advice");

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int MAX_ADVICE_CHARS = 4096;
    private static final int MAX_PLAN_CHARS = 1024;
    private static final Pattern TABLE_REF = Pattern.compile(
            "(?i)\\b(?:from|into|update|join)\\s+([`A-Za-z0-9_$.]+)");
    private static final Pattern PREDICATE_COLUMN = Pattern.compile(
            "(?i)\\b([`A-Za-z_][A-Za-z0-9_]*)\\s*(=|<>|!=|<=|>=|<|>|\\bIN\\b|\\bLIKE\\b"
                    + "|\\bBETWEEN\\b|\\bIS\\b)");
    private static final Pattern WHERE_TAIL = Pattern.compile(
            "(?i)\\b(group\\s+by|order\\s+by|having|limit)\\b");

    /** 生成执行计划文本的钩子(由 SqlEngine 注入,复用同一个计划入口)。 */
    public interface Explainer {
        String explain(Session session, String sql);
    }

    /** 一条慢查询记录。 */
    public record Entry(long id, String startTime, long durationMs, long rowsScanned,
                        long rowsReturned, String client, String database, String tables,
                        String sqlText, String fingerprint, String planSummary, String advice) {
    }

    private final KvStore kv;
    private final Catalog catalog;
    private final StatsService stats;
    private final boolean enabled;
    private final int thresholdMs;
    private final int maxRows;

    private final NavigableMap<Long, Entry> entries = new TreeMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private volatile Explainer explainer;

    public SlowQueryLog(KvStore kv, Catalog catalog, StatsService stats,
                        boolean enabled, int thresholdMs, int maxRows) {
        this.kv = kv;
        this.catalog = catalog;
        this.stats = stats;
        this.enabled = enabled;
        // 边界 175:阈值 <= 0 等价于关闭记录。
        this.thresholdMs = Math.max(0, thresholdMs);
        this.maxRows = Math.max(1, maxRows);
        load();
    }

    public void setExplainer(Explainer explainer) {
        this.explainer = explainer;
    }

    public boolean enabled() {
        return enabled && thresholdMs > 0;
    }

    public int thresholdMs() {
        return thresholdMs;
    }

    public int maxRows() {
        return maxRows;
    }

    /** 启动时把已落库的记录读回内存,并恢复 id 水位。 */
    private void load() {
        if (kv == null) {
            return;
        }
        try {
            List<KeyValue> rows = kv.scan(
                    KeyCodec.tableRangeStart(SystemTables.SYS_SLOW_QUERY),
                    KeyCodec.tableRangeEnd(SystemTables.SYS_SLOW_QUERY), 0);
            for (KeyValue row : rows) {
                RowCodec.Decoded decoded = RowCodec.decode(SystemTables.SLOW_QUERY, row.value());
                if (decoded.tombstone()) {
                    continue;
                }
                Entry entry = fromValues(decoded.values());
                entries.put(entry.id(), entry);
                nextId.updateAndGet(current -> Math.max(current, entry.id() + 1));
            }
            while (entries.size() > maxRows) {
                entries.pollFirstEntry();
            }
        } catch (RuntimeException e) {
            // 老目录 / 系统表缺失不影响启动(与 P4 sys_indexes 的幂等口径一致)
        }
    }

    // ------------------------------------------------------------ 记录

    /**
     * 记录一条刚执行完的语句。阈值内直接返回(FR-840)。
     *
     * @param result 成功时的结果(用于返回行数);失败时传 null
     * @param error  失败时的错误;成功时传 null
     */
    public void record(Session session, String sql, long durationMs, Result result, SqlError error) {
        record(session, sql, durationMs, rowsReturned(result), error);
    }

    /**
     * 记录一条刚执行完的语句(流式结果集用:行数由调用方统计)。
     * 阈值内直接返回(FR-840)。
     */
    public void record(Session session, String sql, long durationMs, long rowsReturned,
                       SqlError error) {
        if (!enabled()) {
            return;
        }
        if (durationMs < thresholdMs) {
            return;
        }
        String statement = sql == null ? "" : sql.trim();
        if (statement.isEmpty() || isDiagnostic(statement)) {
            return; // 诊断语句自身不记录,避免自我放大
        }
        String database = session == null ? SystemTables.DEFAULT_DATABASE : session.currentDatabase();
        String client = session == null ? "" : session.source();
        Diagnosis diagnosis = diagnose(session, statement, rowsReturned);
        long id = nextId.getAndIncrement();
        Entry entry = new Entry(id, TIME_FMT.format(Instant.now()), durationMs,
                diagnosis.rowsScanned(), rowsReturned, client, database,
                diagnosis.tables(), SqlText.summary(statement), SqlText.fingerprint(statement),
                diagnosis.planSummary(), diagnosis.advice());
        entries.put(id, entry);
        persist(entry);
        evict();
    }

    private static boolean isDiagnostic(String statement) {
        String upper = statement.toUpperCase(Locale.ROOT);
        return upper.startsWith("SHOW SLOW QUER") || upper.startsWith("SHOW METRICS")
                || upper.startsWith("SHOW BACKUP JOBS") || upper.startsWith("SHOW SESSIONS");
    }

    private static long rowsReturned(Result result) {
        if (result instanceof Result.Table table) {
            return table.rows().size();
        }
        if (result instanceof Result.Message message) {
            return Math.max(0, message.affectedRows());
        }
        return 0;
    }

    private void persist(Entry entry) {
        if (kv == null) {
            return;
        }
        try {
            kv.put(SystemTables.slowQueryKey(entry.id()),
                    SystemTables.encodeSlowQueryRow(entry.id(), entry.startTime(),
                            entry.durationMs(), entry.rowsScanned(), entry.rowsReturned(),
                            entry.client(), entry.database(), entry.tables(), entry.sqlText(),
                            entry.fingerprint(), entry.planSummary(), entry.advice()));
        } catch (RuntimeException e) {
            // 落库失败不影响查询本身:内存副本仍可查
        }
    }

    /** FR-839:超过上限按时间淘汰最旧(写墓碑,不报错)。 */
    private void evict() {
        while (entries.size() > maxRows) {
            Map.Entry<Long, Entry> oldest = entries.pollFirstEntry();
            if (oldest == null || kv == null) {
                return;
            }
            try {
                kv.put(SystemTables.slowQueryKey(oldest.getKey()),
                        RowCodec.encodeTombstone(SystemTables.SYS_SLOW_QUERY));
            } catch (RuntimeException ignored) {
                // 淘汰失败最多多占一行,不影响正确性
            }
        }
    }

    // ------------------------------------------------------------ 查询

    /** 按时间倒序(最新在前)。 */
    public List<Entry> recent(int limit) {
        List<Entry> out = new ArrayList<>(entries.descendingMap().values());
        if (limit > 0 && out.size() > limit) {
            return new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    public Entry find(long id) {
        return entries.get(id);
    }

    public int size() {
        return entries.size();
    }

    // ------------------------------------------------------------ 诊断(纯规则 + 证据)

    /** 诊断结果:计划摘要 / 扫描行数 / 涉及表 / 建议文本。 */
    public record Diagnosis(String planSummary, long rowsScanned, String tables, String advice) {
    }

    /**
     * 生成建议:①计划摘要 ②无索引谓词列 ③候选索引 + 估算收益 ④统计是否过期。
     * 每条结论都带证据字段;拿不到证据时写"证据不足",不给玄学结论(FR-835)。
     */
    public Diagnosis diagnose(Session session, String sql, long rowsReturned) {
        Set<String> tables = referencedTables(sql);
        String tableNames = String.join(",", tables);
        String plan = explainSafely(session, sql);
        List<String> lines = new ArrayList<>();
        if (plan == null || plan.isBlank()) {
            String advice = "证据不足:该语句无法生成执行计划(EXPLAIN 仅支持 SELECT / UPDATE / DELETE),"
                    + "不给索引结论";
            return new Diagnosis("", 0, tableNames, advice);
        }
        List<String> planLines = new ArrayList<>();
        for (String line : plan.split("\\n")) {
            if (!line.isBlank()) {
                planLines.add(line.trim());
            }
        }
        String planSummary = String.join(" | ", planLines);
        if (planSummary.length() > MAX_PLAN_CHARS) {
            planSummary = planSummary.substring(0, MAX_PLAN_CHARS) + "...";
        }
        boolean fullScan = plan.toUpperCase(Locale.ROOT).contains("TABLE SCAN");
        lines.add("计划摘要: " + (planLines.isEmpty() ? "(空)" : planLines.get(0)));

        TableMeta table = firstTable(tables);
        if (table == null) {
            lines.add("证据不足:无法解析语句涉及的表,不给索引结论");
            return new Diagnosis(planSummary, 0, tableNames, clamp(lines));
        }
        TableStats tableStats = stats == null ? null : stats.tableStats(table);
        long estimatedRows = stats == null ? 0 : stats.estimatedRowCount(table);
        boolean stale = tableStats == null || stats.isStale(tableStats);
        long rowsScanned = fullScan ? estimatedRows : 0;
        lines.add("证据: 访问路径=" + (fullScan ? "全表扫描(TABLE SCAN)" : "索引路径")
                + "; 估算行数=" + estimatedRows
                + "; 实际返回行数=" + rowsReturned
                + "; 统计=" + (tableStats == null ? "未采集"
                        : "分析于 " + tableStats.lastAnalyze()
                        + (stale ? "(已过期)" : "(有效)")));
        lines.add("证据: 表 " + table.name() + " 行数统计=" + estimatedRows
                + (tableStats == null ? "(无统计,采用默认估算 " + estimatedRows + " 行)" : ""));

        List<String> existing = new ArrayList<>();
        for (IndexMeta index : catalog.indexes(table.id())) {
            existing.add(index.name() + "(" + index.columnName() + ")");
        }
        Column pk = table.primaryKey();
        if (pk != null) {
            existing.add("PRIMARY(" + pk.name() + ")");
        }
        lines.add("证据: 现有索引=" + (existing.isEmpty() ? "无" : String.join(", ", existing)));

        for (String columnName : predicateColumns(sql)) {
            Column column = table.column(columnName);
            if (column == null) {
                continue;
            }
            IndexMeta index = catalog.indexOnColumn(table.id(), column.name());
            if (column.primaryKey() || index != null) {
                // FR-837:已存在的索引/主键列不再建议。
                String which = column.primaryKey() ? "PRIMARY"
                        : index.name() + "(" + index.columnName() + ")";
                lines.add("列 " + column.name() + " 已有索引 " + which + ",不再建议(FR-837)");
                continue;
            }
            lines.add("列 " + column.name() + " 出现在谓词中但无索引(表 " + table.name()
                    + " 现有索引: " + (existing.isEmpty() ? "无" : String.join(", ", existing)) + ")");
            lines.add("候选索引: CREATE INDEX idx_" + table.name() + "_" + column.name()
                    + " ON " + table.name() + " (" + column.name() + ");证据: 该列无索引 + 计划="
                    + (fullScan ? "TABLE SCAN" : "索引路径"));
            if (estimatedRows > 0) {
                lines.add("估算收益: 当前扫描 " + estimatedRows + " 行(统计行数),等值谓词走索引预期回表 1 行");
            }
        }
        if (lines.size() <= 4) {
            lines.add("证据不足:谓词中没有可建议索引的列(可能已全部有索引或不含等值/范围谓词)");
        }
        return new Diagnosis(planSummary, rowsScanned, tableNames, clamp(lines));
    }

    private static String clamp(List<String> lines) {
        String text = String.join("\n", lines);
        return text.length() <= MAX_ADVICE_CHARS ? text : text.substring(0, MAX_ADVICE_CHARS) + "...";
    }

    private String explainSafely(Session session, String sql) {
        Explainer current = explainer;
        if (current == null) {
            return null;
        }
        try {
            return current.explain(session, sql);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private TableMeta firstTable(Set<String> tables) {
        for (String name : tables) {
            String plain = name.replace("`", "");
            int dot = plain.indexOf('.');
            String db = dot > 0 ? plain.substring(0, dot) : SystemTables.DEFAULT_DATABASE;
            String table = dot > 0 ? plain.substring(dot + 1) : plain;
            TableMeta meta = catalog.find(db, table);
            if (meta != null) {
                return meta;
            }
            meta = catalog.find(table);
            if (meta != null) {
                return meta;
            }
        }
        return null;
    }

    /** 语句里出现的表名(FROM / INTO / UPDATE / JOIN 之后)。 */
    public static Set<String> referencedTables(String sql) {
        Set<String> tables = new LinkedHashSet<>();
        Matcher matcher = TABLE_REF.matcher(sql);
        while (matcher.find()) {
            String raw = matcher.group(1);
            if (raw != null && !raw.isBlank()) {
                tables.add(raw.toLowerCase(Locale.ROOT));
            }
        }
        return tables;
    }

    /** WHERE 子句里被谓词引用的列名(去重、保序)。 */
    public static List<String> predicateColumns(String sql) {
        int where = indexOfKeyword(sql, "where");
        if (where < 0) {
            return List.of();
        }
        String tail = sql.substring(where + "where".length());
        Matcher stopper = WHERE_TAIL.matcher(tail);
        if (stopper.find()) {
            tail = tail.substring(0, stopper.start());
        }
        Set<String> columns = new LinkedHashSet<>();
        Matcher matcher = PREDICATE_COLUMN.matcher(tail);
        while (matcher.find()) {
            String name = matcher.group(1).replace("`", "").toLowerCase(Locale.ROOT);
            if (!"and".equals(name) && !"or".equals(name) && !"not".equals(name)) {
                columns.add(name);
            }
        }
        return new ArrayList<>(columns);
    }

    /** 词法级查找关键字(跳过引号/反引号内的内容,大小写不敏感)。 */
    private static int indexOfKeyword(String sql, String keyword) {
        String lower = sql.toLowerCase(Locale.ROOT);
        int i = 0;
        int n = lower.length();
        while (i < n) {
            char c = lower.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c;
                i++;
                while (i < n) {
                    if (lower.charAt(i) == quote) {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (lower.startsWith(keyword, i)) {
                boolean before = i == 0 || !Character.isLetterOrDigit(lower.charAt(i - 1));
                int after = i + keyword.length();
                boolean afterOk = after >= n || !Character.isLetterOrDigit(lower.charAt(after));
                if (before && afterOk) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    private static Entry fromValues(Object[] v) {
        return new Entry(((Number) v[0]).longValue(), (String) v[1],
                ((Number) v[2]).longValue(), ((Number) v[3]).longValue(),
                ((Number) v[4]).longValue(), (String) v[5], (String) v[6], (String) v[7],
                (String) v[8], (String) v[9], (String) v[10], (String) v[11]);
    }
}
