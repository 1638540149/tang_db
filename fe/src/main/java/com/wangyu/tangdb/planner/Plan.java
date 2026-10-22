package com.wangyu.tangdb.planner;

import com.wangyu.tangdb.storage.TableMeta;

import java.util.List;

/**
 * 访问计划。P2 只有三种路径(规约 FR-142 ~ FR-144 / K20):
 *
 * <ul>
 *   <li>{@link Kind#POINT}:主键等值,走 BE 的 Get;</li>
 *   <li>{@link Kind#POINTS}:主键 IN,多个点查;</li>
 *   <li>{@link Kind#RANGE}:主键范围(含全表),走 BE 的 Scan 限定范围;</li>
 * </ul>
 *
 * <p>WHERE 的其余部分一律在 FE 侧过滤,不做谓词下推。</p>
 */
public final class Plan {

    public enum Kind {
        POINT,
        POINTS,
        RANGE
    }

    private final Kind kind;
    private final TableMeta table;
    private final List<byte[]> pointKeys;
    private final byte[] start;
    private final byte[] end;
    private final Predicate predicate;
    private final boolean fullScan;

    private Plan(Kind kind, TableMeta table, List<byte[]> pointKeys,
                 byte[] start, byte[] end, Predicate predicate, boolean fullScan) {
        this.kind = kind;
        this.table = table;
        this.pointKeys = pointKeys;
        this.start = start;
        this.end = end;
        this.predicate = predicate;
        this.fullScan = fullScan;
    }

    public static Plan point(TableMeta table, byte[] key, Predicate predicate) {
        return new Plan(Kind.POINT, table, List.of(key), null, null, predicate, false);
    }

    public static Plan points(TableMeta table, List<byte[]> keys, Predicate predicate) {
        return new Plan(Kind.POINTS, table, List.copyOf(keys), null, null, predicate, false);
    }

    public static Plan range(TableMeta table, byte[] start, byte[] end,
                             Predicate predicate, boolean fullScan) {
        return new Plan(Kind.RANGE, table, null, start, end, predicate, fullScan);
    }

    public Kind kind() {
        return kind;
    }

    public TableMeta table() {
        return table;
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
}
