package com.wangyu.tangdb.client;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.catalog.CatalogException;
import com.wangyu.tangdb.execute.Executor;
import com.wangyu.tangdb.parser.Parser;

import java.util.Arrays;
import java.util.Locale;

/**
 * 单行 INSERT 延迟测量(SC-209 的性能对比项)。
 *
 * <p>用法:{@code java ... InsertLatencyBench <fe.conf> <count>}。
 * 每个 INSERT 是自动提交事务:一次主键点查 + 一次 CommitTxn(fsync)。</p>
 */
public final class InsertLatencyBench {

    private InsertLatencyBench() {
    }

    public static void main(String[] args) throws Exception {
        String configPath = args.length > 0 ? args[0] : FeConfig.DEFAULT_PATH;
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 200;

        FeConfig config = FeConfig.load(configPath);
        try (TangDbClient client = new TangDbClient(config.beHost(), config.bePort())) {
            Catalog catalog = Catalog.open(client);
            Executor executor = new Executor(client, catalog);
            try {
                executor.execute(Parser.parse("DROP TABLE bench"));
            } catch (CatalogException ignored) {
                // 首次运行没有这张表。
            }
            executor.execute(Parser.parse(
                    "CREATE TABLE bench (id INT PRIMARY KEY, v VARCHAR(32))"));

            // 预热:让 JIT / gRPC channel / 目录缓存进入稳态。
            for (int i = 0; i < 10; i++) {
                executor.execute(Parser.parse(
                        "INSERT INTO bench VALUES (-" + (i + 1) + ", 'warmup')"));
            }

            long[] nanos = new long[count];
            for (int i = 0; i < count; i++) {
                long start = System.nanoTime();
                executor.execute(Parser.parse(
                        "INSERT INTO bench VALUES (" + i + ", 'value-" + i + "')"));
                nanos[i] = System.nanoTime() - start;
            }
            Arrays.sort(nanos);

            double avgMs = 0;
            for (long n : nanos) {
                avgMs += n;
            }
            avgMs /= nanos.length * 1_000_000.0;

            System.out.println(String.format(Locale.ROOT,
                    "INSERT 延迟: n=%d avg=%.2fms p50=%.2fms p99=%.2fms max=%.2fms",
                    count, avgMs,
                    nanos[(int) Math.floor((nanos.length - 1) * 0.50)] / 1_000_000.0,
                    nanos[(int) Math.floor((nanos.length - 1) * 0.99)] / 1_000_000.0,
                    nanos[nanos.length - 1] / 1_000_000.0));
        }
    }
}
