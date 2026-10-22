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
 * <p>配置文件与 BE 侧 {@code config/be.conf} 用的是同一套语法,BE 的解析在
 * {@code be/internal/config/config.go} 里手写实现(不引第三方库)。</p>
 */
public final class FeConfig {

    /** 未显式指定 {@code --config} 时查找的配置文件路径。 */
    public static final String DEFAULT_PATH = "./config/fe.conf";

    private final String beHost;
    private final int bePort;

    private FeConfig(String beHost, int bePort) {
        this.beHost = beHost;
        this.bePort = bePort;
    }

    /** 内置默认配置。 */
    public static FeConfig defaults() {
        return new FeConfig(TangDbClient.DEFAULT_HOST, TangDbClient.DEFAULT_PORT);
    }

    /** 直接用给定取值构造配置(供程序化使用与测试)。 */
    public static FeConfig of(String beHost, int bePort) {
        if (beHost == null || beHost.isBlank()) {
            throw new IllegalArgumentException("be_host 不能为空");
        }
        if (bePort < 1 || bePort > 65535) {
            throw new IllegalArgumentException("be_port 无效: " + bePort + "（应为 1-65535）");
        }
        return new FeConfig(beHost.trim(), bePort);
    }

    public String beHost() {
        return beHost;
    }

    public int bePort() {
        return bePort;
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

        String host = TangDbClient.DEFAULT_HOST;
        int port = TangDbClient.DEFAULT_PORT;

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
                case "be_port" -> port = parsePort(value, path, i + 1);
                default -> throw new IllegalArgumentException(
                        path + " 第 " + (i + 1) + " 行未知配置项: " + key);
            }
        }

        return new FeConfig(host, port);
    }

    private static String requireNonBlank(String value, String path, int lineNo) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(path + " 第 " + lineNo + " 行 be_host 不能为空");
        }
        return value.trim();
    }

    private static int parsePort(String raw, String path, int lineNo) {
        try {
            int port = Integer.parseInt(raw.trim());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException("越界");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    path + " 第 " + lineNo + " 行 be_port 无效: " + raw + "（应为 1-65535）");
        }
    }
}
