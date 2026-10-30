package com.wangyu.tangdb.tools;

import com.wangyu.tangdb.protocol.mysql.MysqlClientConnection;

import java.util.ArrayList;
import java.util.List;

/**
 * SPEC-P8 验收用的轻量写负载(SC-453 备份期间写入 / SC-462 备份不阻塞)。
 *
 * <p>用自研 MySQL 协议客户端单连接按目标速率写入(每行一个自动提交事务),
 * 统计成功率与延迟分位。只用于验收脚本,不进产品代码。</p>
 *
 * <pre>
 * java ... P8WriteLoad --port 9966 --password p8test --table p8_load \
 *      --base 5000000 --rate 200 --seconds 3
 * </pre>
 */
public final class P8WriteLoad {

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 3406;
        String user = "root";
        String password = "tangdb";
        String database = "default";
        String table = "p8_load";
        long base = 5_000_000L;
        int rate = 200;
        int seconds = 3;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String value = i + 1 < args.length ? args[i + 1] : "";
            switch (arg) {
                case "--host" -> host = value;
                case "--port" -> port = Integer.parseInt(value);
                case "--user" -> user = value;
                case "--password" -> password = value;
                case "--db" -> database = value;
                case "--table" -> table = value;
                case "--base" -> base = Long.parseLong(value);
                case "--rate" -> rate = Integer.parseInt(value);
                case "--seconds" -> seconds = Integer.parseInt(value);
                default -> {
                    // 忽略未知参数
                }
            }
        }

        int success = 0;
        int failed = 0;
        List<Long> latencies = new ArrayList<>();
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        long seq = 0;
        long nextAt = System.currentTimeMillis();
        try (MysqlClientConnection conn =
                     MysqlClientConnection.connect(host, port, user, password, database)) {
            while (System.currentTimeMillis() < deadline) {
                long id = base + seq++;
                String sql = "INSERT INTO " + table + " (id, city, amount, note) VALUES ("
                        + id + ", 'load', " + (seq % 1000) + ", 'p8-write-load')";
                long started = System.nanoTime();
                try {
                    conn.query(sql);
                    success++;
                } catch (RuntimeException e) {
                    failed++;
                }
                latencies.add((System.nanoTime() - started) / 1_000_000L);
                nextAt += Math.max(1, 1000L / Math.max(1, rate));
                long sleep = nextAt - System.currentTimeMillis();
                if (sleep > 0) {
                    Thread.sleep(sleep);
                } else {
                    nextAt = System.currentTimeMillis();
                }
            }
        }

        latencies.sort(Long::compareTo);
        System.out.println("WRITE_LOAD success=" + success + " failed=" + failed
                + " p50=" + percentile(latencies, 0.50)
                + " p95=" + percentile(latencies, 0.95)
                + " p99=" + percentile(latencies, 0.99)
                + " max=" + (latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1)));
    }

    private static long percentile(List<Long> sorted, double q) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(q * sorted.size()) - 1;
        index = Math.max(0, Math.min(sorted.size() - 1, index));
        return sorted.get(index);
    }
}
