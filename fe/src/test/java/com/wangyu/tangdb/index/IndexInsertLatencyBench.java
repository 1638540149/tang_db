package com.wangyu.tangdb.index;

import com.wangyu.tangdb.protocol.mysql.MysqlClientConnection;

import java.util.Arrays;
import java.util.Locale;

/**
 * SC-333 / FR-526:单行 INSERT 在"无索引 / 1 个索引 / 3 个索引"下的 p50 / p99。
 *
 * <p>用法:{@code java ... IndexInsertLatencyBench <port> <user> <password> <db> <count> <indexCount>}。
 * 全部语句走 MySQL 协议连常驻 fed —— 不在进程内另建 catalog,避免绕过 fed 的权威缓存
 * (P3.5 之后 DDL 必须经由 fed)。每个 INSERT 都是自动提交事务,含一次 WAL fsync。</p>
 */
public final class IndexInsertLatencyBench {

    private IndexInsertLatencyBench() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        String user = args[1];
        String password = args[2];
        String db = args[3];
        int count = args.length > 4 ? Integer.parseInt(args[4]) : 300;
        int indexCount = args.length > 5 ? Integer.parseInt(args[5]) : 0;

        try (MysqlClientConnection conn = MysqlClientConnection.connect(
                "127.0.0.1", port, user, password, db)) {
            try {
                conn.query("DROP TABLE bench_idx");
            } catch (RuntimeException ignored) {
                // 首次运行没有这张表。
            }
            conn.query("CREATE TABLE bench_idx (id INT PRIMARY KEY, a INT, b INT, c INT)");
            String[] columns = {"a", "b", "c"};
            for (int i = 0; i < Math.min(indexCount, columns.length); i++) {
                conn.query("CREATE INDEX idx_" + columns[i] + " ON bench_idx (" + columns[i] + ")");
            }

            for (int i = 0; i < 20; i++) {
                conn.query("INSERT INTO bench_idx VALUES (-" + (i + 1) + ", 1, 1, 1)");
            }

            long[] nanos = new long[count];
            for (int i = 0; i < count; i++) {
                String sql = "INSERT INTO bench_idx VALUES (" + i + ", " + (i % 7)
                        + ", " + (i % 11) + ", " + (i % 13) + ")";
                long start = System.nanoTime();
                conn.query(sql);
                nanos[i] = System.nanoTime() - start;
            }
            Arrays.sort(nanos);
            double avgMs = 0;
            for (long n : nanos) {
                avgMs += n;
            }
            avgMs /= nanos.length * 1_000_000.0;
            System.out.println(String.format(Locale.ROOT,
                    "INSERT 延迟(索引数=%d): n=%d avg=%.2fms p50=%.2fms p99=%.2fms max=%.2fms",
                    indexCount, count, avgMs,
                    nanos[(int) Math.floor((nanos.length - 1) * 0.50)] / 1_000_000.0,
                    nanos[(int) Math.floor((nanos.length - 1) * 0.99)] / 1_000_000.0,
                    nanos[nanos.length - 1] / 1_000_000.0));
        }
    }
}
