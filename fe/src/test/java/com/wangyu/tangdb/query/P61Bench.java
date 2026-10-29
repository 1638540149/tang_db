package com.wangyu.tangdb.query;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.client.TangDbClient;
import com.wangyu.tangdb.execute.Executor;
import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.parser.Parser;
import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.KeyCodec;
import com.wangyu.tangdb.storage.KeyValue;
import com.wangyu.tangdb.storage.ProjectedCodec;
import com.wangyu.tangdb.storage.ScanOptions;
import com.wangyu.tangdb.storage.TableMeta;
import com.wangyu.tangdb.txn.ReadViews;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * SPEC-P6.1 的实测工具(不进生产路径):直连 BE,在同一 JVM 内计时 / 统计载荷字节。
 *
 * <pre>
 * SQL 计时:   --mode sql --port 9940 --parallelism 8 --pushdown true \
 *             --sql "Q1=SELECT COUNT(*) FROM t"
 * 投影对比:   --mode projection --port 9940 --table wide --columns c1,c2 --limit 10
 * 输出:
 *   Q1=123.4 rows=200000 bytes=12345678
 *   FULL_BYTES=.. PROJ_BYTES=.. RATIO=0.12 FULL_MS=.. PROJ_MS=.. LIMIT_ROWS=10 LIMIT_MS=..
 * </pre>
 */
public final class P61Bench {

    public static void main(String[] args) {
        int port = 9100;
        String host = "127.0.0.1";
        String mode = "sql";
        int parallelism = 1;
        boolean pushdown = false;
        String table = "";
        String columns = "";
        int limit = 10;
        List<String> specs = new java.util.ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--host" -> host = args[++i];
                case "--mode" -> mode = args[++i];
                case "--parallelism" -> parallelism = Integer.parseInt(args[++i]);
                case "--pushdown" -> pushdown = Boolean.parseBoolean(args[++i]);
                case "--table" -> table = args[++i];
                case "--columns" -> columns = args[++i];
                case "--limit" -> limit = Integer.parseInt(args[++i]);
                case "--sql" -> specs.add(args[++i]);
                default -> {
                    // 忽略未知参数
                }
            }
        }

        try (TangDbClient client = new TangDbClient(host, port)) {
            if ("projection".equals(mode)) {
                try {
                    projection(client, table, columns, limit);
                } catch (com.wangyu.tangdb.client.ClientException e) {
                    System.out.println("PROJECTION=ERROR " + e.getMessage());
                }
                return;
            }
            Catalog catalog = Catalog.open(client);
            Executor executor = new Executor(client, catalog)
                    .withExecution(parallelism, pushdown, 4096);
            for (String spec : specs) {
                int eq = spec.indexOf('=');
                String name = eq < 0 ? spec : spec.substring(0, eq);
                String sql = eq < 0 ? spec : spec.substring(eq + 1);
                try {
                    run(executor, sql);
                    long[] times = new long[3];
                    long rows = 0;
                    long bytes = 0;
                    for (int i = 0; i < times.length; i++) {
                        client.resetScanPayloadBytes();
                        long start = System.nanoTime();
                        Result result = run(executor, sql);
                        times[i] = (System.nanoTime() - start) / 1_000_000;
                        rows = rowCount(result);
                        bytes = client.scanPayloadBytes();
                    }
                    Arrays.sort(times);
                    System.out.println(String.format(Locale.ROOT,
                            "%s=%d rows=%d bytes=%d", name, times[1], rows, bytes));
                } catch (RuntimeException e) {
                    System.out.println(name + "=ERROR " + e.getMessage());
                }
            }
        }
    }

    /** 20 列宽表的"全行 vs 列投影"载荷字节对比 + LIMIT 提前终止(SC-405 / SC-406)。 */
    private static void projection(TangDbClient client, String tableName, String columnList,
                                   int limit) throws com.wangyu.tangdb.client.ClientException {
        Catalog catalog = Catalog.open(client);
        TableMeta table = catalog.find(null, tableName);
        if (table == null) {
            System.out.println("PROJECTION=ERROR 表不存在: " + tableName);
            return;
        }
        byte[] start = KeyCodec.tableRangeStart(table.id());
        byte[] end = KeyCodec.tableRangeEnd(table.id());

        client.resetScanPayloadBytes();
        long fullStart = System.nanoTime();
        List<KeyValue> full = client.scanRaw(start, end, 0);
        long fullMs = (System.nanoTime() - fullStart) / 1_000_000;
        long fullBytes = client.scanPayloadBytes();

        boolean[] needed = new boolean[table.columnCount()];
        needed[0] = true;
        int selected = 1;
        for (String name : columnList.split(",")) {
            int index = table.columnIndex(name.trim());
            if (index >= 0) {
                needed[index] = true;
                selected++;
            }
        }
        ProjectedCodec.Plan plan = ProjectedCodec.plan(table, needed);
        if (plan == null) {
            System.out.println("PROJECTION=ERROR 列数超过 32,无法投影");
            return;
        }
        ScanOptions options = new ScanOptions(4096, plan.mask(), plan.columnSlots(),
                plan.rowFrame(), null, true);

        client.resetScanPayloadBytes();
        long projStart = System.nanoTime();
        List<KeyValue> projected = ReadViews.latest(client)
                .scanProjected(start, end, 0, options);
        long projMs = (System.nanoTime() - projStart) / 1_000_000;
        long projBytes = client.scanPayloadBytes();

        client.resetScanPayloadBytes();
        long limitStart = System.nanoTime();
        List<KeyValue> limited = ReadViews.latest(client).scanProjected(start, end, limit, options);
        long limitMs = (System.nanoTime() - limitStart) / 1_000_000;

        double ratio = fullBytes == 0 ? 0 : (double) projBytes / fullBytes;
        System.out.println(String.format(Locale.ROOT,
                "FULL_ROWS=%d FULL_BYTES=%d PROJ_ROWS=%d PROJ_BYTES=%d RATIO=%.4f "
                        + "FULL_MS=%d PROJ_MS=%d LIMIT_ROWS=%d LIMIT_MS=%d SELECTED=%d TOTAL=%d",
                full.size(), fullBytes, projected.size(), projBytes, ratio,
                fullMs, projMs, limited.size(), limitMs, selected, table.columnCount()));
    }

    private static Result run(Executor executor, String sql) {
        return executor.execute(Parser.parse(sql));
    }

    private static long rowCount(Result result) {
        if (result instanceof Result.Table table) {
            return table.rows().size();
        }
        if (result instanceof Result.Message message) {
            return message.affectedRows();
        }
        return 0;
    }

    /** 供脚本核对列定义(不用反射)。 */
    static List<Column> columns(TableMeta table) {
        return table.columns();
    }
}
