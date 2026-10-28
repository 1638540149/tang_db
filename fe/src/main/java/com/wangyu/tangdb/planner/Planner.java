package com.wangyu.tangdb.planner;

import com.wangyu.tangdb.catalog.CatalogException;
import com.wangyu.tangdb.parser.Ast;
import com.wangyu.tangdb.stats.StatsService;
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
 * 计划器:选择访问路径(主键点查 / 主键范围 / 索引点查 / 索引范围 / 全表扫描)并绑定投影。
 *
 * <p>P4 的固定优先级(RBO)仍然保留,但 P6 起默认换成**代价比较**(SPEC-P6 FR-630 / FR-635):</p>
 * <pre>
 * 候选按固定优先级排序:① 主键等值 ② 主键 IN ③ 主键范围 ④ 索引等值 ⑤ 索引范围 ⑥ 全表扫描
 * optimizer_enabled=true   → 选择估算代价最低者;代价相等按固定优先级(边界 87)
 * optimizer_enabled=false  → 直接取第一个候选(= P5 的"有索引就用",结果与 P5 一致)
 * </pre>
 *
 * <p>{@code OR} 不合并:候选项只来自顶层 AND 合取项里的简单比较,纯 OR 退化为全表扫,
 * 结果由残余谓词保证正确(FR-506)。</p>
 */
public final class Planner {

    /** 投影:列序号 + 输出列名(别名已生效)。 */
    public record Projection(int[] indexes, List<String> names) {
    }

    /** 一条候选访问路径(EXPLAIN 需要把每条候选的估算都显示出来,FR-630 / FR-631)。 */
    public record Candidate(String label, Plan plan, long rows, double cost) {
    }

