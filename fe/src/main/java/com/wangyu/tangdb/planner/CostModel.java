package com.wangyu.tangdb.planner;

/**
 * CBO 相对代价模型(SPEC-P6 FR-629 / K137)。
 *
 * <p>常量是**相对单位**(以"扫描一行"为 1.0),不映射到毫秒;所有调参集中在本类,
 * {@link Estimator} 与 {@link Planner} 只调用这里的方法。</p>
 *
 * <pre>
 * 全表扫描 1.0 / 索引项扫描 1.5 / 回表 2.0 / 哈希构建 1.2 / 哈希探测 1.0
 * 排序比较 0.3×log2(n) / TopN 0.5 / 溢写 5.0
 * </pre>
 *
 * <p>向量索引扫描槽位({@link #VECTOR_INDEX_SCAN})留给 P7 注册,本期不存在
 * {@code VECTOR} 类型,因此永不被选中(SPEC-P6 §7)。</p>
 */
public final class CostModel {

    /** 全表顺序扫描:每行 1.0。 */
    public static final double FULL_SCAN_PER_ROW = 1.0;
    /** 索引项解析:每项 1.5。 */
    public static final double INDEX_SCAN_PER_ROW = 1.5;
    /** 回表点查:每次 2.0。 */
    public static final double TABLE_LOOKUP = 2.0;
    /** 哈希表构建:每行 1.2。 */
    public static final double HASH_BUILD_PER_ROW = 1.2;
    /** 哈希探测:每行 1.0。 */
    public static final double HASH_PROBE_PER_ROW = 1.0;
    /** 排序比较基数:每行 0.3 × log2(n)。 */
    public static final double SORT_COMPARE = 0.3;
    /** TopN 堆:每行 0.5。 */
    public static final double TOPN_PER_ROW = 0.5;
    /** 溢写磁盘:每次 5.0。 */
    public static final double SPILL = 5.0;

    /** 向量索引扫描槽位(P7 注册代价函数;本期永不选中)。 */
    public static final double VECTOR_INDEX_SCAN = Double.POSITIVE_INFINITY;

    private CostModel() {
    }

    /** 全表扫描代价 = 行数 × 1.0(FR-630)。 */
    public static double fullScan(double rows) {
        return Math.max(0, rows) * FULL_SCAN_PER_ROW;
    }

    /** 主键范围扫描:顺序读,与全表同单位。 */
    public static double primaryRange(double rows) {
        return fullScan(rows);
    }

    /** 索引路径代价 = 索引项扫描 + 回表(FR-630)。 */
    public static double indexPath(double indexEntries, double tableLookups) {
        return Math.max(0, indexEntries) * INDEX_SCAN_PER_ROW
                + Math.max(0, tableLookups) * TABLE_LOOKUP;
    }

    /** 主键点查:一次 Get。 */
    public static double primaryPoint(double points) {
        return Math.max(0, points) * TABLE_LOOKUP;
    }

    /** 哈希连接:构建 + 探测。 */
    public static double hashJoin(double buildRows, double probeRows) {
        return Math.max(0, buildRows) * HASH_BUILD_PER_ROW
                + Math.max(0, probeRows) * HASH_PROBE_PER_ROW;
    }

    /** 排序代价:0.3 × n × log2(n)。 */
    public static double sort(double rows) {
        if (rows <= 1) {
            return 0;
        }
        return SORT_COMPARE * rows * (Math.log(rows) / Math.log(2));
    }

    /** TopN 代价:0.5 × n。 */
    public static double topN(double rows) {
        return Math.max(0, rows) * TOPN_PER_ROW;
    }

    /** 溢写代价:一次 5.0(分片数由调用方乘)。 */
    public static double spill(double chunks) {
        return Math.max(0, chunks) * SPILL;
    }
}
