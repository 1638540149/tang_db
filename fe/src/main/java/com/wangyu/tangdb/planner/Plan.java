package com.wangyu.tangdb.planner;

import com.wangyu.tangdb.storage.IndexMeta;
import com.wangyu.tangdb.storage.TableMeta;

import java.util.List;

/**
 * 访问计划(SPEC-P2 FR-142 ~ FR-144;SPEC-P4 FR-504 / K82 扩展索引路径)。
 *
 * <ul>
 *   <li>{@link Kind#POINT}:主键等值,走 BE 的 Get;</li>
 *   <li>{@link Kind#POINTS}:主键 IN,多个点查;</li>
 *   <li>{@link Kind#RANGE}:主键范围(含全表),走 BE 的 Scan 限定范围;</li>
 *   <li>{@link Kind#INDEX_POINT}:索引等值,扫索引项 + 逐行回表;</li>
 *   <li>{@link Kind#INDEX_RANGE}:索引范围,扫索引项 + 逐行回表。</li>
 * </ul>
 *
 * <p>WHERE 的其余部分一律在 FE 侧过滤(FR-503),不做谓词下推。</p>
 */
public final class Plan {

    public enum Kind {
        POINT,
        POINTS,
        RANGE,
        INDEX_POINT,
        INDEX_RANGE
    }

    private final Kind kind;
    private final TableMeta table;
    private final IndexMeta index;
    private final List<byte[]> pointKeys;
    private final byte[] start;
    private final byte[] end;
    private final Predicate predicate;
    private final boolean fullScan;

    private Plan(Kind kind, TableMeta table, IndexMeta index, List<byte[]> pointKeys,
                 byte[] start, byte[] end, Predicate predicate, boolean fullScan) {
        this.kind = kind;
        this.table = table;
        this.index = index;
        this.pointKeys = pointKeys;
        this.start = start;
        this.end = end;
        this.predicate = predicate;
        this.fullScan = fullScan;
    }

    public static Plan point(TableMeta table, byte[] key, Predicate predicate) {
        return new Plan(Kind.POINT, table, null, List.of(key), null, null, predicate, false);
    }

    public static Plan points(TableMeta table, List<byte[]> keys, Predicate predicate) {
        return new Plan(Kind.POINTS, table, null, List.copyOf(keys), null, null, predicate, false);
    }

    public static Plan range(TableMeta table, byte[] start, byte[] end,
                             Predicate predicate, boolean fullScan) {
        return new Plan(Kind.RANGE, table, null, null, start, end, predicate, fullScan);
    }

    /** 索引等值:start/end 已经是一段用户键区间(同一列值可能有多条索引项)。 */
    public static Plan indexPoint(TableMeta table, IndexMeta index, byte[] start, byte[] end,
                                  Predicate predicate) {
        return new Plan(Kind.INDEX_POINT, table, index, null, start, end, predicate, false);
    }

    /** 索引范围。 */
    public static Plan indexRange(TableMeta table, IndexMeta index, byte[] start, byte[] end,
                                  Predicate predicate) {
        return new Plan(Kind.INDEX_RANGE, table, index, null, start, end, predicate, false);
    }

    public Kind kind() {
        return kind;
    }

    public TableMeta table() {
        return table;
    }

    /** 走索引时对应的索引元数据;否则 null。 */
    public IndexMeta index() {
        return index;
    }

    public List<byte[]> pointKeys() {
        return pointKeys;
    }

    public byte[] start() {
        return start;
    }

    public byte[] end() {
        return end;
    }

    public Predicate predicate() {
        return predicate;
    }

    /** 是否是全表扫描(用于测试与诊断)。 */
    public boolean fullScan() {
        return fullScan;
    }

    /** 是否走索引扫描。 */
    public boolean indexScan() {
        return kind == Kind.INDEX_POINT || kind == Kind.INDEX_RANGE;
    }
}
