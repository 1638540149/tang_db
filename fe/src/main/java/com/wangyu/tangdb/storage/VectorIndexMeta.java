package com.wangyu.tangdb.storage;

/**
 * 向量索引元数据(SPEC-P7 §5.3 系统表 9 {@code sys_vec_indexes})。
 *
 * <p>段级状态(哪些 segment 的图已就绪、节点数、待重建段数)在 BE 的 MANIFEST 里,
 * 不在这里双写;这里的 {@code state} 是索引级的粗状态。</p>
 *
 * @param columnOrdinal 向量列在表定义里的序号(BE 建图时按它取"定长槽")
 * @param dim           维度(= 列维度)
 * @param metric        0=L2 / 1=Cosine / 2=InnerProduct(VectorValues.METRIC_*)
 * @param state         {@link #STATE_BUILDING} / {@link #STATE_READY} / {@link #STATE_DROPPED}
 */
public record VectorIndexMeta(long tableId, int indexId, String name, String columnName,
                              int columnOrdinal, int dim, int metric, int m, int efConstruction,
                              int state, String createdAt) {

    public static final int STATE_BUILDING = 0;
    public static final int STATE_READY = 1;
    public static final int STATE_DROPPED = 2;

    public boolean ready() {
        return state == STATE_READY;
    }

    public boolean dropped() {
        return state == STATE_DROPPED;
    }

    public String stateName() {
        return switch (state) {
            case STATE_READY -> "READY";
            case STATE_DROPPED -> "DROPPED";
            default -> "BUILDING";
        };
    }

    /**
     * BE 侧使用的"全局唯一索引号":索引 ID 是**每表**从 1 起(FR-464),不同表的同名 ID
     * 会撞在一起(图文件名也按它命名),所以 FE→BE 一律用 {@code table_id × 1,000,000 + index_id}。
     * 逻辑索引 ID(对外展示 / sys_vec_indexes)仍是每表那个小数字。
     */
    public long beId() {
        return tableId * 1_000_000L + indexId;
    }

    public String metricName() {
        return VectorValues.metricName(metric);
    }

    public VectorIndexMeta withState(int newState) {
        return new VectorIndexMeta(tableId, indexId, name, columnName, columnOrdinal, dim, metric,
                m, efConstruction, newState, createdAt);
    }
}
