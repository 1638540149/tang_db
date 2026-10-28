package com.wangyu.tangdb.stats;

/**
 * 列级统计(SPEC-P6 FR-617 / §5.1 表 ID 8 {@code sys_column_stats})。
 *
 * <p>列顺序:{@code table_id, ordinal, column_name, null_count, distinct_count,
 * distinct_is_approx, min_value, max_value, histogram}。</p>
 *
 * <p>{@code minValue} / {@code maxValue} 是文本形态(§5.2);{@code histogram} 是
 * {@link Histogram} 的 JSON 文本。{@code BOOLEAN} / {@code JSON} 列只有
 * {@code null_count} 与 {@code distinct_count},直方图是空数组(FR-618)。</p>
 */
public record ColumnStats(long tableId, int ordinal, String columnName, long nullCount,
                          long distinctCount, boolean distinctIsApprox, String minValue,
                          String maxValue, String histogram) {
}
