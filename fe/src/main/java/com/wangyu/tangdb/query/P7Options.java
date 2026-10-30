package com.wangyu.tangdb.query;

/**
 * SPEC-P7 §5.4 的向量检索 / 扫描并行阈值配置。
 *
 * <p>默认值就是规约 K159 / K167 / FR-784 写死的门限:<b>门限不改</b>(SC-433 / FR-790),
 * 这里只是把它们搬进代码,方便启动时从 {@code config/fe.conf} 覆盖。</p>
 */
public record P7Options(int vectorMetric,
                        int vectorM,
                        int vectorEfConstruction,
                        int vectorEfSearch,
                        int vectorEfMax,
                        int vectorBruteforceRows,
                        int exactFallbackRows,
                        double allowListMaxRatio,
                        int parallelMinRows,
                        String vectorPath,
                        boolean vectorExact,
                        boolean vectorProfile) {

    public static final String PATH_AUTO = "auto";
    public static final String PATH_A = "A";
    public static final String PATH_B = "B";

    public P7Options {
        if (vectorMetric < 0 || vectorMetric > 2) {
            vectorMetric = 0;
        }
        if (vectorM <= 0) {
            vectorM = 16;
        }
        if (vectorEfConstruction <= 0) {
            vectorEfConstruction = 200;
        }
        if (vectorEfSearch <= 0) {
            vectorEfSearch = 64;
        }
        if (vectorEfMax < vectorEfSearch) {
            vectorEfMax = Math.max(512, vectorEfSearch);
        }
        if (vectorBruteforceRows <= 0) {
            vectorBruteforceRows = 10_000;
        }
        if (exactFallbackRows <= 0) {
            exactFallbackRows = 10_000;
        }
        if (allowListMaxRatio <= 0 || allowListMaxRatio > 1) {
            allowListMaxRatio = 0.3;
        }
        if (parallelMinRows < 0) {
            parallelMinRows = 50_000;
        }
        if (vectorPath == null || vectorPath.isBlank()) {
            vectorPath = PATH_AUTO;
        } else {
            vectorPath = vectorPath.trim();
            if (!vectorPath.equalsIgnoreCase(PATH_A) && !vectorPath.equalsIgnoreCase(PATH_B)
                    && !vectorPath.equalsIgnoreCase(PATH_AUTO)) {
                vectorPath = PATH_AUTO;
            } else {
                vectorPath = vectorPath.equalsIgnoreCase(PATH_A) ? PATH_A
                        : vectorPath.equalsIgnoreCase(PATH_B) ? PATH_B : PATH_AUTO;
            }
        }
    }

    public static P7Options defaults() {
        return new P7Options(0, 16, 200, 64, 512, 10_000, 10_000, 0.3, 50_000,
                PATH_AUTO, false, false);
    }

    /** 强制精确(会话开关 / EXPLAIN 前缀):结果标 approximate=false(FR-760)。 */
    public P7Options withExact(boolean exact) {
        return new P7Options(vectorMetric, vectorM, vectorEfConstruction, vectorEfSearch,
                vectorEfMax, vectorBruteforceRows, exactFallbackRows, allowListMaxRatio,
                parallelMinRows, vectorPath, exact, vectorProfile);
    }

    /** 强制路径 A / B / auto(SC-422 对拍用)。 */
    public P7Options withPath(String path) {
        return new P7Options(vectorMetric, vectorM, vectorEfConstruction, vectorEfSearch,
                vectorEfMax, vectorBruteforceRows, exactFallbackRows, allowListMaxRatio,
                parallelMinRows, path, vectorExact, vectorProfile);
    }

    /**
     * SPEC-P7.1 K181:分段耗时开关({@code SET vector_profile = ON})。
     *
     * <p>默认关闭 —— 计时与打点日志本身有成本,只在诊断时打开;打开后 EXPLAIN 会多一行
     * "分段耗时",BE 日志也会限流打印一条。</p>
     */
    public P7Options withProfile(boolean profile) {
        return new P7Options(vectorMetric, vectorM, vectorEfConstruction, vectorEfSearch,
                vectorEfMax, vectorBruteforceRows, exactFallbackRows, allowListMaxRatio,
                parallelMinRows, vectorPath, vectorExact, profile);
    }
}
