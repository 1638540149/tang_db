package com.wangyu.tangdb.metrics;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.execute.SqlEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 逐向量索引的指标来源(SPEC-P8 FR-828)。
 *
 * <p>复用既有的 {@code SHOW VECTOR INDEXES} 执行路径(不新增第二个入口),结果缓存 2 秒,
 * 避免 {@code /metrics} 被高频抓取时每次都向 BE 要一次段级状态。</p>
 */
public final class VectorMetricsSupplier implements Supplier<List<Metrics.VectorRow>> {

    private static final long CACHE_MILLIS = 2000;

    private final SqlEngine engine;
    private final Catalog catalog;
    private volatile long expiresAtMillis;
    private volatile List<Metrics.VectorRow> cache = List.of();

    public VectorMetricsSupplier(SqlEngine engine, Catalog catalog) {
        this.engine = engine;
        this.catalog = catalog;
    }

    @Override
    public List<Metrics.VectorRow> get() {
        long now = System.currentTimeMillis();
        if (now < expiresAtMillis) {
            return cache;
        }
        synchronized (this) {
            if (now < expiresAtMillis) {
                return cache;
            }
            cache = refresh();
            expiresAtMillis = now + CACHE_MILLIS;
            return cache;
        }
    }

    private List<Metrics.VectorRow> refresh() {
        if (catalog.allVectorIndexes().isEmpty()) {
            return List.of();
        }
        List<Metrics.VectorRow> rows = new ArrayList<>();
        try {
            Result result = engine.execute(null, "SHOW VECTOR INDEXES");
            if (!(result instanceof Result.Table table)) {
                return List.of();
            }
            List<String> columns = table.columns();
            int nameIdx = columns.indexOf("index_name");
            int segIdx = columns.indexOf("segment_count");
            int nodeIdx = columns.indexOf("node_count");
            int pendingIdx = columns.indexOf("pending_segments");
            int stateIdx = columns.indexOf("state");
            for (Object[] row : table.rows()) {
                String name = nameIdx >= 0 && nameIdx < row.length
                        ? String.valueOf(row[nameIdx]) : "?";
                rows.add(new Metrics.VectorRow(name,
                        longAt(row, segIdx), longAt(row, nodeIdx), longAt(row, pendingIdx),
                        stateCode(stateIdx >= 0 && stateIdx < row.length ? row[stateIdx] : null)));
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return rows;
    }

    private static long longAt(Object[] row, int index) {
        if (index < 0 || index >= row.length || !(row[index] instanceof Number number)) {
            return 0;
        }
        return number.longValue();
    }

    private static int stateCode(Object raw) {
        String text = raw == null ? "" : String.valueOf(raw).toUpperCase(java.util.Locale.ROOT);
        return switch (text) {
            case "READY" -> 2;
            case "BUILDING" -> 1;
            default -> 0;
        };
    }
}
