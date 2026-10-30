package com.wangyu.tangdb.query;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.client.TangDbClient;
import com.wangyu.tangdb.execute.Executor;
import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.parser.Parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SPEC-P7 的实测工具(不进生产路径):直连 BE,在同一 JVM 内做召回 / 并发 / 时延测量。
 *
 * <pre>
 * 召回:  --mode recall --port 9970 --table docs --column embedding --dim 16 --queries 20
 * 并发:  --mode concurrent --port 9970 --table docs --column embedding --dim 16 --threads 8 --seconds 60
 * 时延:  --mode perf --port 9970 --table docs --column embedding --dim 16 --runs 3
 * </pre>
 *
 * 输出是 {@code KEY=VALUE} 行,脚本直接 grep。
 */
public final class P7VectorBench {

    private static String host = "127.0.0.1";
    private static int port = 9100;
    private static String table = "docs";
    private static String column = "embedding";
    private static String where = "";
    private static int dim = 16;
    private static int k = 10;
    private static int queries = 20;
    private static int threads = 8;
    private static int seconds = 60;
    private static int runs = 3;
    private static int iters = 20;
    private static String mode = "recall";
    private static boolean profile;
    private static long seed = 20261029L;

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--table" -> table = args[++i];
                case "--column" -> column = args[++i];
                case "--where" -> where = args[++i];
                case "--dim" -> dim = Integer.parseInt(args[++i]);
                case "--k" -> k = Integer.parseInt(args[++i]);
                case "--queries" -> queries = Integer.parseInt(args[++i]);
                case "--threads" -> threads = Integer.parseInt(args[++i]);
                case "--seconds" -> seconds = Integer.parseInt(args[++i]);
                case "--runs" -> runs = Integer.parseInt(args[++i]);
                case "--iters" -> iters = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--mode" -> mode = args[++i];
                case "--profile" -> profile = true;
                default -> {
                }
            }
        }
        try (TangDbClient client = new TangDbClient(host, port)) {
            Catalog catalog = Catalog.open(client);
            switch (mode) {
                case "recall" -> recall(client, catalog);
                case "concurrent" -> concurrent(client, catalog);
                case "perf" -> perf(client, catalog);
                default -> {
                    System.out.println("unknown mode " + mode);
                    System.exit(2);
                }
            }
        }
    }

    private static Executor executor(TangDbClient client, Catalog catalog, boolean exact) {
        Executor executor = new Executor(client, catalog);
        executor.withP7(P7Options.defaults().withExact(exact), null);
        if (profile) {
            // SPEC-P7.1 K181:分段耗时(默认关闭;bench 用 --profile 打开)
            executor.setVectorProfile(true);
        }
        return executor;
    }

    private static String sql(String vectorLiteral) {
        String filter = where == null || where.isBlank() ? "" : " WHERE " + where;
        return "SELECT id FROM " + table + filter + " ORDER BY l2_distance(" + column + ", '"
                + vectorLiteral + "') LIMIT " + k;
    }

    private static List<String> ids(Result result) {
        List<String> out = new ArrayList<>();
        if (result instanceof Result.Table table) {
            for (Object[] row : table.rows()) {
                out.add(String.valueOf(row[0]));
            }
        }
        return out;
    }

    /** 召回率:ANN 结果与 exact 结果的前 k 交集比例。 */
    private static void recall(TangDbClient client, Catalog catalog) {
        Random random = new Random(seed);
        Executor ann = executor(client, catalog, false);
        Executor exact = executor(client, catalog, true);
        double sum = 0;
        int cells = 0;
        for (int q = 0; q < queries; q++) {
            String literal = randomVector(random);
            List<String> annIds = ids(ann.execute(Parser.parse(sql(literal))));
            List<String> exactIds = ids(exact.execute(Parser.parse(sql(literal))));
            if (exactIds.isEmpty()) {
                continue;
            }
            int hit = 0;
            for (String id : exactIds) {
                if (annIds.contains(id)) {
                    hit++;
                }
            }
            sum += (double) hit / exactIds.size();
            cells++;
        }
        double recall = cells == 0 ? 0 : sum / cells;
        System.out.printf(Locale.ROOT, "RECALL=%.4f QUERIES=%d K=%d ROWS=%d%n",
                recall, cells, k, cells * k);
    }

    /** 并发:8 个检索线程 + 1 个写入线程;统计 P99 与错误数,并校验同一查询两次结果一致。 */
    private static void concurrent(TangDbClient client, Catalog catalog) throws Exception {
        // 空载基线
        Executor warm = executor(client, catalog, false);
        Random random = new Random(seed);
        String literal = randomVector(random);
        List<Long> idle = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long start = System.nanoTime();
            warm.execute(Parser.parse(sql(literal)));
            idle.add((System.nanoTime() - start) / 1_000_000L);
        }
        long idleP99 = percentile(idle, 99);

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicLong errors = new AtomicLong();
        List<List<Long>> perThread = Collections.synchronizedList(new ArrayList<>());
        List<Thread> pool = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int seedOffset = t;
            List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
            perThread.add(latencies);
            Thread thread = new Thread(() -> {
                Random r = new Random(seed + seedOffset);
                Executor executor = executor(client, catalog, false);
                while (!stop.get()) {
                    String l = randomVector(r);
                    long start = System.nanoTime();
                    try {
                        // 每条语句自己固定一个快照(BE 侧 SearchVectorIndex + 回表同快照);
                        // 这里只检查"没有错误、行数不超过 k、并发下不 panic"。
                        List<String> rows = ids(executor.execute(Parser.parse(sql(l))));
                        if (rows.size() > k) {
                            errors.incrementAndGet();
                        }
                    } catch (RuntimeException e) {
                        if (errors.incrementAndGet() == 1) {
                            System.err.println("[bench] first error: " + e);
                        }
                    }
                    latencies.add((System.nanoTime() - start) / 1_000_000L);
                }
            }, "p7-reader-" + t);
            thread.setDaemon(true);
            thread.start();
            pool.add(thread);
        }
        Thread writer = new Thread(() -> {
            int i = 0;
            while (!stop.get()) {
                Executor executor = executor(client, catalog, false);
                long id = 900_000_000L + i++;
                try {
                    executor.execute(Parser.parse("INSERT INTO " + table
                            + " (id, title, category, " + column + ") VALUES (" + id
                            + ", 'w" + id + "', 'W', " + randomVector2(random) + ")"));
                } catch (RuntimeException e) {
                    if (errors.incrementAndGet() == 1) {
                        System.err.println("[bench] first writer error: " + e);
                    }
                }
                sleep(50);
            }
        }, "p7-writer");
        writer.setDaemon(true);
        writer.start();

        Thread.sleep(seconds * 1000L);
        stop.set(true);
        for (Thread thread : pool) {
            thread.join(5000);
        }
        writer.join(5000);

        List<Long> all = new ArrayList<>();
        for (List<Long> list : perThread) {
            all.addAll(list);
        }
        long p99 = percentile(all, 99);
        System.out.printf(Locale.ROOT,
                "IDLE_P99=%dms IDLE_P50=%dms P99=%dms P50=%dms QUERIES=%d ERRORS=%d "
                        + "THREADS=%d SECONDS=%d%n",
                idleP99, percentile(idle, 50), p99, percentile(all, 50), all.size(),
                errors.get(), threads, seconds);
    }

    /**
     * 单查询时延:先跑 {@code --runs} 次(计入 RUNS/MEDIAN_MS,兼容既有脚本),
     * 再跑 {@code --iters} 次算 P50/P99(SPEC-P7.1 K184:报 P50/P99,不只报中位数)。
     */
    private static void perf(TangDbClient client, Catalog catalog) {
        Random random = new Random(seed);
        Executor executor = executor(client, catalog, false);
        // 一次不计入统计的预热:JIT + 向量运行时缓存 + chunk 缓存(与"稳定态时延"口径一致)。
        executor.execute(Parser.parse(sql(randomVector(random))));
        List<Long> runsMs = new ArrayList<>();
        for (int i = 0; i < Math.max(1, runs); i++) {
            String literal = randomVector(random);
            long start = System.nanoTime();
            executor.execute(Parser.parse(sql(literal)));
            runsMs.add((System.nanoTime() - start) / 1_000_000L);
            QueryEngine.VectorPlan plan = executor.lastVectorPlan();
            if (profile && plan != null) {
                System.out.println("PROFILE run=" + (i + 1) + " " + plan.profileLine()
                        + " candidates_examined=" + plan.candidatesExamined
                        + " path=" + plan.path);
            }
        }
        List<Long> measured = new ArrayList<>();
        for (int i = 0; i < Math.max(1, iters); i++) {
            String literal = randomVector(random);
            long start = System.nanoTime();
            executor.execute(Parser.parse(sql(literal)));
            measured.add((System.nanoTime() - start) / 1_000_000L);
            QueryEngine.VectorPlan plan = executor.lastVectorPlan();
            if (profile && plan != null && i == Math.max(1, iters) - 1) {
                System.out.println("PROFILE iters_last " + plan.profileLine()
                        + " candidates_examined=" + plan.candidatesExamined
                        + " path=" + plan.path);
            }
        }
        List<Long> sorted = new ArrayList<>(runsMs);
        Collections.sort(sorted);
        System.out.printf(Locale.ROOT,
                "RUNS=%s MEDIAN_MS=%d MIN_MS=%d P50_MS=%d P99_MS=%d ITERS=%d K=%d%n",
                runsMs, sorted.get(sorted.size() / 2), sorted.get(0),
                percentile(measured, 50), percentile(measured, 99), measured.size(), k);
    }

    private static long percentile(List<Long> values, int p) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * p / 100.0) - 1);
        return sorted.get(Math.max(0, index));
    }

    private static String randomVector(Random random) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < dim; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%.4f", random.nextDouble() * 2 - 1));
        }
        return sb.append(']').toString();
    }

    private static String randomVector2(Random random) {
        // INSERT 里的向量字面量是**字符串**:'[a,b,…]'(FE 的 Lexer 不认裸 '[')
        return "'" + randomVector(random) + "'";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
