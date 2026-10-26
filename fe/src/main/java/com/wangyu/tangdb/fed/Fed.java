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
    private final Thread evictionThread;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private volatile boolean closed;

    private Fed(FeConfig config, TangDbClient client, BackendRegistry registry,
                FeGrpcServer heartbeatServer, SessionManager sessions, SqlEngine engine,
                MysqlServer mysqlServer, Thread evictionThread) {
        this.config = config;
        this.client = client;
        this.registry = registry;
        this.heartbeatServer = heartbeatServer;
        this.sessions = sessions;
        this.engine = engine;
        this.mysqlServer = mysqlServer;
        this.evictionThread = evictionThread;
    }

    /** 完成全部启动步骤并返回运行中的 fed。失败抛异常(由 main 负责打印并退出)。 */
    public static Fed start(FeConfig config) throws Exception {
        // ① 连 BE(FR-407:连不上报明确原因)
        TangDbClient client;
        try {
            client = new TangDbClient(config.beHost(), config.bePort());
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
        SqlEngine engine = new SqlEngine(client, catalog, sessions, registry,
                config.heartbeatEnabled(), config.user());
        byte[] storedHash = NativePassword.deriveStoredHash(config.password());

        MysqlServer mysqlServer;
        try {
            mysqlServer = new MysqlServer(config.mysqlPort(), engine, sessions, client, catalog,
                    config.user(), storedHash);
            mysqlServer.start();
        } catch (IOException e) {
            if (heartbeatServer != null) {
                heartbeatServer.close();
            }
            client.close();
            throw new FeStartupException("协议端口 " + config.mysqlPort() + " 无法监听(可能已被占用):"
                    + e.getMessage(), e);
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
                mysqlServer, evictionThread);
    }

    public int mysqlPort() {
        return mysqlServer.port();
    }

    public SessionManager sessions() {
        return sessions;
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
