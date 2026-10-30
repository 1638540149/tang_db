package com.wangyu.tangdb.execute;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.parser.Ast;
import com.wangyu.tangdb.parser.ParseException;
import com.wangyu.tangdb.parser.Parser;
import com.wangyu.tangdb.proto.HeartbeatRequest;
import com.wangyu.tangdb.server.BackendRegistry;
import com.wangyu.tangdb.session.Session;
import com.wangyu.tangdb.session.SessionManager;
import com.wangyu.tangdb.storage.KvStore;

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
    private final int indexBackfillBatch;
    private final int queryBatchRows;
    private final long sortSpillBytes;
    private final String sortTmpDir;
    private final com.wangyu.tangdb.planner.OptimizerConfig optimizer;
    private final com.wangyu.tangdb.stats.StatsService stats;
    /** SPEC-P6.1 执行层参数(默认取 P6 语义,由常驻 FE 按配置覆盖)。 */
    private int queryParallelism = 1;
    private boolean pushdownEnabled = false;
    private int scanBatchRows = 4096;
    /** SPEC-P7 §5.4:向量检索门限 + 会话开关(默认值即规约门限)。 */
    private com.wangyu.tangdb.query.P7Options p7 = com.wangyu.tangdb.query.P7Options.defaults();
    /** SPEC-P7 E 组:推理算子(未注入 = 端点未配置,使用 ai_embed 会明确报错)。 */
    private com.wangyu.tangdb.ai.AiService ai;
    /** SPEC-P8 B 组:两条通道各自计数(边界 186,不串号)。 */
    private com.wangyu.tangdb.metrics.Metrics mysqlMetrics =
            com.wangyu.tangdb.metrics.Metrics.DISABLED;
    private com.wangyu.tangdb.metrics.Metrics consoleMetrics =
            com.wangyu.tangdb.metrics.Metrics.DISABLED;
    /** SPEC-P8 C 组:慢查询日志与诊断建议(未注入 = 关闭)。 */
    private com.wangyu.tangdb.slow.SlowQueryLog slowLog;
    /** SPEC-P8 D 组:备份 / 恢复编排(未注入 = 相关语句报未启用)。 */
    private com.wangyu.tangdb.backup.BackupService backups;

    public SqlEngine(KvStore kv, Catalog catalog, SessionManager sessions,
                     BackendRegistry backends, boolean heartbeatEnabled, String user) {
        this(kv, catalog, sessions, backends, heartbeatEnabled, user, 1000);
    }

    public SqlEngine(KvStore kv, Catalog catalog, SessionManager sessions,
                     BackendRegistry backends, boolean heartbeatEnabled, String user,
                     int indexBackfillBatch) {
        this.kv = kv;
        this.catalog = catalog;
        this.sessions = sessions;
        this.backends = backends;
        this.heartbeatEnabled = heartbeatEnabled;
        this.user = user == null ? "" : user;
        this.indexBackfillBatch = indexBackfillBatch > 0 ? indexBackfillBatch : 1000;
        this.queryBatchRows = 1024;
        this.sortSpillBytes = 64L * 1024 * 1024;
        this.sortTmpDir = "tmp/fe-sort";
        this.optimizer = com.wangyu.tangdb.planner.OptimizerConfig.defaults();
        this.stats = new com.wangyu.tangdb.stats.StatsService(kv, catalog, optimizer);
    }

    /** SPEC-P6.1:注入并行度 / 下推开关 / 扫描批大小(FR-711 / FR-718)。 */
    public SqlEngine withExecution(int parallelism, boolean pushdown, int scanBatchRows) {
        this.queryParallelism = parallelism > 0 ? parallelism : 1;
        this.pushdownEnabled = pushdown;
        if (scanBatchRows > 0) {
            this.scanBatchRows = scanBatchRows;
        }
        return this;
    }

    /** SPEC-P7:注入向量检索参数与推理算子(由常驻 FE 按配置装配)。 */
    public SqlEngine withP7(com.wangyu.tangdb.query.P7Options options,
                            com.wangyu.tangdb.ai.AiService service) {
        if (options != null) {
            this.p7 = options;
        }
        this.ai = service;
        return this;
    }

    /** SPEC-P8:注入两条通道的指标登记表(FR-822 / 边界 186)。 */
    public SqlEngine withMetrics(com.wangyu.tangdb.metrics.Metrics mysql,
                                 com.wangyu.tangdb.metrics.Metrics console) {
        this.mysqlMetrics = mysql == null ? com.wangyu.tangdb.metrics.Metrics.DISABLED : mysql;
        this.consoleMetrics = console == null
                ? com.wangyu.tangdb.metrics.Metrics.DISABLED : console;
        return this;
    }

    /** SPEC-P8:注入慢查询日志(FR-831),并把"生成执行计划"的钩子接回同一计划入口。 */
    public SqlEngine withSlowQueryLog(com.wangyu.tangdb.slow.SlowQueryLog log) {
        this.slowLog = log;
        if (log != null) {
            log.setExplainer(this::explainText);
        }
        return this;
    }

    /** SPEC-P8:注入备份 / 恢复编排(FR-841 ~ FR-850)。 */
    public SqlEngine withBackupService(com.wangyu.tangdb.backup.BackupService service) {
        this.backups = service;
        return this;
    }

    /** 备份 / 恢复编排(控制台状态区用)。 */
    public com.wangyu.tangdb.backup.BackupService backupService() {
        return backups;
    }

    /** 慢查询日志(控制台状态区 / 诊断用)。 */
    public com.wangyu.tangdb.slow.SlowQueryLog slowQueryLog() {
        return slowLog;
    }

    /**
     * 生成一条语句的 EXPLAIN 文本(慢查询诊断用)。走同一个计划构造入口(与执行不分叉),
     * 但用独立执行器,避免影响正在返回的语句的事务状态。
     */
    public String explainText(Session session, String sql) {
        Executor fresh = executor(null);
        if (session != null) {
            try {
                fresh.useDatabase(session.currentDatabase());
            } catch (RuntimeException ignored) {
                // 库已删除等:退回默认库
            }
        }
        Result result = fresh.execute(Parser.parse("EXPLAIN " + sql));
        if (result instanceof Result.Table table) {
            StringBuilder sb = new StringBuilder();
            for (Object[] row : table.rows()) {
                for (Object value : row) {
                    if (sb.length() > 0) {
                        sb.append((char) 10);
                    }
                    sb.append(value == null ? "NULL" : String.valueOf(value));
                }
            }
            return sb.toString();
        }
        if (result instanceof Result.Message message) {
            return message.text();
        }
        return "";
    }

    /** 协议通道的指标表(控制台 /metrics 读它)。 */
    public com.wangyu.tangdb.metrics.Metrics metrics() {
        return mysqlMetrics;
    }

    /** 控制台通道的指标表。 */
    public com.wangyu.tangdb.metrics.Metrics consoleMetrics() {
        return consoleMetrics;
    }

    private com.wangyu.tangdb.metrics.Metrics metricsFor(Session session) {
        return session != null && session.console() ? consoleMetrics : mysqlMetrics;
    }

    /** 当前执行层参数(启动日志与 EXPLAIN 用)。 */
    public int queryParallelism() {
        return queryParallelism;
    }

    public boolean pushdownEnabled() {
        return pushdownEnabled;
    }

    public int scanBatchRows() {
        return scanBatchRows;
    }

    /** SPEC-P5 §5.4:查询引擎参数(query_batch_rows / sort_spill_bytes / sort_tmp_dir)。 */
    public SqlEngine(KvStore kv, Catalog catalog, SessionManager sessions,
                     BackendRegistry backends, boolean heartbeatEnabled, String user,
                     int indexBackfillBatch, int queryBatchRows, long sortSpillBytes,
                     String sortTmpDir) {
        this(kv, catalog, sessions, backends, heartbeatEnabled, user, indexBackfillBatch,
                queryBatchRows, sortSpillBytes, sortTmpDir,
                com.wangyu.tangdb.planner.OptimizerConfig.defaults());
    }

    /** SPEC-P6 §5.3:在 P5 参数基础上再注入 11 项优化器配置。 */
    public SqlEngine(KvStore kv, Catalog catalog, SessionManager sessions,
                     BackendRegistry backends, boolean heartbeatEnabled, String user,
                     int indexBackfillBatch, int queryBatchRows, long sortSpillBytes,
                     String sortTmpDir, com.wangyu.tangdb.planner.OptimizerConfig optimizer) {
        this.kv = kv;
        this.catalog = catalog;
        this.sessions = sessions;
        this.backends = backends;
        this.heartbeatEnabled = heartbeatEnabled;
        this.user = user == null ? "" : user;
        this.indexBackfillBatch = indexBackfillBatch > 0 ? indexBackfillBatch : 1000;
        this.queryBatchRows = queryBatchRows > 0 ? queryBatchRows : 1024;
        this.sortSpillBytes = sortSpillBytes > 0 ? sortSpillBytes : 64L * 1024 * 1024;
        this.sortTmpDir = sortTmpDir == null || sortTmpDir.isBlank()
                ? "tmp/fe-sort" : sortTmpDir;
        this.optimizer = optimizer == null
                ? com.wangyu.tangdb.planner.OptimizerConfig.defaults() : optimizer;
        // 统计服务跨会话共享:行数变化记账(FR-623)必须在同一份账本上,否则多会话下 stale
        // 永远判不出来。它只持有内存缓存 + KvStore,与 Catalog 的生命周期一致。
        this.stats = new com.wangyu.tangdb.stats.StatsService(kv, catalog, this.optimizer);
    }

    /** 供测试 / 诊断:当前共享的统计服务。 */
    public com.wangyu.tangdb.stats.StatsService stats() {
        return stats;
    }

    /**
     * 执行一条 SQL(FR-447)。失败时抛异常,由调用方(协议层 / 测试)映射成错误。
     *
     * <p>文本里若有多条语句(顶层分号分隔),按书写顺序全部执行,返回**最后一条**的结果
     * ——需要逐条结果请用 {@link #executeBatch}。</p>
     */
    public Result execute(Session session, String sql) {
        List<Result> results = executeBatch(session, sql);
        return results.get(results.size() - 1);
    }

    /**
     * 执行一条 {@code COM_QUERY} 文本(FR-556 / FR-558 / K101):按顶层分号切成 N 条,
     * 逐条走原有单条路径,返回结果列表(空语句忽略)。
     *
     * <p>某条失败立即抛出 —— 已执行语句的结果一并丢弃,调用方只回该条的错误包,
     * 其后语句不再执行(FR-558)。切分属 SQL 词汇层,协议层不参与(K101)。</p>
     */
    public List<Result> executeBatch(Session session, String sql) {
        if (session != null) {
            if (session.killed()) {
                throw new SqlError(SqlError.ER_QUERY_INTERRUPTED, "70100",
                        "会话已被 KILL,请重新连接");
            }
            session.touch();
        }
        List<Result> results = new ArrayList<>();
        for (String segment : splitStatements(sql == null ? "" : sql)) {
            String statement = stripLeadingComments(stripSemicolon(segment.trim()));
            if (statement.isEmpty()) {
                continue; // 空语句(连续 / 尾随分号、只有注释)忽略,不报 1064(FR-556)
            }
            results.add(executeOne(session, statement));
        }
        if (results.isEmpty()) {
            return List.of(new Result.Message("OK"));
        }
        return results;
    }

    /** 执行一条已经切好、去过头注释的语句(原单条路径,兼容层 + Parser + Executor 不改)。 */
    private Result executeOne(Session session, String statement) {
        String upper = statement.toUpperCase(Locale.ROOT);
        long started = System.nanoTime();
        com.wangyu.tangdb.metrics.Metrics metrics = metricsFor(session);
        try {
            Result result = executeOneInner(session, statement, upper);
            if (result instanceof Result.StreamTable streaming) {
                // 流式结果集:真正的扫描发生在协议层逐批取数时,所以耗时在 RowStream 关闭时结算
                // (否则慢查询与延迟分位只会统计到"构造计划"的时间,SC-450 会漏记)。
                RowStream timed = new TimedRowStream(streaming.stream(), session, statement,
                        started, metrics);
                return new Result.StreamTable(streaming.columns(), timed);
            }
            long millis = (System.nanoTime() - started) / 1_000_000L;
            recordStatement(session, statement, upper, millis, result, 0L, metrics, null);
            return result;
        } catch (RuntimeException e) {
            long millis = (System.nanoTime() - started) / 1_000_000L;
            SqlError se = e instanceof SqlError error ? error : SqlError.from(e);
            if (se.code() == SqlError.ER_LOCK_DEADLOCK) {
                metrics.recordConflict();
            } else if (se.code() == SqlError.ER_DUP_ENTRY) {
                metrics.recordUniqueConflict();
            }
            recordStatement(session, statement, upper, millis, null, 0L, metrics, se);
            throw e;
        }
    }

    /** 统一记账:指标(FR-822)+ 慢查询(FR-831)。流式结果集在关闭时也走这里。 */
    private void recordStatement(Session session, String statement, String upper, long millis,
                                 Result result, long rows, com.wangyu.tangdb.metrics.Metrics metrics,
                                 SqlError error) {
        metrics.recordStatement(millis, error == null ? 0 : error.code());
        if (error == null && upper.startsWith("COMMIT")) {
            metrics.recordCommit(millis);
        }
        if (slowLog == null) {
            return;
        }
        if (error != null) {
            slowLog.record(session, statement, millis, 0L, error);
        } else if (rows > 0) {
            slowLog.record(session, statement, millis, rows, null);
        } else {
            slowLog.record(session, statement, millis, result, null);
        }
        if (millis >= slowLog.thresholdMs()) {
            metrics.recordSlowQuery();
        }
    }

    /** 给流式结果集加"关闭时结算耗时"的包装(FR-822 / FR-831)。 */
    private final class TimedRowStream implements RowStream {
        private final RowStream delegate;
        private final Session session;
        private final String statement;
        private final long startedNanos;
        private final com.wangyu.tangdb.metrics.Metrics metrics;
        private long rows;
        private boolean recorded;

        TimedRowStream(RowStream delegate, Session session, String statement, long startedNanos,
                       com.wangyu.tangdb.metrics.Metrics metrics) {
            this.delegate = delegate;
            this.session = session;
            this.statement = statement;
            this.startedNanos = startedNanos;
            this.metrics = metrics;
        }

        @Override
        public List<Object[]> nextBatch() {
            List<Object[]> batch = delegate.nextBatch();
            if (batch == null) {
                finish(null);
            } else {
                rows += batch.size();
            }
            return batch;
        }

        @Override
        public List<String> columns() {
            return delegate.columns();
        }

        @Override
        public void close() {
            try {
                delegate.close();
            } finally {
                finish(null);
            }
        }

        private synchronized void finish(SqlError error) {
            if (recorded) {
                return;
            }
            recorded = true;
            long millis = (System.nanoTime() - startedNanos) / 1_000_000L;
            recordStatement(session, statement, statement.toUpperCase(Locale.ROOT), millis, null,
                    rows, metrics, error);
        }
    }

    private Result executeOneInner(Session session, String statement, String upper) {
        Result compat = handleCompat(session, statement, upper);
        if (compat != null) {
            return compat;
        }

        Executor executor = executor(session);
        try {
            return executor.execute(Parser.parse(statement));
        } catch (ParseException e) {
            if (e.code() != 0) {
                // P6:语法合法但本期明确不支持的形态(1248 / 1235)在解析期就给准确码(FR-670)
                throw new SqlError(e.code(), "42000", e.getMessage());
            }
            String parseMessage = e.getMessage() == null ? "" : e.getMessage();
            if (parseMessage.contains("归 P6") || parseMessage.contains("归 P12")
                    || parseMessage.contains("相关子查询")) {
                throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000", parseMessage);
            }
            if (upper.startsWith("SHOW ")) {
                throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                        "不支持的 SHOW 语句:" + e.getMessage());
            }
            if (upper.startsWith("EXPLAIN ")) {
                throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                        "不支持解释该语句(EXPLAIN 只支持 SELECT / UPDATE / DELETE)");
            }
            throw e;
        }
    }

    /**
     * 按**顶层分号**切分多条语句(FR-556)。只做词法级扫描,不判断 SQL 语义:
     * 单引号 / 双引号 / 反引号内的分号不切,{@code --} 与 {@code #} 行注释、块注释里的分号
     * 也不切;成对引号(如 {@code ''})与反斜杠转义按词法跳过。
     *
     * <p>返回的片段保留原文(含注释),由调用方逐条走原执行路径;连续分号与尾随分号
     * 产生的空片段由调用方忽略。</p>
     */
    static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                i = copyQuoted(sql, i, c, buf);
                continue;
            }
            if (c == '`') {
                i = copyQuoted(sql, i, c, buf);
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int eol = sql.indexOf('\n', i);
                if (eol < 0) {
                    eol = n;
                }
                buf.append(sql, i, eol);
                i = eol;
                continue;
            }
            if (c == '#') {
                int eol = sql.indexOf('\n', i);
                if (eol < 0) {
                    eol = n;
                }
                buf.append(sql, i, eol);
                i = eol;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                buf.append(sql, i, stop);
                i = stop;
                continue;
            }
            if (c == ';') {
                statements.add(buf.toString());
                buf.setLength(0);
                i++;
                continue;
            }
            buf.append(c);
            i++;
        }
        statements.add(buf.toString());
        return statements;
    }

    /**
     * 复制一段引号 / 反引号包裹的内容,返回下一个未处理位置。
     * 成对引号(如 {@code ''})与反斜杠转义不结束当前片段。
     */
    private static int copyQuoted(String sql, int start, char quote, StringBuilder buf) {
        int n = sql.length();
        int i = start;
        buf.append(quote);
        i++;
        while (i < n) {
            char c = sql.charAt(i);
            buf.append(c);
            if (c == '\\' && i + 1 < n) {
                buf.append(sql.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < n && sql.charAt(i + 1) == quote) {
                    buf.append(quote);
                    i += 2;
                    continue;
                }
                i++;
                break;
            }
            i++;
        }
        return i;
    }

    /**
     * 新建一个裸露的执行器(SPEC-P8 FR-862:控制台会话用它登记到独立会话表,
     * 不占用 MySQL 连接额度)。参数在每次执行时由 {@link #executor(Session)} 补齐。
     */
    public Executor newExecutor() {
        return new Executor(kv, catalog);
    }

    /** 当前库(控制台状态区显示用)。 */
    public String currentDatabase(Session session) {
        return executor(session).currentDatabase();
    }

    /** 设置会话当前库(COM_INIT_DB / 握手 database),库不存在抛 1049(FR-436 / FR-446)。 */
    public void useDatabase(Session session, String database) {
        executor(session).useDatabase(database);
    }

    private Executor executor(Session session) {
        if (session == null) {
            return new Executor(kv, catalog)
                    .withQueryOptions(queryBatchRows, sortSpillBytes, sortTmpDir)
                    .withOptimizer(optimizer)
                    .withStats(stats)
                    .withExecution(queryParallelism, pushdownEnabled, scanBatchRows)
                    .withStreamingResults(true)
                    .withBackends(backends)
                    .withP7(p7, ai)
                    .attachServer(sessions, user, 0, indexBackfillBatch);
        }
        return session.executor()
                .withQueryOptions(queryBatchRows, sortSpillBytes, sortTmpDir)
                .withOptimizer(optimizer)
                .withStats(stats)
                .withExecution(queryParallelism, pushdownEnabled, scanBatchRows)
                .withStreamingResults(true)
                .withBackends(backends)
                .withP7(p7, ai)
                .attachServer(sessions, user, session.id(), indexBackfillBatch);
    }

    // ------------------------------------------------------------ 连接期兼容语句(§5.5)

    /** @return 命中的兼容语句结果;未命中返回 null(交给 Parser) */
    private Result handleCompat(Session session, String statement, String upper) {
        // SET ...(全部接受并给 OK;K65:autocommit 语义不改变)
        if (upper.startsWith("SET ")) {
            // SPEC-P7 FR-760 / SC-422:两个会话开关真正生效(其余 SET 仍按 K65 接受)
            String knob = upper.substring(4).replace(" ", "");
            if (knob.startsWith("VECTOR_EXACT=")) {
                boolean on = isOn(knob.substring("VECTOR_EXACT=".length()));
                executor(session).setVectorExact(on);
                return new Result.Message("OK, vector_exact=" + (on ? "ON" : "OFF"));
            }
            if (knob.startsWith("VECTOR_PATH=")) {
                String path = knob.substring("VECTOR_PATH=".length()).replace("'", "");
                executor(session).setVectorPath(path);
                return new Result.Message("OK, vector_path=" + path);
            }
            // SPEC-P7.1 K181:五段耗时开关(SET vector_profile = ON|OFF)
            if (knob.startsWith("VECTOR_PROFILE=")) {
                boolean on = isOn(knob.substring("VECTOR_PROFILE=".length()));
                executor(session).setVectorProfile(on);
                return new Result.Message("OK, vector_profile=" + (on ? "ON" : "OFF"));
            }
            return new Result.Message("OK");
        }
        // USE 也走 Parser(库语义),这里只处理纯兼容层。

        Result atVars = trySelectSystemVariables(session, statement, upper);
        if (atVars != null) {
            return atVars;
        }

        // SHOW SESSIONS / SHOW BACKENDS 是 tang_db 自有诊断语句,不进公共 SQL 语法。
        if (upper.startsWith("SHOW SESSIONS")) {
            return showSessions();
        }
        if (upper.startsWith("SHOW BACKENDS")) {
            return showBackends();
        }
        // SPEC-P8 FR-824:SHOW METRICS [LIKE 'pattern'] —— 与 /metrics 同源同值。
        if (upper.startsWith("SHOW METRICS")) {
            return showMetrics(session, statement, upper);
        }
        // SPEC-P8 FR-833:慢查询查看(诊断建议与证据在同一行)。
        if (upper.startsWith("SHOW SLOW QUER")) {
            return showSlowQueries(statement, upper);
        }
        // SPEC-P8 FR-846:备份 / 恢复任务
        if (upper.startsWith("SHOW BACKUP JOBS")) {
            return showBackupJobs();
        }
        // SPEC-P8 FR-841 / FR-844:备份与恢复(解析在 FE 侧,执行在 BE 侧)
        if (upper.startsWith("BACKUP ")) {
            com.wangyu.tangdb.backup.BackupService.ParsedBackup parsed =
                    com.wangyu.tangdb.backup.BackupService.parseBackup(statement);
            if (parsed == null) {
                throw new SqlError(SqlError.ER_SYNTAX_ERROR, "42000",
                        "BACKUP 语法: BACKUP TO '<目录>' [INCREMENTAL FROM '<上次备份>'] [FORCE]");
            }
            if (backups == null) {
                throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000", "备份功能未启用");
            }
            try {
                return new Result.Message(backups.backup(parsed.directory(),
                        parsed.incrementalFrom(), parsed.force()));
            } catch (RuntimeException e) {
                throw SqlError.from(e);
            }
        }
        if (upper.startsWith("RESTORE ")) {
            com.wangyu.tangdb.backup.BackupService.ParsedRestore parsed =
                    com.wangyu.tangdb.backup.BackupService.parseRestore(statement);
            if (parsed == null) {
                throw new SqlError(SqlError.ER_SYNTAX_ERROR, "42000",
                        "RESTORE 语法: RESTORE FROM '<备份目录>' [TO '<目标目录>'] [FORCE]");
            }
            if (backups == null) {
                throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000", "备份功能未启用");
            }
            try {
                return new Result.Message(backups.restore(parsed.source(), parsed.target(),
                        parsed.force()));
            } catch (RuntimeException e) {
                throw SqlError.from(e);
            }
        }

        // information_schema 虚拟表:走 Parser + MetaCommands(K92 / K94 / K98)。
        if (upper.contains("INFORMATION_SCHEMA")) {
            return informationSchema(session, statement, upper);
        }
        return null;
    }

    private static boolean isOn(String raw) {
        String v = raw.trim().replace("'", "");
        return "ON".equalsIgnoreCase(v) || "TRUE".equalsIgnoreCase(v) || "1".equals(v);
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

    // ------------------------------------------------------------ SHOW METRICS

    /**
     * {@code SHOW METRICS [LIKE 'pattern']}(FR-824):两列 metric/value,数据与
     * {@code GET /metrics} 完全同一份({@link com.wangyu.tangdb.metrics.Metrics#snapshot()})。
     */
    private Result showMetrics(Session session, String statement, String upper) {
        com.wangyu.tangdb.metrics.Metrics sink = metricsFor(session);
        String like = null;
        int idx = upper.indexOf("LIKE");
        if (idx >= 0) {
            like = unquote(statement.substring(idx + 4).trim());
        }
        List<Object[]> rows = new ArrayList<>();
        for (com.wangyu.tangdb.metrics.Metrics.MetricPoint point : sink.snapshot()) {
            if (like == null || sqlLike(point.name(), like)) {
                rows.add(new Object[]{point.name(), point.valueText()});
            }
        }
        return new Result.Table(List.of("metric", "value"), rows);
    }

    private static String unquote(String raw) {
        String s = raw.trim();
        if (s.startsWith("'") && s.endsWith("'") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** SQL LIKE(只认 % 与 _)转正则匹配;大小写不敏感。 */
    private static boolean sqlLike(String value, String pattern) {
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '%' -> regex.append(".*");
                case '_' -> regex.append('.');
                default -> regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        return value.matches("(?i)" + regex);
    }

    // ------------------------------------------------------------ SHOW SLOW QUERIES

    /** {@code SHOW SLOW QUERIES [LIMIT n]} / {@code SHOW SLOW QUERY <id>}(FR-833)。 */
    private Result showSlowQueries(String statement, String upper) {
        if (slowLog == null) {
            throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                    "慢查询记录未启用(slow_query_log = false)");
        }
        String rest = statement.substring(statement.toUpperCase(Locale.ROOT)
                .indexOf("SHOW SLOW QUERY") + "SHOW SLOW QUERY".length()).trim();
        // "QUERIES" 的后半截("IES")也落在这里,必须先判列表形态再判 id 形态。
        boolean list = rest.toUpperCase(Locale.ROOT).startsWith("IES");
        if (list) {
            rest = rest.substring("IES".length()).trim();
        }
        if (!list) {
            long id;
            try {
                id = Long.parseLong(rest.trim());
            } catch (NumberFormatException e) {
                throw new SqlError(SqlError.ER_SYNTAX_ERROR, "42000",
                        "SHOW SLOW QUERY 需要一个数字 id: " + rest);
            }
            com.wangyu.tangdb.slow.SlowQueryLog.Entry entry = slowLog.find(id);
            if (entry == null) {
                return new Result.Table(com.wangyu.tangdb.slow.SlowQueryLog.COLUMNS,
                        new ArrayList<>());
            }
            return new Result.Table(com.wangyu.tangdb.slow.SlowQueryLog.COLUMNS,
                    List.<Object[]>of(entryRow(entry)));
        }
        int limit = 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)LIMIT\\s+(\\d+)").matcher(statement);
        if (matcher.find()) {
            limit = Integer.parseInt(matcher.group(1));
        }
        List<Object[]> rows = new ArrayList<>();
        for (com.wangyu.tangdb.slow.SlowQueryLog.Entry entry : slowLog.recent(limit)) {
            rows.add(entryRow(entry));
        }
        return new Result.Table(com.wangyu.tangdb.slow.SlowQueryLog.COLUMNS, rows);
    }

    private static Object[] entryRow(com.wangyu.tangdb.slow.SlowQueryLog.Entry entry) {
        return new Object[]{entry.id(), entry.startTime(), entry.durationMs(),
                entry.rowsScanned(), entry.rowsReturned(), entry.client(), entry.database(),
                entry.tables(), entry.sqlText(), entry.fingerprint(), entry.planSummary(),
                entry.advice()};
    }

    // ------------------------------------------------------------ SHOW BACKUP JOBS

    /** {@code SHOW BACKUP JOBS}(FR-846):任务 id / 时间 / 状态 / 已复制 / 总字节。 */
    private Result showBackupJobs() {
        if (backups == null) {
            throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000", "备份功能未启用");
        }
        List<Object[]> rows = new ArrayList<>();
        for (com.wangyu.tangdb.backup.BackupService.Job job : backups.jobs()) {
            rows.add(new Object[]{job.jobId(), job.kind(), job.backupDir(), job.targetDir(),
                    job.status(), job.startedAt(), job.finishedAt(), job.copiedBytes(),
                    job.totalBytes(), job.incremental(), job.message()});
        }
        return new Result.Table(com.wangyu.tangdb.backup.BackupService.COLUMNS, rows);
    }

    // ------------------------------------------------------------ information_schema

    /**
     * information_schema 虚拟表查询(FR-537 ~ FR-540 / K98;SPEC-P6 FR-667)。
     *
     * <p>P6 起**没有语句文本门禁**:能落成旧 {@link Ast.Select} 的简单形态继续走
     * {@link MetaCommands}(P4 的输出列名/排序断言不变);JOIN / 子查询 / 聚合 / DISTINCT /
     * 集合运算等形态返回 {@code null},交给统一计划器(虚拟表在 QueryEngine 里就是普通来源)。</p>
     */
    private Result informationSchema(Session session, String statement, String upper) {
        Ast.Statement ast;
        try {
            ast = Parser.parse(statement);
        } catch (ParseException e) {
            if (e.code() != 0) {
                throw new SqlError(e.code(), "42000", e.getMessage());
            }
            // LIMIT 取值 / 语法错统一报 1064(FR-549 / 边界 31),不能被误映射成 1235。
            if (e.getMessage() != null && e.getMessage().contains("LIMIT")) {
                throw e;
            }
            throw e;
        }
        if (ast instanceof Ast.Select select && select.database() != null
                && select.database().equalsIgnoreCase("information_schema")) {
            return new MetaCommands(catalog, executor(session), sessions, user)
                    .informationSchema(select);
        }
        // 统一计划器(虚拟表作为普通 FROM 来源,SC-381 / SC-383)
        return null;
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
