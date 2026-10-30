package com.wangyu.tangdb.metrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * FE 侧指标登记表(SPEC-P8 FR-823 ~ FR-830)。
 *
 * <p>只用 JDK 自带类型:计数器是 {@link AtomicLong}/{@link LongAdder},延迟是
 * {@link LatencyWindow},Prometheus 文本格式手写。{@code metrics_enabled=false} 时
 * 构造出的是"空表"——所有 record 方法直接返回,{@link #snapshot()} 恒为空集,零采集、零计时。</p>
 *
 * <p>命名统一 {@code <子系统>_<对象>_<单位>}(FR-830):{@code fe_*} 来自 FE 自身,
 * {@code be_*} 来自 BE 心跳上报。</p>
 */
public final class Metrics {

    /** 关闭采集时的空实现。 */
    public static final Metrics DISABLED = new Metrics(false);

    private final boolean enabled;
    private final long startedAtMillis = System.currentTimeMillis();

    private final LatencyWindow latency = new LatencyWindow(60);

    private final LongAdder queries = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final LongAdder slowQueries = new LongAdder();
    private final LongAdder commits = new LongAdder();
    private final LongAdder commitMillis = new LongAdder();
    private final LongAdder conflicts = new LongAdder();
    private final LongAdder uniqueConflicts = new LongAdder();
    private final LongAdder vectorSearches = new LongAdder();
    private final LongAdder vectorCandidates = new LongAdder();
    private final Map<Integer, AtomicLong> errorCodes = new ConcurrentHashMap<>();

    private volatile IntSupplier activeSessions = () -> 0;
    private volatile Supplier<BeSnapshot> beSupplier = () -> BeSnapshot.EMPTY;
    private volatile Supplier<List<VectorRow>> vectorSupplier = () -> List.of();

    public Metrics(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setActiveSessionsSupplier(IntSupplier supplier) {
        if (supplier != null) {
            this.activeSessions = supplier;
        }
    }

    /** BE 指标来源(读最近一次心跳);BE 离线时返回 {@link BeSnapshot#EMPTY}。 */
    public void setBeSupplier(Supplier<BeSnapshot> supplier) {
        if (supplier != null) {
            this.beSupplier = supplier;
        }
    }

    /** 向量索引状态来源(逐索引段数 / 节点数 / 待重建段数)。 */
    public void setVectorSupplier(Supplier<List<VectorRow>> supplier) {
        if (supplier != null) {
            this.vectorSupplier = supplier;
        }
    }

    // ------------------------------------------------------------ 采集入口

    /** 一条语句的结果码与耗时(FR-822;失败语句也记)。 */
    public void recordStatement(long millis, int code) {
        if (!enabled) {
            return;
        }
        queries.increment();
        latency.record(millis);
        if (code != 0) {
            errors.increment();
            errorCodes.computeIfAbsent(code, k -> new AtomicLong()).incrementAndGet();
        }
    }

    public void recordCommit(long millis) {
        if (!enabled) {
            return;
        }
        commits.increment();
        commitMillis.add(Math.max(0, millis));
    }

    public void recordConflict() {
        if (!enabled) {
            return;
        }
        conflicts.increment();
    }

    public void recordUniqueConflict() {
        if (!enabled) {
            return;
        }
        uniqueConflicts.increment();
    }

    public void recordSlowQuery() {
        if (!enabled) {
            return;
        }
        slowQueries.increment();
    }

    /** 一次向量检索实际回表的候选数(FR-828)。 */
    public void recordVectorSearch(int candidates) {
        if (!enabled) {
            return;
        }
        vectorSearches.increment();
        if (candidates > 0) {
            vectorCandidates.add(candidates);
        }
    }

    public long slowQueryCount() {
        return enabled ? slowQueries.sum() : 0;
    }

    // ------------------------------------------------------------ 快照

    /** 按固定顺序产出全部指标点(Prometheus 与 SHOW METRICS 共用同一份数据)。 */
    public List<MetricPoint> snapshot() {
        List<MetricPoint> out = new ArrayList<>();
        if (!enabled) {
            return out;
        }
        BeSnapshot be = beSupplier.get();
        if (be == null) {
            be = BeSnapshot.EMPTY;
        }
        long uptime = Math.max(0, (System.currentTimeMillis() - startedAtMillis) / 1000);
        out.add(MetricPoint.gauge("fe_uptime_seconds", "FE 进程运行时长", uptime));
        out.add(MetricPoint.counter("fe_query_count", "语句执行总次数", queries.sum()));
        out.add(MetricPoint.gauge("fe_query_qps", "查询 QPS(1 分钟滑动窗口平均)",
                round3(latency.qps())));
        out.add(MetricPoint.gauge("fe_query_latency_p50_ms", "查询延迟 P50(1 分钟窗口)",
                latency.quantile(0.50)));
        out.add(MetricPoint.gauge("fe_query_latency_p95_ms", "查询延迟 P95(1 分钟窗口)",
                latency.quantile(0.95)));
        out.add(MetricPoint.gauge("fe_query_latency_p99_ms", "查询延迟 P99(1 分钟窗口)",
                latency.quantile(0.99)));
        out.add(MetricPoint.counter("fe_query_error_count", "失败语句总数", errors.sum()));
        Map<Integer, AtomicLong> codes = new LinkedHashMap<>();
        errorCodes.keySet().stream().sorted().forEach(code -> codes.put(code, errorCodes.get(code)));
        for (Map.Entry<Integer, AtomicLong> e : codes.entrySet()) {
            out.add(MetricPoint.counter("fe_query_error_code_count{code=\"" + e.getKey() + "\"}",
                    "按错误码统计的失败语句数", e.getValue().get()));
        }
        out.add(MetricPoint.gauge("fe_session_active_count", "活跃会话数", activeSessions.getAsInt()));
        out.add(MetricPoint.counter("fe_slow_query_count", "慢查询记录数", slowQueries.sum()));
        out.add(MetricPoint.counter("fe_txn_commit_count", "事务提交次数", commits.sum()));
        out.add(MetricPoint.counter("fe_txn_conflict_count", "写写冲突次数(1213)", conflicts.sum()));
        out.add(MetricPoint.counter("fe_txn_unique_conflict_count",
                "唯一冲突次数(1062)", uniqueConflicts.sum()));
        out.add(MetricPoint.gauge("fe_txn_commit_avg_ms", "事务平均提交耗时",
                commits.sum() == 0 ? 0 : round3((double) commitMillis.sum() / commits.sum())));

        out.add(MetricPoint.gauge("be_alive", "BE 是否在线(1 = 在线)", be.alive() ? 1 : 0));
        out.add(MetricPoint.gauge("be_memtable_bytes", "memtable 占用字节",
                be.memtableBytes()));
        out.add(MetricPoint.gauge("be_memtable_entries", "memtable 条目数", be.memtableCount()));
        out.add(MetricPoint.gauge("be_immutable_count", "不可变 memtable 队列长度",
                be.immutableCount()));
        out.add(MetricPoint.gauge("be_segment_count", "segment 文件数", be.segmentCount()));
        out.add(MetricPoint.gauge("be_segment_bytes", "segment 总字节", be.segmentBytes()));
        out.add(MetricPoint.gauge("be_wal_bytes", "WAL 总字节", be.walBytes()));
        out.add(MetricPoint.counter("be_compaction_count", "compaction 次数", be.compactionCount()));
        out.add(MetricPoint.gauge("be_compaction_avg_ms", "compaction 平均耗时",
                be.compactionCount() == 0 ? 0
                        : round3((double) be.compactionMillis() / be.compactionCount())));
        out.add(MetricPoint.counter("be_wal_fsync_count", "WAL fsync 次数", be.fsyncCount()));
        out.add(MetricPoint.gauge("be_wal_fsync_avg_ms", "WAL fsync 平均耗时",
                be.fsyncCount() == 0 ? 0 : round3((double) be.fsyncMillis() / be.fsyncCount())));
        out.add(MetricPoint.gauge("be_group_commit_avg_batch", "group commit 平均批量",
                round3(be.groupCommitAvgBatch())));
        out.add(MetricPoint.counter("be_put_count", "BE Put 次数", be.putCount()));
        out.add(MetricPoint.counter("be_get_count", "BE Get 次数", be.getCount()));
        out.add(MetricPoint.counter("be_scan_count", "BE Scan 次数", be.scanCount()));

        out.add(MetricPoint.gauge("be_vector_segment_count", "全部向量索引的图段数",
                be.vectorSegments()));
        out.add(MetricPoint.gauge("be_vector_node_count", "全部向量索引的节点数", be.vectorNodes()));
        out.add(MetricPoint.gauge("be_vector_pending_segment_count", "待重建图段数",
                be.vectorPendingSegments()));
        out.add(MetricPoint.counter("be_vector_search_count", "向量检索次数", be.vectorSearches()));
        out.add(MetricPoint.gauge("be_vector_avg_candidates", "向量检索平均候选数",
                round3(be.vectorAvgCandidates())));
        out.add(MetricPoint.counter("fe_vector_search_count", "FE 侧向量检索次数",
                vectorSearches.sum()));
        out.add(MetricPoint.gauge("fe_vector_avg_candidates", "FE 侧向量检索平均候选数",
                vectorSearches.sum() == 0 ? 0
                        : round3((double) vectorCandidates.sum() / vectorSearches.sum())));
        for (VectorRow row : vectorSupplier.get()) {
            out.add(MetricPoint.gauge(
                    "be_vector_index_segment_count{index=\"" + row.name() + "\"}",
                    "某向量索引的图段数", row.segments()));
            out.add(MetricPoint.gauge(
                    "be_vector_index_node_count{index=\"" + row.name() + "\"}",
                    "某向量索引的节点数", row.nodes()));
            out.add(MetricPoint.gauge(
                    "be_vector_index_pending_segment_count{index=\"" + row.name() + "\"}",
                    "某向量索引的待重建段数", row.pending()));
            out.add(MetricPoint.gauge(
                    "be_vector_index_state{index=\"" + row.name() + "\"}",
                    "某向量索引状态(0=DROPPED 1=BUILDING 2=READY)", row.state()));
        }
        return out;
    }

    /** Prometheus 文本格式(FR-823):{@code # HELP} / {@code # TYPE} + {@code name value}。 */
    public String prometheus() {
        StringBuilder sb = new StringBuilder(2048);
        String lastHelp = null;
        for (MetricPoint point : snapshot()) {
            // 带标签的同一族指标只写一次 HELP/TYPE
            String family = point.family();
            if (!family.equals(lastHelp)) {
                sb.append("# HELP ").append(family).append(' ').append(point.help()).append('\n');
                sb.append("# TYPE ").append(family).append(' ').append(point.type()).append('\n');
                lastHelp = family;
            }
            sb.append(point.name()).append(' ').append(point.valueText()).append('\n');
        }
        return sb.toString();
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /** 一条指标点。{@code name} 可能带 {@code {label="v"}} 后缀。 */
    public record MetricPoint(String name, String help, String type, double value) {

        public static MetricPoint gauge(String name, String help, double value) {
            return new MetricPoint(name, help, "gauge", value);
        }

        public static MetricPoint counter(String name, String help, double value) {
            return new MetricPoint(name, help, "counter", value);
        }

        /** 指标族名(去掉标签部分),用于 HELP/TYPE 去重。 */
        public String family() {
            int brace = name.indexOf('{');
            return brace < 0 ? name : name.substring(0, brace);
        }

        /** 数值文本:整数不带小数点,小数保留 3 位。 */
        public String valueText() {
            if (value == Math.rint(value) && Math.abs(value) < 1e15) {
                return String.valueOf((long) value);
            }
            return String.format(java.util.Locale.ROOT, "%.3f", value);
        }
    }

    /** BE 心跳带来的指标快照。 */
    public record BeSnapshot(boolean alive, long memtableBytes, long memtableCount,
                             long immutableCount, long segmentCount, long segmentBytes,
                             long walBytes, long compactionCount, long compactionMillis,
                             long fsyncCount, long fsyncMillis, double groupCommitAvgBatch,
                             long putCount, long getCount, long scanCount,
                             long vectorSegments, long vectorNodes, long vectorPendingSegments,
                             long vectorSearches, double vectorAvgCandidates) {

        public static final BeSnapshot EMPTY = new BeSnapshot(false, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** 单个向量索引的状态行。 */
    public record VectorRow(String name, long segments, long nodes, long pending, int state) {
    }
}
