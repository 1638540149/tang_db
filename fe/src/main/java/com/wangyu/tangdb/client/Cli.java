package com.wangyu.tangdb.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * tang_db 交互式命令行客户端。
 *
 * <p>支持的命令：{@code put <key> <value>}、{@code get <key>}、{@code ping}、{@code exit}。
 * 启动参数：{@code --be-host <host>}、{@code --be-port <port>}，缺省为
 * {@code 127.0.0.1:9100}。</p>
 *
 * <p>所有错误只输出中文提示，绝不向用户打印 Java 异常堆栈。</p>
 */
public final class Cli {

    static final String USAGE =
            "用法: java com.wangyu.tangdb.client.Cli [--be-host <host>] [--be-port <port>]\n"
            + "命令: put <key> <value> | get <key> | ping | exit";

    private static final String PROMPT = "tangdb> ";

    private Cli() {
    }

    public static void main(String[] args) {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        // 固定 UTF-8 输出，避免 Windows 控制台默认编码把中文提示打成乱码。
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
     * 执行命令行主循环。抽成独立方法便于测试，不依赖 {@link System#in}/{@link System#out}。
     *
     * @return 进程退出码：0 正常，1 运行期错误，2 启动参数错误
     */
    static int run(String[] args, BufferedReader in, PrintStream out, PrintStream err) {
        Config config;
        try {
            config = parseArgs(args);
        } catch (IllegalArgumentException e) {
            err.println("错误：" + e.getMessage());
            err.println(USAGE);
            return 2;
        }

        out.println("tang_db 命令行客户端（BE " + config.host() + ":" + config.port() + "）");
        out.println("可用命令: put <key> <value> | get <key> | ping | exit");

        try (TangDbClient client = new TangDbClient(config.host(), config.port())) {
            while (true) {
                out.print(PROMPT);
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

                Command command;
                try {
                    command = parseCommand(line);
                } catch (IllegalArgumentException e) {
                    err.println("错误：" + e.getMessage());
                    continue;
                }

                switch (command.type()) {
                    case EMPTY:
                        break;
                    case EXIT:
                        out.println("再见");
                        return 0;
                    case PUT:
                        try {
                            out.println(client.put(command.key(), command.value()));
                        } catch (ClientException e) {
                            printClientError(err, e);
                        } catch (RuntimeException e) {
                            printInternalError(err, e);
                        }
                        break;
                    case GET:
                        try {
                            Optional<String> value = client.get(command.key());
                            out.println(value.orElse(""));
                        } catch (ClientException e) {
                            printClientError(err, e);
                        } catch (RuntimeException e) {
                            printInternalError(err, e);
                        }
                        break;
                    case PING:
                        try {
                            out.println("BE 版本: " + client.ping());
                        } catch (ClientException e) {
                            printClientError(err, e);
                        } catch (RuntimeException e) {
                            printInternalError(err, e);
                        }
                        break;
                    default:
                        err.println("错误：未知命令");
                        break;
                }
            }
        } catch (RuntimeException e) {
            printInternalError(err, e);
            return 1;
        }
    }

    /**
     * 解析一行命令。空白行返回 {@code EMPTY}；非法命令抛出带中文提示的
     * {@link IllegalArgumentException}。
     */
    static Command parseCommand(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty()) {
            return new Command(Type.EMPTY, null, null);
        }

        String[] tokens = trimmed.split("\\s+", 3);
        String name = tokens[0].toLowerCase(Locale.ROOT);
        switch (name) {
            case "put":
                if (tokens.length < 3 || tokens[2].isEmpty()) {
                    throw new IllegalArgumentException("用法: put <key> <value>");
                }
                return new Command(Type.PUT, tokens[1], tokens[2]);
            case "get":
                if (tokens.length != 2) {
                    throw new IllegalArgumentException("用法: get <key>");
                }
                return new Command(Type.GET, tokens[1], null);
            case "ping":
                if (tokens.length != 1) {
                    throw new IllegalArgumentException("ping 命令不接受参数");
                }
                return new Command(Type.PING, null, null);
            case "exit":
                if (tokens.length != 1) {
                    throw new IllegalArgumentException("exit 命令不接受参数");
                }
                return new Command(Type.EXIT, null, null);
            default:
                throw new IllegalArgumentException(
                        "未知命令: " + tokens[0] + "。可用命令: put、get、ping、exit");
        }
    }

    /**
     * 解析启动参数，支持 {@code --be-host <host>} / {@code --be-port <port>}，
     * 以及 {@code --be-host=<host>} / {@code --be-port=<port>} 两种写法。
     */
    static Config parseArgs(String[] args) {
        String host = TangDbClient.DEFAULT_HOST;
        int port = TangDbClient.DEFAULT_PORT;
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
            } else {
                throw new IllegalArgumentException("未知启动参数: " + arg + "。\n" + USAGE);
            }
        }
        return new Config(host, port);
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

    private static void printClientError(PrintStream err, ClientException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = "未知错误";
        }
        err.println("错误：" + message);
    }

    private static void printInternalError(PrintStream err, RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        err.println("错误：客户端内部错误（" + message + "）");
    }

    enum Type {
        EMPTY,
        PUT,
        GET,
        PING,
        EXIT
    }

    record Command(Type type, String key, String value) {
    }

    record Config(String host, int port) {
    }
}
