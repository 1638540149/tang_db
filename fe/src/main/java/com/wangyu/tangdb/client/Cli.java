package com.wangyu.tangdb.client;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.catalog.CatalogException;
import com.wangyu.tangdb.execute.Executor;
import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.parser.ParseException;
import com.wangyu.tangdb.parser.Parser;
import com.wangyu.tangdb.proto.HeartbeatRequest;
import com.wangyu.tangdb.server.BackendRegistry;
import com.wangyu.tangdb.server.FeGrpcServer;
import com.wangyu.tangdb.server.FeStartupException;
import com.wangyu.tangdb.storage.StoreException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * tang_db 命令行客户端(P3 起是带事务的 SQL 控制台)。
 *
 * <p>启动顺序:加载配置 → 连接 BE 并加载 catalog(BE 不可用则启动失败,FR-111)
 * → 按 heartbeat_enabled 决定是否启动 FE 心跳服务 → 进入 SQL 循环。</p>
 *
 * <p>所有错误只输出中文提示,绝不向用户打印 Java 堆栈(FR-173 / FR-363)。</p>
 */
public final class Cli {

    static final String USAGE =
            "用法: java com.wangyu.tangdb.client.Cli [--config <file>] [--be-host <host>] [--be-port <port>]\n"
            + "配置: 默认读 " + FeConfig.DEFAULT_PATH + ",命令行参数覆盖配置文件\n"
            + "命令: SQL 以 ; 结束;BEGIN / COMMIT / ROLLBACK | SHOW TABLES | SHOW DATABASES | "
            + "DESC <表> | SHOW BACKENDS | exit";

    private static final String PROMPT = "tangdb> ";
    private static final String TXN_PROMPT = "tangdb(txn)> ";
    private static final String CONTINUATION_PROMPT = "    -> ";
    private static final String HEARTBEAT_DISABLED_BACKENDS =
            "本会话未启用心跳监听(heartbeat_enabled = false),看不到 BE 状态";

    private Cli() {
    }

