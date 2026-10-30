package com.wangyu.tangdb.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * FE 的启动配置。
 *
 * <p>来源是仓库根目录下的 {@code config/fe.conf},格式为一行一项 {@code key = value},
 * {@code #} 开头的整行为注释。取值优先级固定为:</p>
 *
 * <pre>命令行参数 &gt; config/fe.conf &gt; 内置默认值</pre>
 *
 * <p>P3.5 起新增:{@code mode} / {@code mysql_port} / {@code session_max} /
 * {@code session_idle_timeout} / {@code shutdown_grace_seconds} / {@code user} /
 * {@code password}(§5.6)。</p>
 */
public final class FeConfig {

    /** 未显式指定 {@code --config} 时查找的配置文件路径。 */
    public static final String DEFAULT_PATH = "./config/fe.conf";

    /** FE 心跳服务默认监听端口(P2 规约 K24)。 */
    public static final int DEFAULT_HEARTBEAT_LISTEN_PORT = 9091;

    /** 默认的判死周期数:连续 3 个心跳周期未收到即判不可用。 */
    public static final int DEFAULT_BE_OFFLINE_CYCLES = 3;

    /** MySQL 协议端口默认值(SPEC-P3.5 §12 I10)。 */
    public static final int DEFAULT_MYSQL_PORT = 3406;

    public static final int DEFAULT_SESSION_MAX = 64;
    public static final int DEFAULT_SESSION_IDLE_TIMEOUT = 0;
    public static final int DEFAULT_SHUTDOWN_GRACE_SECONDS = 5;
    public static final String DEFAULT_USER = "root";
    public static final String DEFAULT_PASSWORD = "tangdb";
    /** 建索引回填批量(SPEC-P4 K84)。 */
    public static final int DEFAULT_INDEX_BACKFILL_BATCH = 1000;

    // ---- SPEC-P5 §5.4 ----
    public static final int DEFAULT_QUERY_BATCH_ROWS = 1024;
    public static final long DEFAULT_SORT_SPILL_BYTES = 64L * 1024 * 1024;
    public static final int DEFAULT_CSV_IMPORT_BATCH_ROWS = 5000;
    /** FE 自己的排序临时目录(放在 BE 数据目录下会穿透 FE/BE 边界,故放在 FE 侧)。 */
    public static final String DEFAULT_SORT_TMP_DIR = "tmp/fe-sort";

    // ---- SPEC-P6 §5.3(11 项优化器配置) ----
    public static final boolean DEFAULT_OPTIMIZER_ENABLED = true;
    public static final int DEFAULT_ANALYZE_FULL_SCAN_MAX_ROWS = 200_000;
    public static final int DEFAULT_ANALYZE_SAMPLE_STRIDE = 20;
    public static final int DEFAULT_ANALYZE_HISTOGRAM_BUCKETS = 64;
    public static final int DEFAULT_ANALYZE_EXACT_DISTINCT_MAX_ROWS = 100_000;
    public static final int DEFAULT_ANALYZE_HLL_PRECISION = 14;
    public static final int DEFAULT_CBO_JOIN_ENUM_MAX_TABLES = 4;
    public static final int DEFAULT_CBO_HASH_AGGREGATE_DISTINCT_MAX = 10_000;
    public static final int DEFAULT_CBO_TOPN_LIMIT_RATIO_PERCENT = 30;
    public static final int DEFAULT_SUBQUERY_CACHE_MAX_ENTRIES = 4096;
    public static final int DEFAULT_STATS_STALE_ROW_CHANGE_PERCENT = 20;

    // ---- SPEC-P6.1 §5.2(9 项中的 FE 6 项) ----
    /** 扫描与算子的并行度,默认 min(8, CPU)(FR-711 / K153)。 */
    public static final int DEFAULT_QUERY_PARALLELISM = Math.min(8, Runtime.getRuntime().availableProcessors());
    /** 列投影 + 谓词下推总开关(默认开,K153)。 */
    public static final boolean DEFAULT_PUSHDOWN_ENABLED = true;
    /** 扫描批行数(自适应上限 8192,FR-708)。 */
    public static final int DEFAULT_SCAN_BATCH_ROWS = 4096;
    /** 普通读写/事务期限(秒,FR-722)。 */
    public static final int DEFAULT_RPC_DEADLINE_SECONDS = 5;
    /** 批量导入 / 索引回填 / ANALYZE 期限(秒,FR-722)。 */
    public static final int DEFAULT_RPC_BULK_DEADLINE_SECONDS = 60;
    /** 范围扫描 / 分片扫描期限(秒,FR-722)。 */
    public static final int DEFAULT_RPC_SCAN_DEADLINE_SECONDS = 60;

    private final String beHost;
    private final int bePort;
    private final int heartbeatListenPort;
    private final int beOfflineCycles;
    private final boolean heartbeatEnabled;
    private final String mode;
    private final int mysqlPort;
    private final int sessionMax;
    private final int sessionIdleTimeout;
    private final int shutdownGraceSeconds;
    private final String user;
    private final String password;
    private final int indexBackfillBatch;
    private final int queryBatchRows;
    private final long sortSpillBytes;
    private final String sortTmpDir;
    private final int csvImportBatchRows;
    private final com.wangyu.tangdb.planner.OptimizerConfig optimizer;
    private final int queryParallelism;
    private final boolean pushdownEnabled;
    private final int scanBatchRows;
    private final int rpcDeadlineSeconds;
    private final int rpcBulkDeadlineSeconds;
    private final int rpcScanDeadlineSeconds;

    // ---- SPEC-P7 §5.4(向量检索门限 / 扫描并行阈值 / 推理端点) ----
    // 这两项在 parse() 末尾一次性装配;其余构造路径(测试 / 程序化)取默认值。
    private com.wangyu.tangdb.query.P7Options p7 = com.wangyu.tangdb.query.P7Options.defaults();
    private com.wangyu.tangdb.ai.AiService.Config ai = com.wangyu.tangdb.ai.AiService.Config.disabled();

    // ---- SPEC-P8 §5.3(协议 / 监控 / 慢查询 / 备份 / 控制台) ----
    private P8 p8 = P8.defaults();

    /** SPEC-P8 §5.3 的十项配置。 */
    public record P8(int mysqlMaxConnections, int mysqlWaitTimeout, boolean mysqlSsl,
                     boolean consoleEnabled, int consolePort,
                     boolean metricsEnabled,
                     boolean slowQueryLog, int slowQueryThresholdMs, int slowQueryMaxRows,
                     String backupDir) {

        public static P8 defaults() {
            return new P8(128, 0, false, true, 8080, true, true, 200, 10_000, "");
        }
    }

    /** 分配 SPEC-P8 配置(只由 {@link #parse(String)} 与 {@link #withP8(P8)} 调用)。 */
    void applyP8(P8 settings) {
        if (settings != null) {
            this.p8 = settings;
        }
    }

    /** SPEC-P8 §5.3:协议 / 监控 / 慢查询 / 备份 / 控制台配置。 */
    public P8 p8() {
        return p8;
    }

    /** 程序化覆盖 P8 配置(测试与开关对拍用)。 */
    public FeConfig withP8(P8 settings) {
        FeConfig copy = new FeConfig(beHost, bePort, heartbeatListenPort, beOfflineCycles,
                heartbeatEnabled, mode, mysqlPort, sessionMax, sessionIdleTimeout,
                shutdownGraceSeconds, user, password, indexBackfillBatch, queryBatchRows,
                sortSpillBytes, sortTmpDir, csvImportBatchRows, optimizer,
                queryParallelism, pushdownEnabled, scanBatchRows, rpcDeadlineSeconds,
                rpcBulkDeadlineSeconds, rpcScanDeadlineSeconds);
        copy.applyP7(p7, ai);
        copy.applyP8(settings == null ? p8 : settings);
        return copy;
    }

    /** 分配 SPEC-P7 的向量/推理参数(只由 {@link #parse(String)} 调用)。 */
    void applyP7(com.wangyu.tangdb.query.P7Options options,
                 com.wangyu.tangdb.ai.AiService.Config aiConfig) {
        if (options != null) {
            this.p7 = options;
        }
        if (aiConfig != null) {
            this.ai = aiConfig;
        }
    }

    /** SPEC-P7 §5.4:向量检索门限 + parallel_min_rows。 */
    public com.wangyu.tangdb.query.P7Options p7() {
        return p7;
    }

    /** SPEC-P7 E 组:推理端点配置(缺失时 configured()=false)。 */
    public com.wangyu.tangdb.ai.AiService.Config ai() {
        return ai;
    }

    private FeConfig(String beHost, int bePort, int heartbeatListenPort, int beOfflineCycles,
                     boolean heartbeatEnabled, String mode, int mysqlPort, int sessionMax,
                     int sessionIdleTimeout, int shutdownGraceSeconds, String user, String password,
                     int indexBackfillBatch, int queryBatchRows, long sortSpillBytes,
                     String sortTmpDir, int csvImportBatchRows,
                     com.wangyu.tangdb.planner.OptimizerConfig optimizer) {
        this(beHost, bePort, heartbeatListenPort, beOfflineCycles, heartbeatEnabled, mode,
                mysqlPort, sessionMax, sessionIdleTimeout, shutdownGraceSeconds, user, password,
                indexBackfillBatch, queryBatchRows, sortSpillBytes, sortTmpDir,
                csvImportBatchRows, optimizer, DEFAULT_QUERY_PARALLELISM,
                DEFAULT_PUSHDOWN_ENABLED, DEFAULT_SCAN_BATCH_ROWS, DEFAULT_RPC_DEADLINE_SECONDS,
                DEFAULT_RPC_BULK_DEADLINE_SECONDS, DEFAULT_RPC_SCAN_DEADLINE_SECONDS);
    }

    private FeConfig(String beHost, int bePort, int heartbeatListenPort, int beOfflineCycles,
                     boolean heartbeatEnabled, String mode, int mysqlPort, int sessionMax,
                     int sessionIdleTimeout, int shutdownGraceSeconds, String user, String password,
                     int indexBackfillBatch, int queryBatchRows, long sortSpillBytes,
                     String sortTmpDir, int csvImportBatchRows,
                     com.wangyu.tangdb.planner.OptimizerConfig optimizer,
                     int queryParallelism, boolean pushdownEnabled, int scanBatchRows,
                     int rpcDeadlineSeconds, int rpcBulkDeadlineSeconds, int rpcScanDeadlineSeconds) {
        this.beHost = beHost;
        this.bePort = bePort;
        this.heartbeatListenPort = heartbeatListenPort;
        this.beOfflineCycles = beOfflineCycles;
        this.heartbeatEnabled = heartbeatEnabled;
        this.mode = mode;
        this.mysqlPort = mysqlPort;
        this.sessionMax = sessionMax;
        this.sessionIdleTimeout = sessionIdleTimeout;
        this.shutdownGraceSeconds = shutdownGraceSeconds;
        this.user = user;
        this.password = password;
        this.indexBackfillBatch = indexBackfillBatch;
        this.queryBatchRows = queryBatchRows;
        this.sortSpillBytes = sortSpillBytes;
        this.sortTmpDir = sortTmpDir;
        this.csvImportBatchRows = csvImportBatchRows;
        this.optimizer = optimizer == null
                ? com.wangyu.tangdb.planner.OptimizerConfig.defaults() : optimizer;
        this.queryParallelism = queryParallelism;
        this.pushdownEnabled = pushdownEnabled;
        this.scanBatchRows = scanBatchRows;
        this.rpcDeadlineSeconds = rpcDeadlineSeconds;
        this.rpcBulkDeadlineSeconds = rpcBulkDeadlineSeconds;
        this.rpcScanDeadlineSeconds = rpcScanDeadlineSeconds;
    }

    /** 内置默认配置。 */
    public static FeConfig defaults() {
        return new FeConfig(TangDbClient.DEFAULT_HOST, TangDbClient.DEFAULT_PORT,
                DEFAULT_HEARTBEAT_LISTEN_PORT, DEFAULT_BE_OFFLINE_CYCLES, true,
                "fed", DEFAULT_MYSQL_PORT, DEFAULT_SESSION_MAX, DEFAULT_SESSION_IDLE_TIMEOUT,
                DEFAULT_SHUTDOWN_GRACE_SECONDS, DEFAULT_USER, DEFAULT_PASSWORD,
                DEFAULT_INDEX_BACKFILL_BATCH, DEFAULT_QUERY_BATCH_ROWS, DEFAULT_SORT_SPILL_BYTES,
                DEFAULT_SORT_TMP_DIR, DEFAULT_CSV_IMPORT_BATCH_ROWS,
                com.wangyu.tangdb.planner.OptimizerConfig.defaults());
    }

    /** 直接用给定取值构造配置(供程序化使用与测试)。 */
    public static FeConfig of(String beHost, int bePort) {
        return of(beHost, bePort, DEFAULT_HEARTBEAT_LISTEN_PORT, DEFAULT_BE_OFFLINE_CYCLES);
    }

    /** 直接用给定取值构造配置(含 P2 新增项)。 */
    public static FeConfig of(String beHost, int bePort, int heartbeatListenPort, int beOfflineCycles) {
        return of(beHost, bePort, heartbeatListenPort, beOfflineCycles, true);
    }

    /** 直接用给定取值构造配置(含 P3 的 heartbeat_enabled)。 */
    public static FeConfig of(String beHost, int bePort, int heartbeatListenPort, int beOfflineCycles,
                              boolean heartbeatEnabled) {
        FeConfig d = defaults();
        return new FeConfig(requireHost(beHost), validatePort(bePort, "be_port"),
                validatePort(heartbeatListenPort, "heartbeat_listen_port"),
                Math.max(1, beOfflineCycles), heartbeatEnabled,
                d.mode, d.mysqlPort, d.sessionMax, d.sessionIdleTimeout, d.shutdownGraceSeconds,
                d.user, d.password, d.indexBackfillBatch, d.queryBatchRows, d.sortSpillBytes,
                d.sortTmpDir, d.csvImportBatchRows, d.optimizer);
    }

    public String beHost() {
        return beHost;
    }

    public int bePort() {
        return bePort;
    }

    /** FE 心跳服务(gRPC)监听端口。 */
    public int heartbeatListenPort() {
        return heartbeatListenPort;
    }

    /** 超过多少个心跳周期未收到心跳即判 BE 不可用。 */
    public int beOfflineCycles() {
        return beOfflineCycles;
    }

    /** 是否启用心跳监听(SPEC-P3 FR-366 / K57)。 */
    public boolean heartbeatEnabled() {
        return heartbeatEnabled;
    }

    /** 运行模式:fed(常驻服务)/ cli(瘦客户端)。 */
    public String mode() {
        return mode;
    }

    /** MySQL 协议监听端口(§12 I10)。 */
    public int mysqlPort() {
        return mysqlPort;
    }

    /** 会话上限(FR-411)。 */
    public int sessionMax() {
        return sessionMax;
    }

    /** 空闲超时(秒,0 = 不超时,FR-412)。 */
    public int sessionIdleTimeout() {
        return sessionIdleTimeout;
    }

    /** 优雅退出等待上限(秒,K73)。 */
    public int shutdownGraceSeconds() {
        return shutdownGraceSeconds;
    }

    /** 认证用户名(§5.6)。 */
    public String user() {
        return user;
    }

    /** 认证口令(明文只在配置文件,K63)。 */
    public String password() {
        return password;
    }

    /** 建索引回填批量(SPEC-P4 K84 / FR-467)。 */
    public int indexBackfillBatch() {
        return indexBackfillBatch;
    }

    /** 向量化批行数(SPEC-P5 §5.4 / FR-571)。 */
    public int queryBatchRows() {
        return queryBatchRows;
    }

    /** 排序溢写阈值(SPEC-P5 §5.4 / FR-580)。 */
    public long sortSpillBytes() {
        return sortSpillBytes;
    }

    /** FE 排序临时目录(K114,K114 的 BE 路径会穿透边界)。 */
    public String sortTmpDir() {
        return sortTmpDir;
    }

    /** CSV 导入批大小(SPEC-P5 §5.4 / FR-607)。 */
    public int csvImportBatchRows() {
        return csvImportBatchRows;
    }

    /** 优化器配置(SPEC-P6 §5.3 的 11 项)。 */
    public com.wangyu.tangdb.planner.OptimizerConfig optimizer() {
        return optimizer;
    }

    /** 扫描与算子的并行度(SPEC-P6.1 FR-711)。 */
    public int queryParallelism() {
        return queryParallelism;
    }

    /** 列投影 + 谓词下推总开关(SPEC-P6.1 FR-710 / FR-718)。 */
    public boolean pushdownEnabled() {
        return pushdownEnabled;
    }

    /** 扫描批行数(SPEC-P6.1 FR-708)。 */
    public int scanBatchRows() {
        return scanBatchRows;
    }

    /** 普通读写/事务期限(秒,FR-722)。 */
    public int rpcDeadlineSeconds() {
        return rpcDeadlineSeconds;
    }

    /** 批量导入 / 索引回填 / ANALYZE 期限(秒,FR-722)。 */
    public int rpcBulkDeadlineSeconds() {
        return rpcBulkDeadlineSeconds;
    }

    /** 范围扫描 / 分片扫描期限(秒,FR-722)。 */
    public int rpcScanDeadlineSeconds() {
        return rpcScanDeadlineSeconds;
    }

    /** 程序化覆盖执行层三项(测试与开关对拍用)。 */
    public FeConfig withExecution(int parallelism, boolean pushdown, int batchRows) {
        return new FeConfig(beHost, bePort, heartbeatListenPort, beOfflineCycles,
                heartbeatEnabled, mode, mysqlPort, sessionMax, sessionIdleTimeout,
                shutdownGraceSeconds, user, password, indexBackfillBatch, queryBatchRows,
                sortSpillBytes, sortTmpDir, csvImportBatchRows, optimizer,
                Math.max(1, parallelism), pushdown, Math.max(1, batchRows),
                rpcDeadlineSeconds, rpcBulkDeadlineSeconds, rpcScanDeadlineSeconds);
    }

    /**
     * 加载配置文件。
     *
     * <p>路径等于 {@link #DEFAULT_PATH} 且文件不存在时退回内置默认值(便于在任意目录直接跑);
     * 显式指定的其它路径不存在则报错 —— 明确写下的东西不该被静默忽略。</p>
     *
     * @throws IllegalArgumentException 文件不存在、读不了、或内容非法
     */
    public static FeConfig load(String path) {
        if (path == null || path.isBlank()) {
            return defaults();
        }

        Path file = Path.of(path);
        if (!Files.exists(file)) {
            if (DEFAULT_PATH.equals(path)) {
                return defaults();
            }
            throw new IllegalArgumentException("配置文件不存在: " + path);
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取配置文件失败: " + path + "（" + e.getMessage() + "）");
        }

        FeConfig d = defaults();
        String host = d.beHost;
        int port = d.bePort;
        int heartbeatPort = d.heartbeatListenPort;
        int offlineCycles = d.beOfflineCycles;
        boolean heartbeatEnabled = d.heartbeatEnabled;
        String mode = d.mode;
        int mysqlPort = d.mysqlPort;
        int sessionMax = d.sessionMax;
        int sessionIdleTimeout = d.sessionIdleTimeout;
        int shutdownGraceSeconds = d.shutdownGraceSeconds;
        String user = d.user;
        String password = d.password;
        int indexBackfillBatch = d.indexBackfillBatch;
        int queryBatchRows = d.queryBatchRows;
        long sortSpillBytes = d.sortSpillBytes;
        String sortTmpDir = d.sortTmpDir;
        int csvImportBatchRows = d.csvImportBatchRows;
        boolean optimizerEnabled = d.optimizer.enabled();
        int analyzeFullScanMaxRows = d.optimizer.analyzeFullScanMaxRows();
        int analyzeSampleStride = d.optimizer.analyzeSampleStride();
        int analyzeHistogramBuckets = d.optimizer.analyzeHistogramBuckets();
        int analyzeExactDistinctMaxRows = d.optimizer.analyzeExactDistinctMaxRows();
        int analyzeHllPrecision = d.optimizer.analyzeHllPrecision();
        int cboJoinEnumMaxTables = d.optimizer.joinEnumMaxTables();
        int cboHashAggregateDistinctMax = d.optimizer.hashAggregateDistinctMax();
        int cboTopnLimitRatioPercent = d.optimizer.topnLimitRatioPercent();
        int subqueryCacheMaxEntries = d.optimizer.subqueryCacheMaxEntries();
        int statsStaleRowChangePercent = d.optimizer.statsStaleRowChangePercent();
        int queryParallelism = d.queryParallelism;
        boolean pushdownEnabled = d.pushdownEnabled;
        int scanBatchRows = d.scanBatchRows;
        int rpcDeadlineSeconds = d.rpcDeadlineSeconds;
        int rpcBulkDeadlineSeconds = d.rpcBulkDeadlineSeconds;
        int rpcScanDeadlineSeconds = d.rpcScanDeadlineSeconds;
        int vectorMetric = d.p7.vectorMetric();
        int vectorM = d.p7.vectorM();
        int vectorEfConstruction = d.p7.vectorEfConstruction();
        int vectorEfSearch = d.p7.vectorEfSearch();
        int vectorEfMax = d.p7.vectorEfMax();
        int vectorBruteforceRows = d.p7.vectorBruteforceRows();
        int exactFallbackRows = d.p7.exactFallbackRows();
        double allowListMaxRatio = d.p7.allowListMaxRatio();
        int parallelMinRows = d.p7.parallelMinRows();
        String aiEndpoint = d.ai.endpoint();
        String aiModel = d.ai.model();
        int aiDim = d.ai.dim();
        int aiBatchRows = d.ai.batchRows();
        int aiTimeoutMs = d.ai.timeoutMs();
        int aiCacheEntries = d.ai.cacheEntries();
        int aiMaxRetries = d.ai.maxRetries();
        int aiBreakerBatches = d.ai.breakerBatches();
        int mysqlMaxConnections = d.p8.mysqlMaxConnections();
        int mysqlWaitTimeout = d.p8.mysqlWaitTimeout();
        boolean consoleEnabled = d.p8.consoleEnabled();
        int consolePort = d.p8.consolePort();
        boolean metricsEnabled = d.p8.metricsEnabled();
        boolean slowQueryLog = d.p8.slowQueryLog();
        int slowQueryThresholdMs = d.p8.slowQueryThresholdMs();
        int slowQueryMaxRows = d.p8.slowQueryMaxRows();
        String backupDir = d.p8.backupDir();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i == 0 && !line.isEmpty() && line.charAt(0) == '\uFEFF') {
                line = line.substring(1); // 容忍带 BOM 的文件
            }
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            int eq = line.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException(
                        path + " 第 " + (i + 1) + " 行格式错误（应为 key = value）: " + line);
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (key.isEmpty()) {
                throw new IllegalArgumentException(path + " 第 " + (i + 1) + " 行缺少配置项名");
            }
            if (value.isEmpty()) {
                throw new IllegalArgumentException(path + " 第 " + (i + 1) + " 行 " + key + " 的取值为空");
            }

            switch (key) {
                case "be_host" -> host = requireNonBlank(value, path, i + 1);
                case "be_port" -> port = parseInt(key, value, path, i + 1, 1, 65535);
                case "heartbeat_listen_port" ->
                        heartbeatPort = parseInt(key, value, path, i + 1, 1, 65535);
                case "be_offline_cycles" -> offlineCycles = parseInt(key, value, path, i + 1, 1, 1000);
                case "heartbeat_enabled" -> heartbeatEnabled = parseBoolean(key, value, path, i + 1);
                case "mode" -> mode = parseMode(value, path, i + 1);
                case "mysql_port" -> mysqlPort = parseInt(key, value, path, i + 1, 1, 65535);
                case "session_max" -> sessionMax = parseInt(key, value, path, i + 1, 1, 1_000_000);
                case "session_idle_timeout" -> sessionIdleTimeout = parseInt(key, value, path, i + 1, 0, 1_000_000);
                case "shutdown_grace_seconds" -> shutdownGraceSeconds = parseInt(key, value, path, i + 1, 0, 3600);
                case "user" -> user = requireNonBlank(value, path, i + 1);
                case "password" -> password = value;
                case "index_backfill_batch" ->
                        indexBackfillBatch = parseInt(key, value, path, i + 1, 1, 1_000_000);
                case "query_batch_rows" ->
                        queryBatchRows = parseInt(key, value, path, i + 1, 1, 1_000_000);
                case "sort_spill_bytes" -> sortSpillBytes = parseSize(key, value, path, i + 1);
                case "sort_tmp_dir" -> sortTmpDir = requireNonBlank(value, path, i + 1);
                case "csv_import_batch_rows" ->
                        csvImportBatchRows = parseInt(key, value, path, i + 1, 1, 10_000_000);
                // ---- SPEC-P6 §5.3 ----
                case "optimizer_enabled" -> optimizerEnabled = parseBoolean(key, value, path, i + 1);
                case "analyze_full_scan_max_rows" ->
                        analyzeFullScanMaxRows = parseInt(key, value, path, i + 1, 1, Integer.MAX_VALUE);
                case "analyze_sample_stride" ->
                        analyzeSampleStride = parseInt(key, value, path, i + 1, 1, 1_000_000);
                case "analyze_histogram_buckets" ->
                        analyzeHistogramBuckets = parseInt(key, value, path, i + 1, 1, 10_000);
                case "analyze_exact_distinct_max_rows" ->
                        analyzeExactDistinctMaxRows = parseInt(key, value, path, i + 1, 1, Integer.MAX_VALUE);
                case "analyze_hll_precision" ->
                        analyzeHllPrecision = parseInt(key, value, path, i + 1, 4, 18);
                case "cbo_join_enum_max_tables" ->
                        cboJoinEnumMaxTables = parseInt(key, value, path, i + 1, 1, 64);
                case "cbo_hash_aggregate_distinct_max" ->
                        cboHashAggregateDistinctMax = parseInt(key, value, path, i + 1, 1, Integer.MAX_VALUE);
                case "cbo_topn_limit_ratio_percent" ->
                        cboTopnLimitRatioPercent = parseInt(key, value, path, i + 1, 1, 100);
                case "subquery_cache_max_entries" ->
                        subqueryCacheMaxEntries = parseInt(key, value, path, i + 1, 1, 10_000_000);
                case "stats_stale_row_change_percent" ->
                        statsStaleRowChangePercent = parseInt(key, value, path, i + 1, 1, 1000);
                // ---- SPEC-P6.1 §5.2 ----
                case "query_parallelism" ->
                        queryParallelism = parseParallelism(value, path, i + 1);
                case "pushdown_enabled" -> pushdownEnabled = parseBoolean(key, value, path, i + 1);
                case "scan_batch_rows" ->
                        scanBatchRows = parseInt(key, value, path, i + 1, 1, 8192);
                case "rpc_deadline_seconds" ->
                        rpcDeadlineSeconds = parseInt(key, value, path, i + 1, 1, 600);
                case "rpc_bulk_deadline_seconds" ->
                        rpcBulkDeadlineSeconds = parseInt(key, value, path, i + 1, 1, 600);
                case "rpc_scan_deadline_seconds" ->
                        rpcScanDeadlineSeconds = parseInt(key, value, path, i + 1, 1, 600);
                case "vector_metric" -> {
                    vectorMetric = com.wangyu.tangdb.storage.VectorValues.metricCode(value);
                    if (vectorMetric < 0) {
                        throw new IllegalArgumentException(path + " 第 " + (i + 1)
                                + " 行 vector_metric 无效: " + value
                                + "(应为 l2 / cosine / inner_product)");
                    }
                }
                case "vector_m" -> vectorM = parseInt(key, value, path, i + 1, 4, 64);
                case "vector_ef_construction" ->
                        vectorEfConstruction = parseInt(key, value, path, i + 1, 16, 1024);
                case "vector_ef_search" -> vectorEfSearch = parseInt(key, value, path, i + 1, 1, 4096);
                case "vector_ef_max" -> vectorEfMax = parseInt(key, value, path, i + 1, 1, 8192);
                case "vector_bruteforce_rows" ->
                        vectorBruteforceRows = parseInt(key, value, path, i + 1, 1, 100_000_000);
                case "exact_fallback_rows" ->
                        exactFallbackRows = parseInt(key, value, path, i + 1, 1, 100_000_000);
                case "allow_list_max_ratio" -> allowListMaxRatio = parseRatio(key, value, path, i + 1);
                case "parallel_min_rows" ->
                        parallelMinRows = parseInt(key, value, path, i + 1, 0, 100_000_000);
                case "ai_endpoint" -> aiEndpoint = value;
                case "ai_model" -> aiModel = value;
                case "ai_dim" -> aiDim = parseInt(key, value, path, i + 1, 0, 4096);
                case "ai_batch_rows" -> aiBatchRows = parseInt(key, value, path, i + 1, 1, 65536);
                case "ai_timeout_ms" -> aiTimeoutMs = parseInt(key, value, path, i + 1, 1, 600_000);
                case "ai_cache_entries" ->
                        aiCacheEntries = parseInt(key, value, path, i + 1, 0, 10_000_000);
                case "ai_max_retries" -> aiMaxRetries = parseInt(key, value, path, i + 1, 0, 10);
                case "ai_breaker_batches" ->
                        aiBreakerBatches = parseInt(key, value, path, i + 1, 1, 1000);
                // ---- SPEC-P8 §5.3 ----
                case "mysql_max_connections" ->
                        mysqlMaxConnections = parseInt(key, value, path, i + 1, 1, 1_000_000);
                case "mysql_wait_timeout" ->
                        mysqlWaitTimeout = parseInt(key, value, path, i + 1, 0, 1_000_000);
                case "mysql_ssl" -> {
                    boolean ssl = parseBoolean(key, value, path, i + 1);
                    if (ssl) {
                        // 边界 169 / K197:不静默降级,启动即失败并说明未实现。
                        throw new IllegalArgumentException(path + " 第 " + (i + 1)
                                + " 行 mysql_ssl = true:本期未实现 TLS(边界 169 / K197),"
                                + "请改为 false 或删除该项");
                    }
                }
                case "console_enabled" -> consoleEnabled = parseBoolean(key, value, path, i + 1);
                case "console_port" -> consolePort = parseInt(key, value, path, i + 1, 1, 65535);
                case "metrics_enabled" -> metricsEnabled = parseBoolean(key, value, path, i + 1);
                case "slow_query_log" -> slowQueryLog = parseBoolean(key, value, path, i + 1);
                case "slow_query_threshold_ms" ->
                        slowQueryThresholdMs = parseInt(key, value, path, i + 1, 0, 3_600_000);
                case "slow_query_max_rows" ->
                        slowQueryMaxRows = parseInt(key, value, path, i + 1, 1, 10_000_000);
                case "backup_dir" -> backupDir = value;
                default -> throw new IllegalArgumentException(
                        path + " 第 " + (i + 1) + " 行未知配置项: " + key);
            }
        }

        FeConfig config = new FeConfig(host, port, heartbeatPort, offlineCycles, heartbeatEnabled,
                mode, mysqlPort, sessionMax, sessionIdleTimeout, shutdownGraceSeconds, user, password,
                indexBackfillBatch, queryBatchRows, sortSpillBytes, sortTmpDir, csvImportBatchRows,
                new com.wangyu.tangdb.planner.OptimizerConfig(optimizerEnabled, cboJoinEnumMaxTables,
                        cboHashAggregateDistinctMax, cboTopnLimitRatioPercent, subqueryCacheMaxEntries,
                        statsStaleRowChangePercent, analyzeFullScanMaxRows, analyzeSampleStride,
                        analyzeHistogramBuckets, analyzeExactDistinctMaxRows, analyzeHllPrecision),
                queryParallelism, pushdownEnabled, scanBatchRows, rpcDeadlineSeconds,
                rpcBulkDeadlineSeconds, rpcScanDeadlineSeconds);
        config.applyP7(new com.wangyu.tangdb.query.P7Options(vectorMetric, vectorM,
                        vectorEfConstruction, vectorEfSearch, vectorEfMax, vectorBruteforceRows,
                        exactFallbackRows, allowListMaxRatio, parallelMinRows,
                        com.wangyu.tangdb.query.P7Options.PATH_AUTO, false, false),
                new com.wangyu.tangdb.ai.AiService.Config(aiEndpoint, aiModel, aiDim, aiTimeoutMs,
                        aiBatchRows, aiCacheEntries, aiMaxRetries, aiBreakerBatches));
        config.applyP8(new P8(mysqlMaxConnections, mysqlWaitTimeout, false,
                consoleEnabled, consolePort, metricsEnabled, slowQueryLog, slowQueryThresholdMs,
                slowQueryMaxRows, backupDir));
        return config;
    }

    /** 解析 0~1 的比例(allow_list_max_ratio)。 */
    private static double parseRatio(String key, String raw, String path, int lineNo) {
        try {
            double value = Double.parseDouble(raw.trim());
            if (value > 0 && value <= 1) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // fallthrough
        }
        throw new IllegalArgumentException(path + " 第 " + lineNo + " 行 " + key
                + " 无效: " + raw + "(应为 0~1 之间的小数)");
    }

    /**
     * 解析并行度:必须 ≥ 1;超过 CPU×4 视为配置非法并给出建议上限(SPEC-P6.1 §8 #115/#116)。
     */
    private static int parseParallelism(String raw, String path, int lineNo) {
        int max = Math.max(1, Runtime.getRuntime().availableProcessors() * 4);
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(path + " 第 " + lineNo
                    + " 行 query_parallelism 无效: " + raw + "（应为 1-" + max + "）");
        }
        if (value < 1 || value > max) {
            throw new IllegalArgumentException(path + " 第 " + lineNo
                    + " 行 query_parallelism 无效: " + raw + "（应为 1-" + max
                    + "，建议不超过 CPU 核数×4=" + max + "）");
        }
        return value;
    }

    private static String parseMode(String raw, String path, int lineNo) {
        String v = raw.trim().toLowerCase();
        if ("fed".equals(v) || "cli".equals(v)) {
            return v;
        }
        throw new IllegalArgumentException(path + " 第 " + lineNo + " 行 mode 无效: " + raw
                + "(应为 fed / cli)");
    }

    /** 解析 64MB / 1KB / 1024 这类容量。 */
    private static long parseSize(String key, String raw, String path, int lineNo) {
        String v = raw.trim().toUpperCase(java.util.Locale.ROOT);
        long unit = 1;
        if (v.endsWith("MB")) {
            unit = 1024L * 1024;
            v = v.substring(0, v.length() - 2);
        } else if (v.endsWith("KB")) {
            unit = 1024;
            v = v.substring(0, v.length() - 2);
        } else if (v.endsWith("GB")) {
            unit = 1024L * 1024 * 1024;
            v = v.substring(0, v.length() - 2);
        } else if (v.endsWith("B")) {
            v = v.substring(0, v.length() - 1);
        }
        try {
            long n = Long.parseLong(v.trim());
            if (n <= 0) {
                throw new NumberFormatException("必须大于 0");
            }
            return n * unit;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(path + " 第 " + lineNo + " 行 " + key
                    + " 无效: " + raw + "(示例: 64MB / 1KB / 1024)");
        }
    }

    private static boolean parseBoolean(String key, String raw, String path, int lineNo) {
        String v = raw.trim();
        if ("true".equalsIgnoreCase(v)) {
            return true;
        }
        if ("false".equalsIgnoreCase(v)) {
            return false;
        }
        throw new IllegalArgumentException(
                path + " 第 " + lineNo + " 行 " + key + " 无效: " + raw + "(应为 true / false)");
    }

    private static String requireHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("be_host 不能为空");
        }
        return host.trim();
    }

    private static int validatePort(int port, String name) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(name + " 无效: " + port + "（应为 1-65535）");
        }
        return port;
    }

    private static String requireNonBlank(String value, String path, int lineNo) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(path + " 第 " + lineNo + " 行 be_host 不能为空");
        }
        return value.trim();
    }

    private static int parseInt(String key, String raw, String path, int lineNo, int min, int max) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < min || value > max) {
                throw new NumberFormatException("越界");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    path + " 第 " + lineNo + " 行 " + key + " 无效: " + raw
                            + "（应为 " + min + "-" + max + "）");
        }
    }
}
