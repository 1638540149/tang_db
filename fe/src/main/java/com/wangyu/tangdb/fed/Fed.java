package com.wangyu.tangdb.fed;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.catalog.CatalogException;
import com.wangyu.tangdb.client.FeConfig;
import com.wangyu.tangdb.client.TangDbClient;
import com.wangyu.tangdb.execute.SqlEngine;
import com.wangyu.tangdb.protocol.mysql.MysqlServer;
import com.wangyu.tangdb.protocol.mysql.NativePassword;
import com.wangyu.tangdb.server.BackendRegistry;
import com.wangyu.tangdb.server.FeGrpcServer;
import com.wangyu.tangdb.server.FeStartupException;
import com.wangyu.tangdb.session.Session;
import com.wangyu.tangdb.session.SessionManager;
import com.wangyu.tangdb.storage.StoreException;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 常驻 FE 服务(SPEC-P3.5 第零步 / FR-401):持有唯一 catalog 权威缓存与会话层,
 * 独占心跳端口、监听 MySQL 协议端口,直到收到停止信号优雅退出。</p>
 *
 * <p>启动顺序(§2.1):校验配置 → 连 BE(连不上报明确原因退出,FR-407)→ 打开 catalog
 * → 心跳(可选)→ 协议监听。端口被占即启动失败并说明是哪个端口(FR-431 / 边界 12)。</p>
 */
