package com.wangyu.tangdb.client;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;

/**
 * 崩溃测试辅助程序:批量写入或校验 KV。
 *
 * <p>用法:
 * <pre>
 *   java com.wangyu.tangdb.client.CrashTest write &lt;count&gt; &lt;seed&gt; &lt;out-file&gt; [--be-host h] [--be-port p]
 *   java com.wangyu.tangdb.client.CrashTest verify &lt;in-file&gt; [--be-host h] [--be-port p]
 * </pre>
 * </p>
 */
public final class CrashTest {

    private CrashTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: CrashTest write <count> <seed> <file> [be-options]");
            System.err.println("   或 CrashTest verify <file> [be-options]");
            System.exit(2);
        }
        String mode = args[0];

        Target target = resolveTarget(args);

        if ("write".equals(mode)) {
            if (args.length < 4) {
                System.err.println("write 模式需要 <count> <seed> <file>");
                System.exit(2);
            }
            int count = Integer.parseInt(args[1]);
            long seed = Long.parseLong(args[2]);
            Path file = Paths.get(args[3]);
            write(target.host, target.port, count, seed, file);
        } else if ("verify".equals(mode)) {
            Path file = Paths.get(args[1]);
            verify(target.host, target.port, file);
        } else {
            System.err.println("未知模式: " + mode);
            System.exit(2);
        }
    }

    /** BE 连接目标:直接从 --be-host / --be-port / --config 解析(与 CLI 的 fed 参数解耦)。 */
    private static Target resolveTarget(String[] args) {
        String configPath = null;
        String beHost = null;
        String bePort = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--be-host=")) {
                beHost = arg.substring("--be-host=".length());
            } else if (arg.equals("--be-host") && i + 1 < args.length) {
                beHost = args[++i];
            } else if (arg.startsWith("--be-port=")) {
                bePort = arg.substring("--be-port=".length());
            } else if (arg.equals("--be-port") && i + 1 < args.length) {
                bePort = args[++i];
            } else if (arg.startsWith("--config=")) {
                configPath = arg.substring("--config=".length());
            } else if (arg.equals("--config") && i + 1 < args.length) {
                configPath = args[++i];
            }
        }
        FeConfig cfg = configPath == null
                ? FeConfig.load(FeConfig.DEFAULT_PATH) : FeConfig.load(configPath);
        String host = beHost == null ? cfg.beHost() : beHost;
        int port = bePort == null ? cfg.bePort() : Integer.parseInt(bePort);
        return new Target(host, port);
    }

    private record Target(String host, int port) {
    }

    private static void write(String host, int port, int count, long seed, Path file) throws Exception {
        Random random = new Random(seed);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (TangDbClient client = new TangDbClient(host, port);
             BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (int i = 0; i < count; i++) {
                String key = "key-" + i;
                String value = randomValue(random, md, i);
                String result = client.put(key, value);
                if (!"OK".equals(result)) {
                    throw new IOException("put failed for " + key + ": " + result);
                }
                writer.write(key + "\t" + value);
                writer.newLine();
                if ((i + 1) % 100 == 0) {
                    System.out.println("已写入 " + (i + 1) + " / " + count);
                }
            }
        }
        System.out.println("写入完成: " + count + " 条 -> " + file);
    }

    private static void verify(String host, int port, Path file) throws Exception {
        int total = 0;
        int failed = 0;
        try (TangDbClient client = new TangDbClient(host, port);
             BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab < 0) {
                    continue;
                }
                String key = line.substring(0, tab);
                String expected = line.substring(tab + 1);
                total++;
                try {
                    String actual = client.get(key).orElse(null);
                    if (actual == null) {
                        System.out.println("MISSING: " + key);
                        failed++;
                    } else if (!expected.equals(actual)) {
                        System.out.println("MISMATCH: " + key);
                        failed++;
                    }
                } catch (ClientException e) {
                    System.out.println("ERROR: " + key + " -> " + e.getMessage());
                    failed++;
                }
                if (total % 100 == 0) {
                    System.out.println("已校验 " + total);
                }
            }
        }
        System.out.println("校验完成: 总数=" + total + " 失败=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static String randomValue(Random random, MessageDigest md, int index) {
        byte[] bytes = new byte[64];
        random.nextBytes(bytes);
        md.reset();
        md.update(bytes);
        md.update(String.valueOf(index).getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(md.digest());
    }
}
