package com.wangyu.tangdb.stats;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.catalog.SystemTables;
import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.planner.OptimizerConfig;
import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.DataType;
import com.wangyu.tangdb.storage.KeyCodec;
import com.wangyu.tangdb.storage.KeyValue;
import com.wangyu.tangdb.storage.KvStore;
import com.wangyu.tangdb.storage.RowCodec;
import com.wangyu.tangdb.storage.TableMeta;
import com.wangyu.tangdb.storage.Values;
import com.wangyu.tangdb.txn.ReadView;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 统计信息服务(SPEC-P6 A 组 / FR-615 ~ FR-625)。
 *
 * <ul>
 *   <li>采集:FE 侧走统一 {@code Scan}(FR-620),不新增 BE 算子(K125);</li>
 *   <li>持久化:一次 ANALYZE 的全部写入是**一个批记录**(FR-621),走与用户数据相同的
 *       {@code KvStore.writeBatch} 通道;</li>
 *   <li>过期判定:分析之后的行数变化超过阈值即 stale(FR-623),写路径通过
 *       {@link #recordRowDelta} 记账(内存增量,重启后从 0 起算);</li>
 *   <li>缺失/过期时给出默认估算(FR-625),保证"没 ANALYZE 也能跑"。</li>
 * </ul>
 */
public final class StatsService {

    private static final Logger LOG = Logger.getLogger(StatsService.class.getName());

    /** FR-625:没有统计时的默认行数。 */
    public static final long DEFAULT_ROW_COUNT = 10_000;
    /** FR-625:没有统计时的默认 distinct。 */
    public static final long DEFAULT_DISTINCT = 100;
    /** FR-625:没有直方图时范围选择率取 1/3。 */
    public static final double DEFAULT_RANGE_SELECTIVITY = 1.0 / 3.0;

    private static final int SCAN_BATCH_ROWS = 4096;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final KvStore kv;
    private final Catalog catalog;
    private final OptimizerConfig config;

    private final Map<Long, TableStats> tableCache = new ConcurrentHashMap<>();
    private final Map<Long, List<ColumnStats>> columnCache = new ConcurrentHashMap<>();
    /** 分析之后的行数变化(内存记账;重启从 0 起算,不影响已落库统计)。 */
    private final Map<Long, Long> rowDelta = new ConcurrentHashMap<>();

    public StatsService(KvStore kv, Catalog catalog, OptimizerConfig config) {
        this.kv = kv;
        this.catalog = catalog;
        this.config = config == null ? OptimizerConfig.defaults() : config;
    }

    public OptimizerConfig config() {
        return config;
    }

    /** 一次 ANALYZE 的结果(供 SHOW STATS / 消息与日志复用)。 */
    public record AnalyzeResult(TableStats table, List<ColumnStats> columns,
                                long durationMillis, long scannedRows, boolean sampled) {
    }

    // ------------------------------------------------------------ 读

    /** 表级统计;未分析返回 null。stale 按当前记账结果实时计算(FR-623)。 */
    public TableStats tableStats(TableMeta table) {
        if (table == null) {
            return null;
        }
        TableStats cached = tableCache.get(table.id());
        if (cached == null) {
            cached = loadTableStats(table);
            if (cached == null) {
                return null;
            }
            tableCache.put(table.id(), cached);
        }
        if (cached.stale()) {
            return cached;
        }
        return isStale(cached) ? cached.withStale(true) : cached;
    }

    private TableStats loadTableStats(TableMeta table) {
        String db = table.dbName() == null ? SystemTables.DEFAULT_DATABASE : table.dbName();
        byte[] raw = kv.get(SystemTables.tableStatsKey(db, table.id()));
        if (raw == null) {
            return null;
        }
        RowCodec.Decoded decoded;
        try {
            decoded = RowCodec.decode(SystemTables.TABLE_STATS, raw);
        } catch (RuntimeException e) {
            LOG.warning("统计信息损坏:sys_table_stats 表 ID " + table.id() + " 无法解析("
                    + e.getMessage() + "),按未分析处理");
            return null;
        }
        if (decoded.tombstone()) {
            return null;
        }
        Object[] v = decoded.values();
        return new TableStats(table.id(), (String) v[0], (String) v[1],
                ((Number) v[2]).longValue(), ((Number) v[3]).longValue(),
                ((Number) v[4]).longValue(), ((Number) v[5]).intValue(),
                (String) v[6], Boolean.TRUE.equals(v[7]),
                ((Number) v[8]).longValue(), ((Number) v[9]).longValue());
    }

    /** 某表全部列统计(按 ordinal 升序);未分析返回空表。 */
    public List<ColumnStats> columnStats(long tableId) {
        List<ColumnStats> cached = columnCache.get(tableId);
        if (cached != null) {
            return cached;
        }
        List<KeyValue> rows = kv.scan(KeyCodec.tableRangeStart(SystemTables.SYS_COLUMN_STATS),
                KeyCodec.tableRangeEnd(SystemTables.SYS_COLUMN_STATS), 0);
        Map<Integer, ColumnStats> byOrdinal = new LinkedHashMap<>();
        for (KeyValue row : rows) {
            try {
                RowCodec.Decoded decoded = RowCodec.decode(SystemTables.COLUMN_STATS, row.value());
                if (decoded.tombstone()) {
                    continue;
                }
                Object[] v = decoded.values();
                if (((Number) v[0]).longValue() != tableId) {
                    continue;
                }
                String histogram = v[8] instanceof byte[] raw
                        ? new String(raw, StandardCharsets.UTF_8) : (String) v[8];
                byOrdinal.put(((Number) v[1]).intValue(), new ColumnStats(tableId,
                        ((Number) v[1]).intValue(), (String) v[2],
                        ((Number) v[3]).longValue(), ((Number) v[4]).longValue(),
                        Boolean.TRUE.equals(v[5]), (String) v[6], (String) v[7], histogram));
            } catch (RuntimeException e) {
                LOG.warning("统计信息损坏:sys_column_stats 有一行无法解析(" + e.getMessage() + ")");
            }
        }
        List<ColumnStats> out = new ArrayList<>(byOrdinal.values());
        out.sort((a, b) -> Integer.compare(a.ordinal(), b.ordinal()));
        columnCache.put(tableId, out);
        return out;
    }

    /** 某列的统计;没有返回 null。 */
    public ColumnStats columnStats(long tableId, String columnName) {
        if (columnName == null) {
            return null;
        }
        for (ColumnStats stats : columnStats(tableId)) {
            if (stats.columnName().equalsIgnoreCase(columnName)) {
                return stats;
            }
        }
        return null;
    }

    /** 某表某列序号的统计;没有返回 null。 */
    public ColumnStats columnStatsByOrdinal(long tableId, int ordinal) {
        for (ColumnStats stats : columnStats(tableId)) {
            if (stats.ordinal() == ordinal) {
                return stats;
            }
        }
        return null;
    }

    /** 估算行数:有统计用统计,没有用默认(FR-625)。 */
    public long estimatedRowCount(TableMeta table) {
        TableStats stats = tableStats(table);
        if (stats == null) {
            return DEFAULT_ROW_COUNT;
        }
        return Math.max(0, stats.rowCount());
    }

    /** 过期判定:分析后行数变化超过阈值(FR-623)。 */
    public boolean isStale(TableStats stats) {
        if (stats == null) {
            return true;
        }
        if (stats.stale()) {
            return true;
        }
        long delta = Math.abs(rowDelta.getOrDefault(stats.tableId(), 0L));
        if (delta == 0) {
            return false;
        }
        long base = Math.max(1, stats.rowCount());
        return delta * 100 / base > config.statsStaleRowChangePercent();
    }

    // ------------------------------------------------------------ 写路径记账

    /** 行数变化记账(+插入 / -删除);UPDATE 不调用(行数不变)。 */
    public void recordRowDelta(long tableId, long delta) {
        if (delta == 0) {
            return;
        }
        rowDelta.merge(tableId, delta, Long::sum);
    }

    public void invalidate(long tableId) {
        tableCache.remove(tableId);
        columnCache.remove(tableId);
    }

    public void invalidateAll() {
        tableCache.clear();
        columnCache.clear();
    }

    // ------------------------------------------------------------ ANALYZE(FR-615 ~ FR-621)

    /**
     * 分析一张表。
     *
     * @param table    目标表
     * @param targets  要分析的列(全列 / 指定列)
     * @param view     统一扫描通道上的读视图(与用户数据同快照)
     * @param snapshot 分析时刻的提交序号(写入 {@code analyzed_commit_ts})
     */
    public AnalyzeResult analyze(TableMeta table, List<Column> targets, ReadView view,
                                 long snapshot) {
        long started = System.nanoTime();
        // ① 第一遍:精确行数与总字节(K126 用它决定全量还是抽样)
        long[] counted = countRows(table, view);
        long rowCount = counted[0];
        long totalBytes = counted[1];

        boolean sampled = rowCount > config.analyzeFullScanMaxRows();
        int stride = sampled ? config.analyzeSampleStride() : 1;
        long estimatedSampleRows = sampled ? rowCount / stride + 1 : rowCount;
        boolean exactDistinct = estimatedSampleRows <= config.analyzeExactDistinctMaxRows();

        // ② 第二遍:按(全部 / 每 stride 个 batch 取 1 个)采集列统计
        List<Accumulator> accumulators = new ArrayList<>(targets.size());
        for (Column column : targets) {
            accumulators.add(new Accumulator(column, exactDistinct, config));
        }
        long scanned = collect(table, view, accumulators, stride);

        long millis = (System.nanoTime() - started) / 1_000_000;
        String lastAnalyze = TIME_FMT.format(LocalDateTime.now());
        int permille = sampled ? Math.max(1, 1000 / stride) : 1000;

        List<ColumnStats> columns = new ArrayList<>(accumulators.size());
        List<KeyValue> batch = new ArrayList<>(accumulators.size() + 1);
        batch.add(new KeyValue(SystemTables.tableStatsKey(dbOf(table), table.id()),
                SystemTables.encodeTableStatsRow(dbOf(table), table.name(), rowCount, totalBytes,
                        snapshot, permille, lastAnalyze, false, millis, scanned)));
        for (Accumulator accumulator : accumulators) {
            ColumnStats stats = accumulator.finish(table.id(), rowCount, scanned, config);
            columns.add(stats);
            batch.add(new KeyValue(SystemTables.columnStatsKey(table.id(), stats.ordinal()),
                    SystemTables.encodeColumnStatsRow(stats.tableId(), stats.ordinal(),
                            stats.columnName(), stats.nullCount(), stats.distinctCount(),
                            stats.distinctIsApprox(), stats.minValue(), stats.maxValue(),
                            stats.histogram())));
        }
        // ③ 全部列统计 + 表统计 = 一个批记录(FR-621,崩溃后不留半套)
        kv.writeBatch(batch);

        TableStats tableStats = new TableStats(table.id(), dbOf(table), table.name(), rowCount,
                totalBytes, snapshot, permille, lastAnalyze, false, millis, scanned);
        invalidate(table.id());
        tableCache.put(table.id(), tableStats);
        columnCache.put(table.id(), List.copyOf(columns));
        rowDelta.remove(table.id());
        return new AnalyzeResult(tableStats, columns, millis, scanned, sampled);
    }

    private static String dbOf(TableMeta table) {
        return table.dbName() == null ? SystemTables.DEFAULT_DATABASE : table.dbName();
    }

    private long[] countRows(TableMeta table, ReadView view) {
        long rows = 0;
        long bytes = 0;
        byte[] start = KeyCodec.tableRangeStart(table.id());
        byte[] end = KeyCodec.tableRangeEnd(table.id());
        while (true) {
            List<KeyValue> batch = view.scan(start, end, SCAN_BATCH_ROWS);
            if (batch.isEmpty()) {
                break;
            }
            for (KeyValue row : batch) {
                if (KeyCodec.indexId(row.key()) != KeyCodec.INDEX_ID_PRIMARY) {
                    continue;
                }
                if (row.value() != null && row.value().length > 0
                        && row.value()[0] == RowCodec.FORMAT_TOMBSTONE) {
                    continue;
                }
                rows++;
                bytes += row.value() == null ? 0 : row.value().length;
            }
            start = after(batch.get(batch.size() - 1).key());
        }
        return new long[]{rows, bytes};
    }

    private long collect(TableMeta table, ReadView view, List<Accumulator> accumulators, int stride) {
        long scanned = 0;
        int batchIndex = 0;
        byte[] start = KeyCodec.tableRangeStart(table.id());
        byte[] end = KeyCodec.tableRangeEnd(table.id());
        while (true) {
            List<KeyValue> batch = view.scan(start, end, SCAN_BATCH_ROWS);
            if (batch.isEmpty()) {
                break;
            }
            boolean take = batchIndex % Math.max(1, stride) == 0;
            batchIndex++;
            for (KeyValue row : batch) {
                if (!take) {
                    continue;
                }
                if (KeyCodec.indexId(row.key()) != KeyCodec.INDEX_ID_PRIMARY) {
                    continue;
                }
                byte[] raw = row.value();
                if (raw != null && raw.length > 0 && raw[0] == RowCodec.FORMAT_TOMBSTONE) {
                    continue;
                }
                RowCodec.Decoded decoded = RowCodec.decode(table, raw);
                if (decoded.tombstone()) {
                    continue;
                }
                scanned++;
                for (Accumulator accumulator : accumulators) {
                    accumulator.add(decoded.values());
                }
            }
            start = after(batch.get(batch.size() - 1).key());
        }
        return scanned;
    }

    /** 下一批的扫描起点:跳过该用户键的全部版本。 */
    private static byte[] after(byte[] userKey) {
        return KeyCodec.concat(userKey, KeyCodec.afterAllVersions());
    }

    /** 单列采集器:null / distinct / min / max / 直方图原始值。 */
    private static final class Accumulator {
        private final Column column;
        private final boolean exactDistinct;
        private final Set<Object> distinctSet;
        private final HyperLogLog hll;
        private final List<Object> values;
        private final boolean buildHistogram;
        private long nullCount;
        private Object min;
        private Object max;

        Accumulator(Column column, boolean exactDistinct, OptimizerConfig config) {
            this.column = column;
            this.exactDistinct = exactDistinct;
            this.distinctSet = exactDistinct ? new HashSet<>() : null;
            this.hll = exactDistinct ? null : new HyperLogLog(config.analyzeHllPrecision());
            this.buildHistogram = histogramType(column.type());
            this.values = buildHistogram ? new ArrayList<>() : null;
        }

        /** FR-618:INT / BIGINT / DOUBLE / DATE / VARCHAR 建直方图;BOOLEAN / JSON 不建。 */
        private static boolean histogramType(DataType type) {
            return type == DataType.INT || type == DataType.BIGINT || type == DataType.DOUBLE
                    || type == DataType.DATE || type == DataType.VARCHAR;
        }

        void add(Object[] row) {
            Object value = row[column.ordinal()];
            if (value == null) {
                nullCount++;
                return;
            }
            if (distinctSet != null) {
                distinctSet.add(distinctKey(value));
            } else {
                hll.add(value);
            }
            if (column.type() != DataType.JSON) {
                if (min == null || Values.compare(value, column, min) < 0) {
                    min = value;
                }
                if (max == null || Values.compare(value, column, max) > 0) {
                    max = value;
                }
            }
            if (buildHistogram) {
                values.add(value);
            }
        }

        private static Object distinctKey(Object value) {
            if (value instanceof Integer i) {
                return (long) i;
            }
            if (value instanceof Float f) {
                return f.doubleValue();
            }
            if (value instanceof byte[] raw) {
                return "json:" + java.util.HexFormat.of().formatHex(raw);
            }
            return value;
        }

        ColumnStats finish(long tableId, long rowCount, long scanned, OptimizerConfig config) {
            long distinct = distinctSet != null
                    ? distinctSet.size() : (hll == null ? 0 : hll.estimate());
            boolean approx = !exactDistinct;
            // 抽样时把 null_count / 直方图桶计数按真实行数缩放(比例不变,口径与 row_count 一致)。
            double scale = scanned <= 0 ? 1.0 : (double) rowCount / scanned;
            long scaledNulls = scanned == 0 ? 0
                    : (scanned >= rowCount ? nullCount : Math.round(nullCount * scale));

            String histogramJson;
            if (!buildHistogram) {
                histogramJson = Histogram.empty(config.analyzeHistogramBuckets()).toJson();
            } else if (rowCount == 0 || values.isEmpty()) {
                histogramJson = Histogram.empty(config.analyzeHistogramBuckets()).toJson();
            } else {
                List<Object> sorted = new ArrayList<>(values);
                sorted.sort((a, b) -> Values.compare(a, column, b));
                Histogram histogram = Histogram.build(sorted, config.analyzeHistogramBuckets());
                if (scale != 1.0) {
                    histogram = scale(histogram, scale, rowCount);
                }
                histogramJson = histogram.toJson();
            }
            return new ColumnStats(tableId, column.ordinal(),
                    column.name(), scaledNulls, distinct, approx,
                    Histogram.boundText(min), Histogram.boundText(max), histogramJson);
        }

        private Histogram scale(Histogram histogram, double scale, long rowCount) {
            List<Long> counts = new ArrayList<>(histogram.counts().size());
            long sum = 0;
            for (Long count : histogram.counts()) {
                long scaled = Math.max(0, Math.round(count * scale));
                counts.add(scaled);
                sum += scaled;
            }
            // 取整误差补到最后一个桶,让桶计数之和等于 row_count。
            if (!counts.isEmpty() && sum != rowCount) {
                int last = counts.size() - 1;
                counts.set(last, Math.max(0, counts.get(last) + (rowCount - sum)));
            }
            return Histogram.rebuild(histogram.buckets(), histogram.bounds(), counts);
        }
    }

    // ------------------------------------------------------------ SHOW STATS(FR-622 / FR-672 / FR-675)

    /** SHOW STATS 的固定列。 */
    public static final List<String> SHOW_STATS_COLUMNS = List.of(
            "db_name", "table_name", "column_name", "row_count", "total_bytes",
            "sample_permille", "approx", "last_analyze", "analyze_millis", "scanned_rows",
            "stale", "null_count", "distinct_count", "min_value", "max_value", "histogram");

    /**
     * SHOW STATS [FROM t] 的行。
     *
     * @param table 指定表时只看这张表;null 表示当前库全部表
     * @param db    限定库(为 null 时用 {@code default})
     */
    public List<Object[]> showStats(TableMeta table, String db) {
        List<TableMeta> tables = new ArrayList<>();
        if (table != null) {
            tables.add(table);
        } else {
            tables.addAll(catalog.tables(db == null ? SystemTables.DEFAULT_DATABASE : db));
        }
        List<Object[]> rows = new ArrayList<>();
        for (TableMeta target : tables) {
            TableStats stats = tableStats(target);
            if (stats == null) {
                continue; // 没分析过就不出现在 SHOW STATS 里(SC-369:不残留已 DROP 的表)
            }
            List<ColumnStats> columns = columnStats(target.id());
            if (columns.isEmpty()) {
                rows.add(new Object[]{stats.dbName(), stats.tableName(), null,
                        stats.rowCount(), stats.totalBytes(), stats.samplePermille(), null,
                        stats.lastAnalyze(), stats.analyzeMillis(), stats.scannedRows(),
                        stats.stale(), null, null, null, null, null});
                continue;
            }
            for (ColumnStats column : columns) {
                rows.add(new Object[]{stats.dbName(), stats.tableName(), column.columnName(),
                        stats.rowCount(), stats.totalBytes(), stats.samplePermille(),
                        column.distinctIsApprox(), stats.lastAnalyze(), stats.analyzeMillis(),
                        stats.scannedRows(), stats.stale(), column.nullCount(),
                        column.distinctCount(), column.minValue(), column.maxValue(),
                        column.histogram()});
            }
        }
        return rows;
    }

    /** SHOW STATS FROM 的列(带说明的自有诊断语句,K120)。 */
    public static List<String> showStatsColumns() {
        return SHOW_STATS_COLUMNS;
    }

    /** 近似 distinct 的误差界(FR-619 / SHOW STATS 的 approx 语义)。 */
    public double distinctErrorBound() {
        return 1.04 / Math.sqrt(1 << config.analyzeHllPrecision());
    }

    /** 供 ANALYZE 报错用:表不存在(1146)/ 列不存在(1054)。 */
    public static SqlError noSuchTable(String table) {
        return new SqlError(SqlError.ER_NO_SUCH_TABLE, "42S02", "表 " + table + " 不存在");
    }
}