    public static void main(String[] args) {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        // 固定 UTF-8 输出,避免 Windows 控制台默认编码把中文提示打成乱码。
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        int exitCode = run(args, in, out, err);
        out.flush();
        err.flush();
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * 执行命令行主循环。抽成独立方法便于测试,不依赖 {@link System#in}/{@link System#out}。
     *
     * @return 进程退出码:0 正常,1 运行期错误,2 启动参数错误
     */
    static int run(String[] args, BufferedReader in, PrintStream out, PrintStream err) {
        FeConfig feConfig;
        Config config;
        try {
            feConfig = FeConfig.load(findConfigPath(args));
            config = parseArgs(args, feConfig);
        } catch (IllegalArgumentException e) {
            err.println("错误：" + e.getMessage());
            err.println(USAGE);
            return 2;
        }

        out.println("tang_db 命令行客户端（BE " + config.host() + ":" + config.port()
                + "，" + (feConfig.heartbeatEnabled()
                        ? "FE 心跳端口 " + feConfig.heartbeatListenPort()
                        : "本会话未启用心跳监听")
                + "）");
        out.println("输入 SQL 以 ; 结束;可用: BEGIN / COMMIT / ROLLBACK | SHOW TABLES | "
                + "SHOW DATABASES | DESC <表> | SHOW BACKENDS | exit");

        TangDbClient client;
        try {
            client = new TangDbClient(config.host(), config.port());
        } catch (RuntimeException e) {
            err.println("错误：创建 BE 客户端失败（" + safeMessage(e) + "）");
            return 1;
        }

        try {
            Catalog catalog;
            try {
                catalog = Catalog.open(client);
            } catch (CatalogException e) {
                err.println("错误：FE 启动失败：" + e.getMessage());
                return 1;
            } catch (StoreException e) {
                err.println("错误：FE 启动失败：无法从 BE 加载元数据（" + e.getMessage() + "）");
                err.println("提示：FE 启动前必须先启动 BE " + config.host() + ":" + config.port());
                return 1;
            }

            BackendRegistry registry = new BackendRegistry(feConfig.beOfflineCycles());
            FeGrpcServer server = null;
            if (feConfig.heartbeatEnabled()) {
                try {
                    server = FeGrpcServer.start(feConfig.heartbeatListenPort(), registry);
                } catch (FeStartupException e) {
                    err.println("错误：FE 启动失败：" + e.getMessage());
                    return 1;
                }
            }

            try {
                Executor executor = new Executor(client, catalog);
                return loop(in, out, err, config, feConfig, registry, executor, client);
            } finally {
                if (server != null) {
                    server.close();
                }
            }
        } catch (RuntimeException e) {
            printInternalError(err, e);
            return 1;
        } finally {
            client.close();
        }
    }

    private static int loop(BufferedReader in, PrintStream out, PrintStream err,
                            Config config, FeConfig feConfig, BackendRegistry registry,
                            Executor executor, TangDbClient client) {
        SqlBuffer buffer = new SqlBuffer();
        while (true) {
            String prompt = executor.inTransaction() ? TXN_PROMPT : PROMPT;
            out.print(buffer.hasPending() ? CONTINUATION_PROMPT : prompt);
            out.flush();

            String line;
            try {
                line = in.readLine();
            } catch (IOException e) {
                err.println("错误：读取输入失败");
                finishTransaction(executor, out);
                return 1;
            }
            if (line == null) {
                out.println();
                finishTransaction(executor, out);
                return 0;
            }

            String trimmed = line.trim();
            if (!buffer.hasPending()) {
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (isExit(trimmed)) {
                    finishTransaction(executor, out);
                    out.println("再见");
                    return 0;
                }
                if ("ping".equalsIgnoreCase(trimmed)) {
                    doPing(client, out, err);
                    continue;
                }
                if ("show backends".equalsIgnoreCase(trimmed)) {
                    printBackends(out, feConfig, registry);
                    continue;
                }
            }

            List<String> statements = buffer.append(line);
            for (String statement : statements) {
                if (isExit(statement)) {
                    finishTransaction(executor, out);
                    out.println("再见");
                    return 0;
                }
                if ("show backends".equalsIgnoreCase(statement)) {
                    printBackends(out, feConfig, registry);
                    continue;
                }
                executeOne(statement, out, err, config, feConfig, registry, executor);
            }
        }
    }

    private static void executeOne(String statement, PrintStream out, PrintStream err,
                                   Config config, FeConfig feConfig, BackendRegistry registry,
                                   Executor executor) {
        boolean failed = false;
        try {
            ensureBackendAvailable(feConfig, registry, config);
            Result result = executor.execute(Parser.parse(statement));
            printResult(result, out);
        } catch (ParseException e) {
            failed = true;
            err.println("错误：" + e.getMessage());
        } catch (CatalogException e) {
            failed = true;
            err.println("错误：" + e.getMessage());
        } catch (StoreException e) {
            failed = true;
            err.println("错误：" + e.getMessage());
        } catch (ClientException e) {
            failed = true;
            err.println("错误：" + e.getMessage());
        } catch (RuntimeException e) {
            failed = true;
            printInternalError(err, e);
        }
        // FR-344:事务内语句失败不自动回滚,但必须让用户知道事务还在。
        if (failed && executor.inTransaction()) {
            err.println("提示：事务仍在进行中，可继续执行或 ROLLBACK");
        }
    }

    /** CLI 退出时丢弃未提交事务(FR-345)。 */
    private static void finishTransaction(Executor executor, PrintStream out) {
        String message = executor.rollbackOnDisconnect();
        if (message != null) {
            out.println(message);
        }
    }

    /**
     * BE 存活判定:如果 FE 已经认识至少一个 BE 且全部判死,直接给出明确错误(FR-156),
     * 不让 SQL 请求挂在那里等超时。
     *
     * <p>heartbeat_enabled = false 时不看心跳(本来就没有),连接错误交给 gRPC 返回。</p>
     */
    private static void ensureBackendAvailable(FeConfig feConfig, BackendRegistry registry,
                                               Config config) throws ClientException {
        // 心跳关闭时 registry 恒为空,直接用 gRPC 的真实结果,不误报"BE 不可用"。
        if (!feConfig.heartbeatEnabled()) {
            return;
        }
        if (registry.all().isEmpty()) {
            return; // 还没收到注册(刚启动);真正的连接错误会由 gRPC 返回
        }
        if (!registry.anyAvailable()) {
            throw new ClientException("BE 不可用(" + config.host() + ":" + config.port()
                    + "," + registry.unavailableHint() + ")", "UNAVAILABLE");
        }
    }

    private static void printResult(Result result, PrintStream out) {
        if (result instanceof Result.Message message) {
            out.println(message.text());
            return;
        }
        Result.Table table = (Result.Table) result;
        out.print(TableFormatter.format(table.columns(), table.rows()));
    }

    private static void printBackends(PrintStream out, FeConfig feConfig, BackendRegistry registry) {
        if (!feConfig.heartbeatEnabled()) {
            // FR-367:明确说明"没监听",不得报"BE 不可用"或返回空表。
            out.println(HEARTBEAT_DISABLED_BACKENDS);
            return;
        }
        out.print(formatBackends(registry));
        out.flush();
    }

    private static void doPing(TangDbClient client, PrintStream out, PrintStream err) {
        try {
            out.println("BE 版本: " + client.ping());
        } catch (ClientException e) {
            err.println("错误：" + e.getMessage());
        } catch (RuntimeException e) {
            printInternalError(err, e);
        }
    }

    /** SHOW BACKENDS:be_id、endpoint、version、状态、最后心跳、内存表/segment/put/get(FR-155)。 */
    static String formatBackends(BackendRegistry registry) {
        long now = System.currentTimeMillis();
        List<Object[]> rows = new ArrayList<>();
        for (BackendRegistry.Backend backend : registry.all()) {
            HeartbeatRequest hb = backend.lastHeartbeat();
            rows.add(new Object[]{
                    backend.beId(),
                    backend.endpoint(),
                    backend.version(),
                    backend.statusText(now, registry.offlineCycles()),
                    backend.lastSeenText(),
                    hb == null ? 0 : hb.getMemtableBytes(),
                    hb == null ? 0 : hb.getSegmentCount(),
                    hb == null ? 0 : hb.getPutCount(),
                    hb == null ? 0 : hb.getGetCount()
            });
        }
        return TableFormatter.format(
                List.of("be_id", "endpoint", "version", "status", "last_heartbeat",
                        "memtable_bytes", "segment_count", "put_count", "get_count"),
                rows);
    }

    private static boolean isExit(String text) {
        return "exit".equalsIgnoreCase(text) || "quit".equalsIgnoreCase(text);
    }

    // ------------------------------------------------------------ 启动参数

    /** 解析启动参数并加载配置:先按 {@code --config} 读配置文件,再让命令行参数覆盖它。 */
    static Config parseArgs(String[] args) {
        return parseArgs(args, FeConfig.load(findConfigPath(args)));
    }

    /** 在给定配置的基础上解析命令行参数(不做文件 IO,便于测试)。 */
    static Config parseArgs(String[] args, FeConfig base) {
        String host = base.beHost();
        int port = base.bePort();
        if (args == null) {
            return new Config(host, port);
        }

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null) {
                throw new IllegalArgumentException("启动参数不能为 null");
            }
            if (arg.startsWith("--be-host=")) {
                host = requireNonBlank(arg.substring("--be-host=".length()), "--be-host");
            } else if (arg.equals("--be-host")) {
                host = requireNonBlank(nextValue(args, ++i, "--be-host"), "--be-host");
            } else if (arg.startsWith("--be-port=")) {
                port = parsePort(arg.substring("--be-port=".length()));
            } else if (arg.equals("--be-port")) {
                port = parsePort(nextValue(args, ++i, "--be-port"));
            } else if (arg.startsWith("--config=")) {
                requireNonBlank(arg.substring("--config=".length()), "--config");
            } else if (arg.equals("--config")) {
                requireNonBlank(nextValue(args, ++i, "--config"), "--config");
            } else {
                throw new IllegalArgumentException("未知启动参数: " + arg + "。\n" + USAGE);
            }
        }
        return new Config(host, port);
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
                return requireNonBlank(arg.substring("--config=".length()), "--config");
            }
            if (arg.equals("--config")) {
                return requireNonBlank(nextValue(args, ++i, "--config"), "--config");
            }
        }
        return FeConfig.DEFAULT_PATH;
    }

    private static String nextValue(String[] args, int index, String option) {
        if (index >= args.length || args[index] == null || args[index].startsWith("--")) {
            throw new IllegalArgumentException("参数 " + option + " 缺少取值");
        }
        return args[index];
    }

    private static String requireNonBlank(String value, String option) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("参数 " + option + " 不能为空");
        }
        return value.trim();
    }

    private static int parsePort(String raw) {
        try {
            int port = Integer.parseInt(raw.trim());
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("端口无效: " + raw + "（应为 1-65535）");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("端口无效: " + raw + "（应为 1-65535）");
        }
    }

    private static void printInternalError(PrintStream err, RuntimeException e) {
        err.println("错误：客户端内部错误（" + safeMessage(e) + "）");
    }

    private static String safeMessage(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /** 生效的连接配置(心跳端口等其它项在 {@link FeConfig} 里)。 */
    record Config(String host, int port) {
    }
}
