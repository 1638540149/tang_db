package com.wangyu.tangdb.client;

import com.wangyu.tangdb.protocol.mysql.MysqlClientConnection;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * tang_db 命令行客户端(P3.5 起是瘦客户端:通过 MySQL 协议连 fed,不再自带 FE、不直连 BE)。
 *
 * <p>参数:{@code --host / --port / --user / --password / --database},或从
 * {@code config/fe.conf} 取(FR-408 / SC-303)。fed 没起时报"连不上 fed",不崩栈。</p>
 *
 * <p>所有错误只输出中文提示,绝不向用户打印 Java 堆栈(FR-173 / FR-363)。</p>
 */
public final class Cli {

    static final String USAGE =
            "用法: java com.wangyu.tangdb.client.Cli [--config <file>] [--host <host>] [--port <port>]\n"
            + "          [--user <user>] [--password <password>] [--database <db>]\n"
            + "配置: 默认读 " + FeConfig.DEFAULT_PATH + ",命令行参数覆盖配置文件\n"
            + "命令: SQL 以 ; 结束;BEGIN / COMMIT / ROLLBACK | SHOW TABLES | SHOW DATABASES | "
            + "SHOW SESSIONS | DESC <表> | SHOW BACKENDS | exit";

    private static final String PROMPT = "tangdb> ";
    private static final String TXN_PROMPT = "tangdb(txn)> ";
    private static final String CONTINUATION_PROMPT = "    -> ";

    private Cli() {
    }

    public static void main(String[] args) {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
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

        out.println("tang_db 命令行客户端（fed " + config.host() + ":" + config.port() + "）");
        out.println("输入 SQL 以 ; 结束;可用: BEGIN / COMMIT / ROLLBACK | SHOW TABLES | "
                + "SHOW DATABASES | SHOW SESSIONS | DESC <表> | SHOW BACKENDS | exit");

        MysqlClientConnection conn;
        try {
            conn = MysqlClientConnection.connect(config.host(), config.port(),
                    config.user(), config.password(), config.database());
        } catch (MysqlClientConnection.MysqlClientException e) {
            err.println("错误：" + e.getMessage());
            return 1;
        } catch (IOException e) {
            err.println("错误：连不上 fed " + config.host() + ":" + config.port()
                    + "（" + safeMessage(e) + "）");
            return 1;
        }

        try {
            return loop(in, out, err, conn);
        } finally {
            conn.close();
        }
    }

    private static int loop(BufferedReader in, PrintStream out, PrintStream err,
                            MysqlClientConnection conn) {
        SqlBuffer buffer = new SqlBuffer();
        while (true) {
            String prompt = conn.inTransaction() ? TXN_PROMPT : PROMPT;
            out.print(buffer.hasPending() ? CONTINUATION_PROMPT : prompt);
            out.flush();

            String line;
            try {
                line = in.readLine();
            } catch (IOException e) {
                err.println("错误：读取输入失败");
                return 1;
            }
            if (line == null) {
                out.println();
                return 0;
            }

            String trimmed = line.trim();
            if (!buffer.hasPending()) {
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (isExit(trimmed)) {
                    out.println("再见");
                    return 0;
                }
                if ("ping".equalsIgnoreCase(trimmed)) {
                    doPing(conn, out, err);
                    continue;
                }
            }

            List<String> statements = buffer.append(line);
            for (String statement : statements) {
                if (isExit(statement)) {
                    out.println("再见");
                    return 0;
                }
                executeOne(statement, out, err, conn);
            }
        }
    }

    private static void executeOne(String statement, PrintStream out, PrintStream err,
                                   MysqlClientConnection conn) {
        boolean failed = false;
        try {
            MysqlClientConnection.Response response = conn.query(statement);
            if (response.isResultSet()) {
                out.print(TableFormatter.format(response.columns(), response.rows()));
            } else {
                String info = response.info();
                out.println(info != null && !info.isBlank() ? info : okText(response.affectedRows()));
            }
        } catch (MysqlClientConnection.MysqlClientException e) {
            failed = true;
            err.println("错误：" + e.getMessage());
        } catch (IOException e) {
            failed = true;
            err.println("错误：与 fed 通信失败（" + safeMessage(e) + "）");
        } catch (RuntimeException e) {
            failed = true;
            err.println("错误：客户端内部错误（" + safeMessage(e) + "）");
        }
        // FR-344:事务内语句失败不自动回滚,但必须让用户知道事务还在。
        if (failed && conn.inTransaction()) {
            err.println("提示：事务仍在进行中，可继续执行或 ROLLBACK");
        }
    }

    private static String okText(long affectedRows) {
        return "OK, " + affectedRows + (affectedRows == 1 ? " row affected" : " rows affected");
    }

    private static void doPing(MysqlClientConnection conn, PrintStream out, PrintStream err) {
        try {
            conn.ping();
            out.println("OK");
        } catch (MysqlClientConnection.MysqlClientException e) {
            err.println("错误：" + e.getMessage());
        } catch (IOException e) {
            err.println("错误：" + safeMessage(e));
        }
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
        String host = "127.0.0.1";
        int port = base.mysqlPort();
        String user = base.user();
        String password = base.password();
        String database = null;

        if (args == null) {
            return new Config(host, port, user, password, database);
        }

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null) {
                throw new IllegalArgumentException("启动参数不能为 null");
            }
            if (arg.startsWith("--host=")) {
                host = requireNonBlank(arg.substring("--host=".length()), "--host");
            } else if (arg.equals("--host")) {
                host = requireNonBlank(nextValue(args, ++i, "--host"), "--host");
            } else if (arg.startsWith("--port=")) {
                port = parsePort(arg.substring("--port=".length()));
            } else if (arg.equals("--port")) {
                port = parsePort(nextValue(args, ++i, "--port"));
            } else if (arg.startsWith("--user=")) {
                user = requireNonBlank(arg.substring("--user=".length()), "--user");
            } else if (arg.equals("--user")) {
                user = requireNonBlank(nextValue(args, ++i, "--user"), "--user");
            } else if (arg.startsWith("--password=")) {
                password = arg.substring("--password=".length());
            } else if (arg.equals("--password")) {
                password = nextValue(args, ++i, "--password");
            } else if (arg.startsWith("--database=")) {
                database = requireNonBlank(arg.substring("--database=".length()), "--database");
            } else if (arg.equals("--database")) {
                database = requireNonBlank(nextValue(args, ++i, "--database"), "--database");
            } else if (arg.startsWith("--config=")) {
                requireNonBlank(arg.substring("--config=".length()), "--config");
            } else if (arg.equals("--config")) {
                requireNonBlank(nextValue(args, ++i, "--config"), "--config");
            } else {
                throw new IllegalArgumentException("未知启动参数: " + arg + "。\n" + USAGE);
            }
        }
        return new Config(host, port, user, password, database);
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

    private static String safeMessage(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /** 生效的连接配置。 */
    record Config(String host, int port, String user, String password, String database) {
    }
}