    /** 访问路径选择结果:选中的计划 + 全部候选 + 估算依据。 */
    public record Choice(Plan plan, List<Candidate> candidates, Estimator.Evidence evidence,
                         String analyzedAt) {

        /** 选中的那条候选(按计划对象身份定位)。 */
        public Candidate chosen() {
            for (Candidate candidate : candidates) {
                if (candidate.plan() == plan) {
                    return candidate;
                }
            }
            return candidates.get(0);
        }
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

    /** 为一个表与 WHERE 条件选择访问路径(没有索引参与,兼容旧调用;P5 固定规则)。 */
    public static Plan access(TableMeta table, Ast.Condition where) {
        return access(table, where, List.of());
    }

    /**
     * P5 的固定 RBO 入口(测试与旧调用方使用)。
     * 行为与 P4 完全一致:主键等值 > 主键范围(含 IN)> 索引等值 > 索引范围 > 全表扫。
     */
    public static Plan access(TableMeta table, Ast.Condition where, List<IndexMeta> indexes) {
        return choose(table, where, indexes, null, false).plan();
    }

    /** SPEC-P6:代价比较入口(FR-630 / FR-635)。 */
    public static Choice choose(TableMeta table, Ast.Condition where, List<IndexMeta> indexes,
                                StatsService stats, boolean cboEnabled) {
        Predicate predicate = Predicate.bind(where, table);
        if (table.primaryKey() == null) {
            throw new CatalogException("表 " + table.name() + " 没有主键");
        }
        List<Ast.Condition> conjuncts = new ArrayList<>();
        flattenAnd(where, conjuncts);

        Estimator estimator = new Estimator(stats, null);
        List<Candidate> candidates = candidates(table, conjuncts, predicate, indexes, estimator);

        Candidate chosen = candidates.get(0);
        if (cboEnabled) {
            for (Candidate candidate : candidates) {
                if (candidate.cost() < chosen.cost()) {
                    chosen = candidate;
                }
            }
        }
        return new Choice(chosen.plan(), List.copyOf(candidates),
                estimator.evidence(table), estimator.analyzedAt(table));
    }

    // ------------------------------------------------------------ 候选生成

    /** 主键条件归一后的结果。 */
    private static final class PkConditions {
        boolean hasEq;
        Object eqValue;
        boolean eqIsNull;
        final List<Object> inValues = new ArrayList<>();
        boolean hasLower;
        Object lower;
        boolean lowerInclusive = true;
        boolean hasUpper;
        Object upper;
        boolean upperInclusive = true;
    }

    private static List<Candidate> candidates(TableMeta table, List<Ast.Condition> conjuncts,
                                              Predicate predicate, List<IndexMeta> indexes,
                                              Estimator estimator) {
        List<Candidate> out = new ArrayList<>();
        Column pk = table.primaryKey();
        PkConditions pkConditions = analyzePrimaryKey(table, pk, conjuncts);
        long rowCount = estimator.rowCount(table);
        long filteredRows = estimator.estimateRows(table, predicateSource(conjuncts));

        if (pkConditions.eqIsNull) {
            // pk = NULL 不可能命中,但仍要给出正确结果:全表扫描 + 谓词过滤(结果为 0 行)。
            out.add(new Candidate("TABLE SCAN",
                    Plan.range(table, KeyCodec.tableRangeStart(table.id()),
                            KeyCodec.tableRangeEnd(table.id()), predicate, true),
                    0, CostModel.fullScan(rowCount)));
            return out;
        }
        if (pkConditions.hasEq) {
            out.add(new Candidate("PRIMARY POINT",
                    Plan.point(table, KeyCodec.rowKey(table.id(), pk.type(), pkConditions.eqValue),
                            predicate),
                    1, CostModel.primaryPoint(1)));
        }
        if (!pkConditions.inValues.isEmpty()) {
            Set<byte[]> keys = new LinkedHashSet<>();
            for (Object value : pkConditions.inValues) {
                keys.add(KeyCodec.rowKey(table.id(), pk.type(), value));
            }
            out.add(new Candidate("PRIMARY POINT(IN " + keys.size() + ")",
                    Plan.points(table, new ArrayList<>(keys), predicate),
                    keys.size(), CostModel.primaryPoint(keys.size())));
        }
        if (pkConditions.hasLower || pkConditions.hasUpper) {
            byte[] start = pkConditions.hasLower
                    ? KeyCodec.lowerBound(table.id(), pk.type(), pkConditions.lower,
                    pkConditions.lowerInclusive)
                    : KeyCodec.tableRangeStart(table.id());
            byte[] end = pkConditions.hasUpper
                    ? KeyCodec.upperBound(table.id(), pk.type(), pkConditions.upper,
                    pkConditions.upperInclusive)
                    : KeyCodec.tableRangeEnd(table.id());
            long rows = estimator.estimateRangeRows(table, pk, pkConditions.lower,
                    pkConditions.hasLower, pkConditions.upper, pkConditions.hasUpper);
            out.add(new Candidate("PRIMARY RANGE",
                    Plan.range(table, start, end, predicate, false),
                    rows, CostModel.primaryRange(rows)));
        }

        // 索引候选(同类取索引 ID 最小者,FR-505)
        IndexConditions indexConditions = analyzeIndexes(table, conjuncts, indexes);
        if (indexConditions.eqIndex != null) {
            Column column = table.column(indexConditions.eqIndex.columnName());
            long rows = estimator.estimateEqualRows(table,
                    new Estimator.StatColumn(column.name(), column.type()));
            out.add(new Candidate("INDEX POINT(" + indexConditions.eqIndex.name() + ")",
                    Plan.indexPoint(table, indexConditions.eqIndex,
                            IndexCodec.equalityStart(table, indexConditions.eqIndex,
                                    indexConditions.eqValue),
                            IndexCodec.equalityEnd(table, indexConditions.eqIndex,
                                    indexConditions.eqValue), predicate),
                    rows, CostModel.indexPath(rows, rows)));
        }
        if (indexConditions.rangeIndex != null) {
            Column column = table.column(indexConditions.rangeIndex.columnName());
            byte[] start = indexConditions.hasRangeLower
                    ? IndexCodec.lowerBound(table, indexConditions.rangeIndex,
                    indexConditions.rangeLower, indexConditions.rangeLowerInclusive)
                    : IndexCodec.indexStart(table.id(), indexConditions.rangeIndex.indexId());
            byte[] end = indexConditions.hasRangeUpper
                    ? IndexCodec.upperBound(table, indexConditions.rangeIndex,
                    indexConditions.rangeUpper, indexConditions.rangeUpperInclusive)
                    : IndexCodec.indexEnd(table.id(), indexConditions.rangeIndex.indexId());
            long rows = estimator.estimateRangeRows(table, column, indexConditions.rangeLower,
                    indexConditions.hasRangeLower, indexConditions.rangeUpper,
                    indexConditions.hasRangeUpper);
            out.add(new Candidate("INDEX RANGE(" + indexConditions.rangeIndex.name() + ")",
                    Plan.indexRange(table, indexConditions.rangeIndex, start, end, predicate),
                    rows, CostModel.indexPath(rows, rows)));
        }

        // 全表扫描永远是最后一个候选(边界 87:代价相等时优先级兜底)
        out.add(new Candidate("TABLE SCAN",
                Plan.range(table, KeyCodec.tableRangeStart(table.id()),
                        KeyCodec.tableRangeEnd(table.id()), predicate, true),
                filteredRows, CostModel.fullScan(rowCount)));
        return out;
    }

    /** 把合取项重新拼成一棵条件树,给"全表扫描输出行数"估算用。 */
    private static Ast.Condition predicateSource(List<Ast.Condition> conjuncts) {
        if (conjuncts.isEmpty()) {
            return null;
        }
        Ast.Condition result = conjuncts.get(0);
        for (int i = 1; i < conjuncts.size(); i++) {
            result = new Ast.And(result, conjuncts.get(i));
        }
        return result;
    }

    private static PkConditions analyzePrimaryKey(TableMeta table, Column pk,
                                                  List<Ast.Condition> conjuncts) {
        PkConditions out = new PkConditions();
        for (Ast.Condition conjunct : conjuncts) {
            if (conjunct instanceof Ast.Compare cmp && isColumn(cmp.column(), pk)) {
                Object value = cmp.value();
                switch (cmp.op()) {
                    case EQ -> {
                        if (value == null) {
                            out.eqIsNull = true;
                        } else if (!out.hasEq) {
                            out.eqValue = Values.coerce(value, pk);
                            out.hasEq = true;
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
                            if (!out.hasLower) {
                                out.lower = v;
                                out.lowerInclusive = inclusive;
                                out.hasLower = true;
                            } else {
                                int c = compareTyped(pk.type(), v, out.lower);
                                if (c > 0) {
                                    out.lower = v;
                                    out.lowerInclusive = inclusive;
                                } else if (c == 0) {
                                    out.lowerInclusive = out.lowerInclusive && inclusive;
                                }
                            }
                        } else {
                            if (!out.hasUpper) {
                                out.upper = v;
                                out.upperInclusive = inclusive;
                                out.hasUpper = true;
                            } else {
                                int c = compareTyped(pk.type(), v, out.upper);
                                if (c < 0) {
                                    out.upper = v;
                                    out.upperInclusive = inclusive;
                                } else if (c == 0) {
                                    out.upperInclusive = out.upperInclusive && inclusive;
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
                    out.inValues.add(Values.coerce(literal, pk));
                }
            }
        }
        return out;
    }

    private static final class IndexConditions {
        IndexMeta eqIndex;
        Object eqValue;
        IndexMeta rangeIndex;
        Object rangeLower;
        boolean rangeLowerInclusive = true;
        boolean hasRangeLower;
        Object rangeUpper;
        boolean rangeUpperInclusive = true;
        boolean hasRangeUpper;
    }

    /** RBO 的 ③ / ④ 两步:先找等值命中,再找范围命中;同类取索引 ID 最小者(FR-505)。 */
    private static IndexConditions analyzeIndexes(TableMeta table, List<Ast.Condition> conjuncts,
                                                  List<IndexMeta> indexes) {
        IndexConditions out = new IndexConditions();
        if (indexes == null || indexes.isEmpty()) {
            return out;
        }
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
            if (hasEq && out.eqIndex == null) {
                out.eqIndex = index;
                out.eqValue = eq;
            }
            if (!hasEq && (hasLo || hasHi) && out.rangeIndex == null) {
                out.rangeIndex = index;
                out.rangeLower = lo;
                out.rangeLowerInclusive = loInc;
                out.hasRangeLower = hasLo;
                out.rangeUpper = hi;
                out.rangeUpperInclusive = hiInc;
                out.hasRangeUpper = hasHi;
            }
        }
        return out;
    }

    // ------------------------------------------------------------ 工具

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
