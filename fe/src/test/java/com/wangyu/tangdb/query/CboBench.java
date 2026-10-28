package com.wangyu.tangdb.query;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.client.TangDbClient;
import com.wangyu.tangdb.execute.Executor;
import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.parser.Parser;
import com.wangyu.tangdb.planner.OptimizerConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SC-387 的性能对比工具(不进生产路径):在**同一个 JVM**里直连 BE,对每条 SQL 跑
 * 1 次预热 + 3 次计时取中位数,分别用 {@code optimizer_enabled=true/false} 跑一遍。
 *
 * <p>为什么不用 CLI 计时:CLI 每次启动 JVM 的固定开销(数百毫秒)会把 CBO 的差异淹掉,
 * 测出来的是"JVM 启动时间"不是"计划差异"。</p>
 *
 * <pre>
 * java -cp ... com.wangyu.tangdb.query.CboBench --port 9950 --optimizer=true \
 *      --sql "Q1=SELECT ..." --sql "Q3=SELECT ..."
 * 输出: Q1=123.4(毫秒,三次中位数)
 * </pre>
 */
public final class CboBench {

    public static void main(String[] args) {
        int port = 9100;
        boolean optimizer = true;
        String host = "127.0.0.1";
        List<String> specs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--host" -> host = args[++i];
                case "--optimizer" -> optimizer = Boolean.parseBoolean(args[++i]);
                case "--sql" -> specs.add(args[++i]);
                default -> {
                    // 忽略未知参数,保持脚本可演进
                }
            }
        }
        TangDbClient client = new TangDbClient(host, port);
        try {
            Catalog catalog = Catalog.open(client);
            Executor executor = new Executor(client, catalog)
                    .withOptimizer(optimizer ? OptimizerConfig.defaults()
                            : OptimizerConfig.disabled());
            for (String spec : specs) {
                int eq = spec.indexOf('=');
                String name = eq < 0 ? spec : spec.substring(0, eq);
                String sql = eq < 0 ? spec : spec.substring(eq + 1);
                try {
                    run(executor, sql); // 预热
                    long[] times = new long[3];
                    long checksum = 0;
                    for (int i = 0; i < times.length; i++) {
                        long start = System.nanoTime();
                        Result result = run(executor, sql);
                        times[i] = (System.nanoTime() - start) / 1_000_000;
                        checksum += checksum(result);
                    }
                    Arrays.sort(times);
                    System.out.println(name + "=" + times[1] + " rows=" + checksum / 3);
                } catch (RuntimeException e) {
                    System.out.println(name + "=ERROR " + e.getMessage());
                }
            }
        } finally {
            client.close();
        }
    }

    private static Result run(Executor executor, String sql) {
        return executor.execute(Parser.parse(sql));
    }

    private static long checksum(Result result) {
        if (result instanceof Result.Table table) {
            return table.rows().size();
        }
        return 0;
    }

    private CboBench() {
    }
}