public final class Fed implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Fed.class.getName());

    private final FeConfig config;
    private final TangDbClient client;
    private final BackendRegistry registry;
    private final FeGrpcServer heartbeatServer;
    private final SessionManager sessions;
    private final SqlEngine engine;
    private final MysqlServer mysqlServer;
    private final com.wangyu.tangdb.metrics.Metrics metrics;
    private final com.wangyu.tangdb.console.ConsoleServer consoleServer;
    private final Thread evictionThread;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private volatile boolean closed;

    private Fed(FeConfig config, TangDbClient client, BackendRegistry registry,
                FeGrpcServer heartbeatServer, SessionManager sessions, SqlEngine engine,
                MysqlServer mysqlServer, Thread evictionThread,
                com.wangyu.tangdb.metrics.Metrics metrics,
                com.wangyu.tangdb.console.ConsoleServer consoleServer) {
        this.config = config;
        this.client = client;
        this.registry = registry;
        this.heartbeatServer = heartbeatServer;
        this.sessions = sessions;
        this.engine = engine;
        this.mysqlServer = mysqlServer;
        this.evictionThread = evictionThread;
        this.metrics = metrics;
        this.consoleServer = consoleServer;
    }

    /** 完成全部启动步骤并返回运行中的 fed。失败抛异常(由 main 负责打印并退出)。 */
    public static Fed start(FeConfig config) throws Exception {
        // ① 连 BE(FR-407:连不上报明确原因)
        TangDbClient client;
        try {
            client = new TangDbClient(config.beHost(), config.bePort())
                    .withDeadlines(config.rpcDeadlineSeconds(), config.rpcBulkDeadlineSeconds(),
                            config.rpcScanDeadlineSeconds());
            client.ping(); // 快速探活,拿到明确失败原因
        } catch (RuntimeException e) {
            throw new FeStartupException("无法连接 BE " + config.beHost() + ":" + config.bePort()
                    + "(" + safeMessage(e) + ")——请先启动 BE", e);
        }

        // ② 打开 catalog(权威缓存)
        Catalog catalog;
        try {
            catalog = Catalog.open(client);
        } catch (CatalogException e) {
            client.close();
            throw new FeStartupException("FE 启动失败:" + e.getMessage(), e);
        } catch (StoreException e) {
            client.close();
            throw new FeStartupException("无法从 BE 加载元数据(" + e.getMessage()
                    + ")——请先启动 BE " + config.beHost() + ":" + config.bePort(), e);
        }

        // ③ 心跳(可选,FR-415)
        BackendRegistry registry = new BackendRegistry(config.beOfflineCycles());
        FeGrpcServer heartbeatServer = null;
        if (config.heartbeatEnabled()) {
            try {
                heartbeatServer = FeGrpcServer.start(config.heartbeatListenPort(), registry);
            } catch (FeStartupException e) {
                client.close();
                throw new FeStartupException("心跳端口 " + config.heartbeatListenPort()
                        + " 无法监听:" + e.getMessage(), e);
            }
        }

        // ④ 会话层 + 执行入口 + 协议监听
        SessionManager sessions = new SessionManager(config.sessionMax(), config.sessionIdleTimeout());
        // K114:启动时清理上次崩溃残留的排序临时文件(FE 侧目录,不碰 BE 数据目录)。
        com.wangyu.tangdb.query.Sorter.cleanTempDir(
                java.nio.file.Path.of(config.sortTmpDir()));
        // SPEC-P8 B 组:两条通道各一张指标表(边界 186:控制台与 MySQL 独立计数)。
        com.wangyu.tangdb.metrics.Metrics metrics =
                new com.wangyu.tangdb.metrics.Metrics(config.p8().metricsEnabled());
        com.wangyu.tangdb.metrics.Metrics consoleMetrics =
                new com.wangyu.tangdb.metrics.Metrics(config.p8().metricsEnabled());
        metrics.setActiveSessionsSupplier(sessions::size);
        consoleMetrics.setActiveSessionsSupplier(
                () -> (int) sessions.all().stream().filter(Session::console).count());
        metrics.setBeSupplier(() -> beSnapshot(registry));
        SqlEngine engine = new SqlEngine(client, catalog, sessions, registry,
                config.heartbeatEnabled(), config.user(), config.indexBackfillBatch(),
                config.queryBatchRows(), config.sortSpillBytes(), config.sortTmpDir(),
                config.optimizer())
                .withExecution(config.queryParallelism(), config.pushdownEnabled(),
                        config.scanBatchRows())
                .withMetrics(metrics, consoleMetrics)
                // SPEC-P7 §5.4:E 组推理算子 + 向量检索门限
                .withP7(config.p7(), new com.wangyu.tangdb.ai.AiService(config.ai()));
        // SPEC-P8 C 组:慢查询日志(阈值内零落库;诊断建议走同一计划入口)。
        com.wangyu.tangdb.slow.SlowQueryLog slowLog = new com.wangyu.tangdb.slow.SlowQueryLog(
                client, catalog, engine.stats(), config.p8().slowQueryLog(),
                config.p8().slowQueryThresholdMs(), config.p8().slowQueryMaxRows());
        engine.withSlowQueryLog(slowLog);
        // SPEC-P8 D 组:备份 / 恢复(备份在 BE 后台执行,恢复在 FE 后台线程执行)。
        engine.withBackupService(new com.wangyu.tangdb.backup.BackupService(
                client, catalog, engine.stats(), client, config.p8().backupDir()));
        System.err.println("信息: P8 慢查询 slow_query_log=" + (slowLog.enabled() ? "on" : "off")
                + " threshold_ms=" + config.p8().slowQueryThresholdMs()
                + " max_rows=" + config.p8().slowQueryMaxRows());
        // FR-724:启动日志必须写明本次使用的期限与并行度。
        System.err.println("信息: P7 向量/推理配置 vector_m=" + config.p7().vectorM()
                + " ef_construction=" + config.p7().vectorEfConstruction()
                + " ef_search=" + config.p7().vectorEfSearch()
                + " ef_max=" + config.p7().vectorEfMax()
                + " bruteforce_rows=" + config.p7().vectorBruteforceRows()
                + " parallel_min_rows=" + config.p7().parallelMinRows()
                + " ai_endpoint=" + (config.ai().endpoint().isEmpty() ? "(未配置)"
                        : config.ai().endpoint())
                + " ai_model=" + (config.ai().model().isEmpty() ? "(未配置)" : config.ai().model())
                + " ai_dim=" + config.ai().dim());
        System.err.println("信息: 执行层配置 parallelism=" + config.queryParallelism()
                + " pushdown=" + config.pushdownEnabled()
                + " scan_batch_rows=" + config.scanBatchRows()
                + " rpc_deadline=" + config.rpcDeadlineSeconds() + "s"
                + " bulk_deadline=" + config.rpcBulkDeadlineSeconds() + "s"
                + " scan_deadline=" + config.rpcScanDeadlineSeconds() + "s");
        byte[] storedHash = NativePassword.deriveStoredHash(config.password());

        MysqlServer mysqlServer;
        try {
            mysqlServer = new MysqlServer(config.mysqlPort(), engine, sessions, client, catalog,
                    config.user(), storedHash, config.p8().mysqlMaxConnections(),
                    config.p8().mysqlWaitTimeout());
            mysqlServer.start();
        } catch (IOException e) {
            if (heartbeatServer != null) {
                heartbeatServer.close();
            }
            client.close();
            throw new FeStartupException("协议端口 " + config.mysqlPort() + " 无法监听(可能已被占用):"
                    + e.getMessage(), e);
        }

        // SPEC-P8 E 组:控制台 HTTP 服务(默认 8080;端口被占用即启动失败,边界 170)。
        com.wangyu.tangdb.console.ConsoleServer consoleServer = null;
        if (config.p8().consoleEnabled()) {
            metrics.setVectorSupplier(
                    new com.wangyu.tangdb.metrics.VectorMetricsSupplier(engine, catalog));
            try {
                consoleServer = new com.wangyu.tangdb.console.ConsoleServer(
                        config.p8().consolePort(),
                        java.nio.file.Path.of("console", "dist"), metrics);
                consoleServer.withApi(new com.wangyu.tangdb.console.ConsoleApiImpl(config, engine,
                        sessions, catalog, metrics, consoleMetrics));
                consoleServer.start();
            } catch (IOException e) {
                if (heartbeatServer != null) {
                    heartbeatServer.close();
                }
                client.close();
                throw new FeStartupException("控制台端口 " + config.p8().consolePort()
                        + " 无法监听(可能已被占用):" + e.getMessage(), e);
            }
        }

        Thread evictionThread = null;
        if (sessions.idleTimeoutEnabled()) {
            evictionThread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    sessions.evictIdle();
                }
            }, "fed-idle-evict");
            evictionThread.setDaemon(true);
            evictionThread.start();
        }

        return new Fed(config, client, registry, heartbeatServer, sessions, engine,
                mysqlServer, evictionThread, metrics, consoleServer);
    }

    public int mysqlPort() {
        return mysqlServer.port();
    }

    public SessionManager sessions() {
        return sessions;
    }

    /** 协议通道指标表(控制台 /metrics 与 /api/status 用)。 */
    public com.wangyu.tangdb.metrics.Metrics metrics() {
        return metrics;
    }

    /** 把最近一次 BE 心跳折成指标快照(BE 离线时 alive=false)。 */
    static com.wangyu.tangdb.metrics.Metrics.BeSnapshot beSnapshot(BackendRegistry registry) {
        long now = System.currentTimeMillis();
        for (BackendRegistry.Backend backend : registry.all()) {
            com.wangyu.tangdb.proto.HeartbeatRequest hb = backend.lastHeartbeat();
            if (hb == null) {
                continue;
            }
            boolean alive = backend.available(now, registry.offlineCycles());
            return new com.wangyu.tangdb.metrics.Metrics.BeSnapshot(alive,
                    hb.getMemtableBytes(), hb.getMemtableCount(), hb.getImmutableMemtableCount(),
                    hb.getSegmentCount(), hb.getSegmentBytes(), hb.getWalBytes(),
                    hb.getCompactionCount(), hb.getCompactionMillis(),
                    hb.getWalFsyncCount(), hb.getWalFsyncMillis(),
                    hb.getGroupCommitAvgBatch(), hb.getPutCount(), hb.getGetCount(),
                    hb.getScanCount(), hb.getVectorSegments(), hb.getVectorNodes(),
                    hb.getVectorPendingSegments(), hb.getVectorSearches(),
                    hb.getVectorAvgCandidates());
        }
        return com.wangyu.tangdb.metrics.Metrics.BeSnapshot.EMPTY;
    }

    public boolean isClosed() {
        return closed;
    }

    /** 优雅退出(FR-413):停止接受 → 等在途结束(上限)→ 断开 → 释放端口。 */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // ① 停止接受新连接
        if (consoleServer != null) {
            consoleServer.close();
        }
        mysqlServer.close();
        // ② 等在途连接结束(上限 shutdown_grace_seconds)
        long deadline = System.currentTimeMillis() + config.shutdownGraceSeconds() * 1000L;
        while (mysqlServer.activeConnections() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // ③ 断开仍挂着的连接
        mysqlServer.closeAllClients();
        sessions.closeAll();
        if (evictionThread != null) {
            evictionThread.interrupt();
        }
        if (heartbeatServer != null) {
            heartbeatServer.close();
        }
        client.close();
        shutdownLatch.countDown();
    }

    /** 阻塞直到优雅退出(供 main 使用)。 */
    public void awaitShutdown() throws InterruptedException {
        shutdownLatch.await();
    }

    private static String safeMessage(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    // ------------------------------------------------------------ main

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);

        FeConfig config;
        try {
            config = FeConfig.load(findConfigPath(args));
        } catch (IllegalArgumentException e) {
            err.println("错误：" + e.getMessage());
            System.exit(2);
            return;
        }
        if (!"fed".equals(config.mode())) {
            err.println("错误：mode = " + config.mode() + " 不是 fed(常驻服务)");
            System.exit(2);
            return;
        }

        Fed fed;
        try {
            fed = Fed.start(config);
        } catch (FeStartupException e) {
            err.println("错误：FE 启动失败：" + e.getMessage());
            System.exit(1);
            return;
        } catch (Exception e) {
            err.println("错误：FE 启动失败：" + safeMessage(e));
            System.exit(1);
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(fed::close, "fed-shutdown"));

        out.println("fed 已就绪:mysql_port=" + fed.mysqlPort()
                + ",be=" + config.beHost() + ":" + config.bePort()
                + ",heartbeat=" + (config.heartbeatEnabled()
                        ? String.valueOf(config.heartbeatListenPort()) : "off"));
        LOG.info("fed 已就绪 mysql_port=" + fed.mysqlPort() + " session_max="
                + config.sessionMax() + " idle_timeout=" + config.sessionIdleTimeout() + "s");

        try {
            fed.awaitShutdown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        out.println("fed 已退出");
    }

    private static String findConfigPath(String[] args) {
        if (args == null) {
            return FeConfig.DEFAULT_PATH;
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null) {
                continue;
            }
            if (arg.startsWith("--config=")) {
                return arg.substring("--config=".length());
            }
            if (arg.equals("--config") && i + 1 < args.length) {
                return args[i + 1];
            }
        }
        return FeConfig.DEFAULT_PATH;
    }
}
