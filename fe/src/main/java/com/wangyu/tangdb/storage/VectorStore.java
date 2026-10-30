package com.wangyu.tangdb.storage;

import java.util.List;

/**
 * 向量索引通道(SPEC-P7 B/C 组):只有"真实 BE 客户端"实现它。
 *
 * <p><b>边界</b>:这里传的全部是"纯字节 + 数字"——
 * 行键范围、列槽表、行框架、维度、度量操作码、查询向量的 float32 字节、允许集;
 * 不出现表名 / 列名 / 类型名 / SQL(K161 / INV-16)。图段构建与图内距离由 BE 计算,
 * 查询侧的精确打分仍由 FE 完成。</p>
 *
 * <p>内存实现(单测)不实现本接口 → 选路自动退化为精确暴力,结果不变。</p>
 */
public interface VectorStore {

    /**
     * 注册(或覆盖)一个向量索引定义,并让 BE 为"还没有图"的 segment 后台建图。
     *
     * @param definition 定义(索引 ID / 表 ID / 行键范围 / 列槽 / 维度 / 度量 / 图参数)
     */
    void registerVectorIndex(VectorIndexDefinition definition);

    /** 触发一次显式重建(FR-750):旧图先删,segment 重新排队建图。 */
    void rebuildVectorIndex(long indexId);

    /** 删除某向量索引的全部图文件(FR-737 / 边界 153)。 */
    void dropVectorIndex(long indexId);

    /** 某索引的段级状态(段数/节点数/待重建段数/图段字节数),SHOW VECTOR INDEXES 用。 */
    VectorIndexStatus vectorIndexStatus(long indexId);

    /**
     * 近似检索:每段 top-{@code ef} → 归并,返回候选行键(用户键,不含版本段)。
     *
     * @param query   查询向量的 float32 小端字节
     * @param allow   允许集;空表示不限(路径 B)。{@code bloom=true} 时按布隆过滤器解释(K182)
     * @param profile SPEC-P7.1 K181:true 时 BE 返回五段耗时(默认 false)
     */
    VectorSearchResult searchVector(long indexId, byte[] query, int ef, int k, long snapshot,
                                    byte[] allow, boolean bloom, boolean profile);

    /**
     * 批量点查(ANN 候选回表):一次 RPC 取全部候选(K180),返回值**与 keys 一一对应**
     * (不存在的键给 null),调用方不需要任何 Map 做中间装配。
     *
     * <p>同一快照,避免"候选来自快照 s、回表读到更新的版本"。</p>
     */
    BatchResult batchGetOrdered(java.util.List<byte[]> keys, long snapshot, boolean profile);

    /**
     * 一次候选回表的结果:值**与请求 keys 一一对应**(不存在的键给 null)+ BE 侧耗时。
     */
    record BatchResult(java.util.List<byte[]> values, String profile) {
    }

    /**
     * 一次近似检索的返回:候选键 + BE 侧遍历量 + 待重建段数 + 分段耗时(K181)。
     *
     * <p>{@code profile} 为空串表示没有开启打点;非空时是 KEY=VALUE 文本,由 FE 原样展示。</p>
     */
    record VectorSearchResult(List<byte[]> keys, int examined, int pendingSegments,
                              String profile) {
        public static VectorSearchResult empty() {
            return new VectorSearchResult(List.of(), 0, 0, "");
        }
    }

    /** 建图定义:FE 只下发字节与数字(K161)。 */
    record VectorIndexDefinition(long indexId, long tableId, byte[] startKey, byte[] endKey,
                                 byte[] columnSlots, byte[] rowFrame, int vectorOrdinal,
                                 int dim, int metric, int m, int efConstruction) {
    }

    /** 段级状态。 */
    record VectorIndexStatus(int segmentCount, int readySegments, int pendingSegments,
                             long nodeCount, long graphBytes, int metric, int dim) {
        public static VectorIndexStatus empty() {
            return new VectorIndexStatus(0, 0, 0, 0, 0, 0, 0);
        }
    }
}
