package com.wangyu.tangdb.planner;

import com.wangyu.tangdb.catalog.CatalogException;
import com.wangyu.tangdb.parser.Ast;
import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.DataType;
import com.wangyu.tangdb.storage.IndexCodec;
import com.wangyu.tangdb.storage.IndexMeta;
import com.wangyu.tangdb.storage.KeyCodec;
import com.wangyu.tangdb.storage.TableMeta;
import com.wangyu.tangdb.storage.Values;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 计划器:选择访问路径(主键点查 / 主键范围 / 全表扫描)并绑定投影。
 *
 * <p>规则来自规约 FR-142 ~ FR-144:
 * 主键等值必须走 Get;主键范围必须用 Scan 限定范围;其余条件全表 Scan + FE 过滤。
 * OR 不拆解(除非整棵条件树都是 AND 合取项),避免把范围条件错误地"并集化"。</p>
 */
public final class Planner {

    /** 投影:列序号 + 输出列名(别名已生效)。 */
    public record Projection(int[] indexes, List<String> names) {
    }

    private Planner() {
    }

    /** 把 SELECT 的投影绑定到表上。 */
    public static Projection projection(TableMeta table, Ast.Select select) {
        if (select.star()) {
            int n = table.columnCount();
            int[] indexes = new int[n];
            List<String> names = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                indexes[i] = i;
                names.add(table.columns().get(i).name());
            }
            return new Projection(indexes, List.copyOf(names));
        }
        int[] indexes = new int[select.items().size()];
        List<String> names = new ArrayList<>(select.items().size());
        for (int i = 0; i < select.items().size(); i++) {
            Ast.Item item = select.items().get(i);
            Column column = Predicate.requireColumn(table, item.column());
            indexes[i] = column.ordinal();
            names.add(item.alias() == null ? column.name() : item.alias());
        }
        return new Projection(indexes, List.copyOf(names));
    }

    /** 为一个表与 WHERE 条件选择访问路径(没有索引参与,兼容旧调用)。 */
    public static Plan access(TableMeta table, Ast.Condition where) {
        return access(table, where, List.of());
    }

    /**
     * 为一个表与 WHERE 条件选择访问路径(SPEC-P4 FR-504 / K82 的固定 RBO):
     *
     * <pre>
     * ① 主键等值 > ② 主键范围(含 IN)> ③ 索引等值 > ④ 索引范围 > ⑤ 全表扫
     * 同类多个索引取索引 ID 最小者(FR-505);OR 不合并(FR-506)。
     * </pre>
     *
     * @param indexes 可参与选择的索引(调用方只传 ACTIVE 的,按索引 ID 升序)
     */
    public static Plan access(TableMeta table, Ast.Condition where, List<IndexMeta> indexes) {
        Predicate predicate = Predicate.bind(where, table);
        Column pk = table.primaryKey();
        if (pk == null) {
            throw new CatalogException("表 " + table.name() + " 没有主键");
        }

        List<Ast.Condition> conjuncts = new ArrayList<>();
        flattenAnd(where, conjuncts);

        Object eqValue = null;
        boolean hasEq = false;
        boolean eqIsNull = false;
        List<Object> inValues = new ArrayList<>();

        Object lower = null;
        boolean lowerInclusive = true;
        boolean hasLower = false;
        Object upper = null;
        boolean upperInclusive = true;
        boolean hasUpper = false;

        for (Ast.Condition conjunct : conjuncts) {
            if (conjunct instanceof Ast.Compare cmp && isColumn(cmp.column(), pk)) {
                Object value = cmp.value();
                switch (cmp.op()) {
                    case EQ -> {
                        if (value == null) {
                            eqIsNull = true;
                        } else if (!hasEq) {
                            eqValue = Values.coerce(value, pk);
                            hasEq = true;
                        }
                    }
                    case GT, GE, LT, LE -> {
                        if (value == null) {
                            // 与 NULL 比较恒为 UNKNOWN,不可能命中;保持全表扫描让谓词自然过滤。
                            continue;
                        }
                        Object v = Values.coerce(value, pk);
                        boolean inclusive = cmp.op() == Ast.Op.GE || cmp.op() == Ast.Op.LE;
                        if (cmp.op() == Ast.Op.GT || cmp.op() == Ast.Op.GE) {
                            if (!hasLower) {
                                lower = v;
                                lowerInclusive = inclusive;
                                hasLower = true;
                            } else {
                                int c = compareTyped(pk.type(), v, lower);
                                if (c > 0) {
                                    lower = v;
                                    lowerInclusive = inclusive;
                                } else if (c == 0) {
                                    lowerInclusive = lowerInclusive && inclusive;
                                }
                            }
                        } else {
                            if (!hasUpper) {
                                upper = v;
                                upperInclusive = inclusive;
                                hasUpper = true;
                            } else {
                                int c = compareTyped(pk.type(), v, upper);
                                if (c < 0) {
                                    upper = v;
                                    upperInclusive = inclusive;
                                } else if (c == 0) {
                                    upperInclusive = upperInclusive && inclusive;
                                }
                            }
                        }
                    }
                    default -> {
                    }
                }
            } else if (conjunct instanceof Ast.In in && isColumn(in.column(), pk)) {
                for (Object literal : in.values()) {
                    if (literal == null) {
                        continue; // NULL 永远不会命中,忽略即可
                    }
                    inValues.add(Values.coerce(literal, pk));
                }
            }
        }

        if (eqIsNull) {
            // pk = NULL 不可能命中,但仍要给出正确结果:全表扫描 + 谓词过滤(结果为 0 行)。
            return Plan.range(table, KeyCodec.tableRangeStart(table.id()),
                    KeyCodec.tableRangeEnd(table.id()), predicate, true);
        }
        if (hasEq) {
            return Plan.point(table, KeyCodec.rowKey(table.id(), pk.type(), eqValue), predicate);
        }
        if (!inValues.isEmpty()) {
            Set<byte[]> keys = new LinkedHashSet<>();
            for (Object v : inValues) {
                keys.add(KeyCodec.rowKey(table.id(), pk.type(), v));
            }
            return Plan.points(table, new ArrayList<>(keys), predicate);
        }
        if (hasLower || hasUpper) {
            byte[] start = hasLower
                    ? KeyCodec.lowerBound(table.id(), pk.type(), lower, lowerInclusive)
                    : KeyCodec.tableRangeStart(table.id());
            byte[] end = hasUpper
                    ? KeyCodec.upperBound(table.id(), pk.type(), upper, upperInclusive)
                    : KeyCodec.tableRangeEnd(table.id());
            return Plan.range(table, start, end, predicate, false);
        }

        Plan indexPlan = chooseIndexPath(table, conjuncts, predicate, indexes);
        if (indexPlan != null) {
            return indexPlan;
        }
        return Plan.range(table, KeyCodec.tableRangeStart(table.id()),
                KeyCodec.tableRangeEnd(table.id()), predicate, true);
    }

    /**
     * RBO 的 ③ / ④ 两步:先找等值命中,再找范围命中;同类取索引 ID 最小者(FR-505)。
     *
     * <p>OR 不合并:候选项只来自顶层 AND 合取项里的简单比较;纯 OR 会退化为全表扫,
     * 结果由残余谓词保证正确(FR-506)。</p>
     */
    private static Plan chooseIndexPath(TableMeta table, List<Ast.Condition> conjuncts,
                                        Predicate predicate, List<IndexMeta> indexes) {
        if (indexes == null || indexes.isEmpty()) {
            return null;
        }
        IndexMeta eqIndex = null;
        Object eqValue = null;
        IndexMeta rangeIndex = null;
        Object rangeLower = null;
        boolean rangeLowerInclusive = true;
        boolean hasRangeLower = false;
        Object rangeUpper = null;
        boolean rangeUpperInclusive = true;
        boolean hasRangeUpper = false;

        for (IndexMeta index : indexes) {
            if (!index.queryable()) {
                continue;
            }
            Column column = table.column(index.columnName());
            if (column == null) {
                continue;
            }
            Object eq = null;
            boolean hasEq = false;
            Object lo = null;
            boolean loInc = true;
            boolean hasLo = false;
            Object hi = null;
            boolean hiInc = true;
            boolean hasHi = false;
            for (Ast.Condition conjunct : conjuncts) {
                if (!(conjunct instanceof Ast.Compare cmp) || !isColumn(cmp.column(), column)) {
                    continue;
                }
                Object value = cmp.value();
                if (value == null) {
                    continue;
                }
                Object coerced;
                try {
                    coerced = Values.coerce(value, column);
                } catch (RuntimeException e) {
                    // 类型不匹配交给谓词绑定阶段报错;这里不把索引选择变成错误来源。
                    continue;
                }
                switch (cmp.op()) {
                    case EQ -> {
                        if (!hasEq) {
                            eq = coerced;
                            hasEq = true;
                        }
                    }
                    case GT, GE -> {
                        boolean inc = cmp.op() == Ast.Op.GE;
                        if (!hasLo) {
                            lo = coerced;
                            loInc = inc;
                            hasLo = true;
                        } else {
                            int c = compareTyped(column.type(), coerced, lo);
                            if (c > 0) {
                                lo = coerced;
                                loInc = inc;
                            } else if (c == 0) {
                                loInc = loInc && inc;
                            }
                        }
                    }
                    case LT, LE -> {
                        boolean inc = cmp.op() == Ast.Op.LE;
                        if (!hasHi) {
                            hi = coerced;
                            hiInc = inc;
                            hasHi = true;
                        } else {
                            int c = compareTyped(column.type(), coerced, hi);
                            if (c < 0) {
                                hi = coerced;
                                hiInc = inc;
                            } else if (c == 0) {
                                hiInc = hiInc && inc;
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
            if (hasEq && eqIndex == null) {
                eqIndex = index;
                eqValue = eq;
            }
            if (!hasEq && (hasLo || hasHi) && rangeIndex == null) {
                rangeIndex = index;
                rangeLower = lo;
                rangeLowerInclusive = loInc;
                hasRangeLower = hasLo;
                rangeUpper = hi;
                rangeUpperInclusive = hiInc;
                hasRangeUpper = hasHi;
            }
        }

        if (eqIndex != null) {
            return Plan.indexPoint(table, eqIndex,
                    IndexCodec.equalityStart(table, eqIndex, eqValue),
                    IndexCodec.equalityEnd(table, eqIndex, eqValue), predicate);
        }
        if (rangeIndex != null) {
            byte[] start = hasRangeLower
                    ? IndexCodec.lowerBound(table, rangeIndex, rangeLower, rangeLowerInclusive)
                    : IndexCodec.indexStart(table.id(), rangeIndex.indexId());
            byte[] end = hasRangeUpper
                    ? IndexCodec.upperBound(table, rangeIndex, rangeUpper, rangeUpperInclusive)
                    : IndexCodec.indexEnd(table.id(), rangeIndex.indexId());
            return Plan.indexRange(table, rangeIndex, start, end, predicate);
        }
        return null;
    }

    private static void flattenAnd(Ast.Condition condition, List<Ast.Condition> out) {
        if (condition == null) {
            return;
        }
        if (condition instanceof Ast.And and) {
            flattenAnd(and.left(), out);
            flattenAnd(and.right(), out);
            return;
        }
        out.add(condition);
    }

    private static boolean isColumn(String name, Column column) {
        return name != null && name.equalsIgnoreCase(column.name());
    }

    private static int compareTyped(DataType type, Object a, Object b) {
        return switch (type) {
            case INT -> Integer.compare(((Number) a).intValue(), ((Number) b).intValue());
            case BIGINT -> Long.compare(((Number) a).longValue(), ((Number) b).longValue());
            case DATE -> ((java.time.LocalDate) a).compareTo((java.time.LocalDate) b);
            case VARCHAR -> Arrays.compare(
                    ((String) a).getBytes(StandardCharsets.UTF_8),
                    ((String) b).getBytes(StandardCharsets.UTF_8));
            case BOOLEAN -> Boolean.compare(Boolean.TRUE.equals(a), Boolean.TRUE.equals(b));
            default -> throw new CatalogException("列类型不支持索引范围: " + type.sqlName());
        };
    }
}
