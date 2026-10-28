package com.wangyu.tangdb.planner;

/**
 * 优化器配置(SPEC-P6 §5.3 的 11 项;FR-628 / FR-632 / FR-636 / FR-640 / FR-641 / FR-659)。
 *
 * <p>字段与 {@code config/fe.conf} 的键一一对应;默认值就是规约 §5.3 写死的默认值。
 * 全部集中在这一个不可变对象里,便于 Executor / QueryEngine / StatsService 共用同一份口径。</p>
 */
public record OptimizerConfig(
        boolean enabled,
        int joinEnumMaxTables,
        int hashAggregateDistinctMax,
        int topnLimitRatioPercent,
        int subqueryCacheMaxEntries,
        int statsStaleRowChangePercent,
        int analyzeFullScanMaxRows,
        int analyzeSampleStride,
        int analyzeHistogramBuckets,
        int analyzeExactDistinctMaxRows,
        int analyzeHllPrecision) {

    public OptimizerConfig {
        if (joinEnumMaxTables < 1) {
            joinEnumMaxTables = 4;
        }
        if (hashAggregateDistinctMax < 1) {
            hashAggregateDistinctMax = 10_000;
        }
        if (topnLimitRatioPercent < 1) {
            topnLimitRatioPercent = 30;
        }
        if (subqueryCacheMaxEntries < 1) {
            subqueryCacheMaxEntries = 4096;
        }
        if (statsStaleRowChangePercent < 1) {
            statsStaleRowChangePercent = 20;
        }
        if (analyzeFullScanMaxRows < 1) {
            analyzeFullScanMaxRows = 200_000;
        }
        if (analyzeSampleStride < 1) {
            analyzeSampleStride = 20;
        }
        if (analyzeHistogramBuckets < 1) {
            analyzeHistogramBuckets = 64;
        }
        if (analyzeExactDistinctMaxRows < 1) {
            analyzeExactDistinctMaxRows = 100_000;
        }
        if (analyzeHllPrecision < 4 || analyzeHllPrecision > 18) {
            analyzeHllPrecision = 14;
        }
    }

    /** 规约 §5.3 的默认值。 */
    public static OptimizerConfig defaults() {
        return new OptimizerConfig(true, 4, 10_000, 30, 4096, 20,
                200_000, 20, 64, 100_000, 14);
    }

    /** 只关掉 CBO(验收对拍 / 兜底),其它分析参数保持默认。 */
    public static OptimizerConfig disabled() {
        return new OptimizerConfig(false, 4, 10_000, 30, 4096, 20,
                200_000, 20, 64, 100_000, 14);
    }
}
