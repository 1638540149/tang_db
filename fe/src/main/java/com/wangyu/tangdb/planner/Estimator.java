package com.wangyu.tangdb.planner;

import com.wangyu.tangdb.parser.Ast;
import com.wangyu.tangdb.stats.ColumnStats;
import com.wangyu.tangdb.stats.Histogram;
import com.wangyu.tangdb.stats.StatsService;
import com.wangyu.tangdb.stats.TableStats;
import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.DataType;
import com.wangyu.tangdb.storage.TableMeta;

/**
 * 基数估算与选择率(SPEC-P6 FR-626 ~ FR-628 / FR-631 / FR-625)。
 *
 * <p>选择率公式表(FR-626):</p>
 * <pre>
 * =        → 1/distinct
 * 范围      → 直方图累计
 * IN       → n/distinct
 * LIKE 'x%'→ 0.20
 * IS NULL  → null_count/row_count
 * NOT      → 1-p
 * AND      → 各因子乘积(独立假设)
 * OR       → 1-(1-p1)(1-p2)
 * </pre>
 *
 * <p>没有统计或统计已 stale 时一律回落默认值(FR-625):行数 10,000、distinct 100、
 * 范围选择率 1/3。{@code 依据} 行由 {@link #evidence(TableMeta)} 给出。</p>
 */
public final class Estimator {

    /** 估算依据:统计 / 默认。 */
    public enum Evidence {
        /** 用的是 ANALYZE 落库的统计。 */
        STATS,
        /** 有统计但已过期,按默认值估算(FR-623 / FR-625)。 */
        STALE,
        /** 未 ANALYZE,用默认值。 */
        DEFAULT
    }

    private final StatsService stats;
    private final OptimizerConfig config;

    public Estimator(StatsService stats, OptimizerConfig config) {
        this.stats = stats;
        this.config = config == null ? OptimizerConfig.defaults() : config;
    }

    // ------------------------------------------------------------ 表级

    /** 估算行数:有统计用统计,否则默认 10,000(FR-625)。 */
    public long rowCount(TableMeta table) {
        TableStats tableStats = effective(table);
        return tableStats == null ? StatsService.DEFAULT_ROW_COUNT
                : Math.max(0, tableStats.rowCount());
    }

    /** 有效统计(过期时返回 null,表示必须回落默认值)。 */
    private TableStats effective(TableMeta table) {
        if (stats == null) {
            return null;
        }
        TableStats tableStats = stats.tableStats(table);
        if (tableStats == null || tableStats.stale()) {
            return null;
        }
        return tableStats;
    }

    /** 估算依据。 */
    public Evidence evidence(TableMeta table) {
        if (stats == null) {
            return Evidence.DEFAULT;
        }
        TableStats tableStats = stats.tableStats(table);
        if (tableStats == null) {
            return Evidence.DEFAULT;
        }
        return tableStats.stale() ? Evidence.STALE : Evidence.STATS;
    }

    /** 分析时刻文本(EXPLAIN 的"依据"行);没有统计返回 null。 */
    public String analyzedAt(TableMeta table) {
        if (stats == null) {
            return null;
        }
        TableStats tableStats = stats.tableStats(table);
        return tableStats == null ? null : tableStats.lastAnalyze();
    }

    /** 某列的 distinct(FR-626);没有统计用默认 100。 */
    public long distinct(TableMeta table, String columnName) {
        Column column = table.column(columnName);
        if (column == null) {
            return StatsService.DEFAULT_DISTINCT;
        }
        return distinct(table, column.ordinal());
    }

    public long distinct(TableMeta table, int ordinal) {
        if (stats == null) {
            return StatsService.DEFAULT_DISTINCT;
        }
        ColumnStats column = stats.columnStatsByOrdinal(table.id(), ordinal);
        if (column == null || column.distinctCount() <= 0) {
            return StatsService.DEFAULT_DISTINCT;
        }
        return column.distinctCount();
    }

    /** 某列的直方图;没有返回 null。 */
    public Histogram histogram(TableMeta table, String columnName) {
        if (stats == null) {
            return null;
        }
        ColumnStats column = stats.columnStats(table.id(), columnName);
        if (column == null) {
            return null;
        }
        return Histogram.parse(column.histogram(), config.analyzeHistogramBuckets());
    }

    // ------------------------------------------------------------ WHERE 选择率(P5 表达式形态)

    /** 估算 WHERE 过滤后的行数(四舍五入,非负)。 */
    public long estimateRows(TableMeta table, Ast.Expr where) {
        long rows = rowCount(table);
        if (where == null) {
            return rows;
        }
        double p = selectivity(where, table);
        return Math.max(0, Math.round(rows * p));
    }

    /** 选择率;未知形态保守取 0.25(不影响正确性,只影响选路方向)。 */
    public double selectivity(Ast.Expr expr, TableMeta table) {
        if (expr == null) {
            return 1.0;
        }
        if (expr instanceof Ast.Lit lit) {
            if (lit.value() == null) {
                return 0.0;
            }
            if (lit.value() instanceof Boolean b) {
                return b ? 1.0 : 0.0;
            }
            return 0.25;
        }
        if (expr instanceof Ast.LogicalAnd and) {
            return clamp(selectivity(and.left(), table) * selectivity(and.right(), table));
        }
        if (expr instanceof Ast.LogicalOr or) {
            double a = selectivity(or.left(), table);
            double b = selectivity(or.right(), table);
            return clamp(1 - (1 - a) * (1 - b));
        }
        if (expr instanceof Ast.Not not) {
            return clamp(1 - selectivity(not.operand(), table));
        }
        if (expr instanceof Ast.Cmp cmp) {
            return comparison(cmp, table);
        }
        if (expr instanceof Ast.InExpr in) {
            Object column = in.operand();
            if (!(column instanceof Ast.Col col)) {
                return 0.25;
            }
            long distinct = distinct(table, col.name());
            double p = clamp((double) in.values().size() / Math.max(1, distinct));
            return in.negated() ? clamp(1 - p) : p;
        }
        if (expr instanceof Ast.IsNull isNull) {
            double nulls = nullRatio(table, isNull.operand());
            return isNull.negated() ? clamp(1 - nulls) : nulls;
        }
        if (expr instanceof Ast.Between between) {
            double p = rangeSelectivity(between.operand(), between.lower(), between.upper(), table);
            return between.negated() ? clamp(1 - p) : p;
        }
        if (expr instanceof Ast.Like like) {
            double p = likeSelectivity(like, table);
            return p;
        }
        return 0.25;
    }

    private double comparison(Ast.Cmp cmp, TableMeta table) {
        Ast.Col column = null;
        Ast.Expr literal = null;
        Ast.Op op = cmp.op();
        if (cmp.left() instanceof Ast.Col c && cmp.right() instanceof Ast.Lit lit) {
            column = c;
            literal = lit;
        } else if (cmp.right() instanceof Ast.Col c && cmp.left() instanceof Ast.Lit lit) {
            column = c;
            literal = lit;
            op = flip(op);
        }
        if (column == null || literal == null) {
            return 0.25; // 连接列比较在这里不参与单表选择率
        }
        if (((Ast.Lit) literal).value() == null) {
            return 0.0; // 与 NULL 比较恒 UNKNOWN
        }
        Object value = ((Ast.Lit) literal).value();
        long distinct = distinct(table, column.name());
        return switch (op) {
            case EQ -> clamp(1.0 / Math.max(1, distinct));
            case NE -> clamp(1 - 1.0 / Math.max(1, distinct));
            case LT, LE -> rangeSelectivity(column, null, literal, table, op == Ast.Op.LE);
            case GT, GE -> {
                double p = rangeSelectivity(column, null, literal, table, op == Ast.Op.GE);
                yield clamp(1 - p);
            }
        };
    }

    private static Ast.Op flip(Ast.Op op) {
        return switch (op) {
            case LT -> Ast.Op.GT;
            case LE -> Ast.Op.GE;
            case GT -> Ast.Op.LT;
            case GE -> Ast.Op.LE;
            default -> op;
        };
    }

    /** 两边界之间的选择率({@code lower} 为空表示无下界)。 */
    private double rangeSelectivity(Ast.Expr operand, Ast.Expr lower, Ast.Expr upper,
                                    TableMeta table) {
        boolean lowerInclusive = true;
        Ast.Expr lowerValue = lower;
        Ast.Expr upperValue = upper;
        // BETWEEN 的两边界都包含;Cmp 形态由调用方单独处理。
        return rangeSelectivity0(operand, lowerValue, lowerInclusive, upperValue, true, table);
    }

    private double rangeSelectivity(Ast.Col column, Ast.Expr lower, Ast.Expr upper,
                                    TableMeta table, boolean upperInclusive) {
        return rangeSelectivity0(column, lower, true, upper, upperInclusive, table);
    }

    private double rangeSelectivity0(Ast.Expr operand, Ast.Expr lower, boolean lowerInclusive,
                                     Ast.Expr upper, boolean upperInclusive, TableMeta table) {
        if (!(operand instanceof Ast.Col col)) {
            return 0.25;
        }
        long rows = rowCount(table);
        Histogram histogram = histogram(table, col.name());
        Column column = table.column(col.name());
        if (histogram == null || histogram.isEmpty() || column == null || rows <= 0) {
            return StatsService.DEFAULT_RANGE_SELECTIVITY; // FR-625
        }
        long le = rows;
        if (upper instanceof Ast.Lit lit && lit.value() != null) {
            le = upperInclusive
                    ? histogram.countLessThanOrEqual(lit.value(), column.type())
                    : histogram.countLessThan(lit.value(), column.type());
        }
        long lt = 0;
        if (lower instanceof Ast.Lit lit && lit.value() != null) {
            lt = lowerInclusive
                    ? histogram.countLessThanOrEqual(lit.value(), column.type())
                    : histogram.countLessThan(lit.value(), column.type());
        }
        return clamp((double) (le - lt) / rows);
    }

    /** IS NULL 的比例:统计里 null_count/row_count;没有统计取 0.1。 */
    private double nullRatio(TableMeta table, Ast.Expr operand) {
        if (!(operand instanceof Ast.Col col) || stats == null) {
            return 0.1;
        }
        ColumnStats column = stats.columnStats(table.id(), col.name());
        if (column == null) {
            return 0.1;
        }
        long rows = rowCount(table);
        return rows <= 0 ? 0 : clamp((double) column.nullCount() / rows);
    }

    /** LIKE 'x%' → 0.20(FR-626);其它形态保守 0.25。 */
    private double likeSelectivity(Ast.Like like, TableMeta table) {
        if (!(like.pattern() instanceof Ast.Lit lit) || !(lit.value() instanceof String pattern)) {
            return 0.25;
        }
        if (pattern.isEmpty()) {
            return 0.25;
        }
        boolean prefixOnly = pattern.charAt(pattern.length() - 1) == '%'
                && pattern.indexOf('%') == pattern.length() - 1
                && pattern.indexOf('_') < 0
                && !(pattern.length() > 1 && pattern.charAt(0) == '%');
        return prefixOnly ? 0.20 : 0.25;
    }

    // ------------------------------------------------------------ WHERE 选择率(P2~P4 条件形态)

    /** 估算旧的 {@link Ast.Condition} 形态(EXPLAIN / 访问路径选择共用)。 */
    public long estimateRows(TableMeta table, Ast.Condition where) {
        long rows = rowCount(table);
        if (where == null) {
            return rows;
        }
        return Math.max(0, Math.round(rows * selectivity(where, table)));
    }

    public double selectivity(Ast.Condition condition, TableMeta table) {
        if (condition == null) {
            return 1.0;
        }
        if (condition instanceof Ast.And and) {
            return clamp(selectivity(and.left(), table) * selectivity(and.right(), table));
        }
        if (condition instanceof Ast.Or or) {
            double a = selectivity(or.left(), table);
            double b = selectivity(or.right(), table);
            return clamp(1 - (1 - a) * (1 - b));
        }
        if (condition instanceof Ast.Compare cmp) {
            return comparisonCondition(cmp, table);
        }
        if (condition instanceof Ast.In in) {
            long distinct = distinct(table, in.column());
            return clamp((double) in.values().size() / Math.max(1, distinct));
        }
        return 0.25;
    }

    private double comparisonCondition(Ast.Compare cmp, TableMeta table) {
        if (cmp.value() == null) {
            return 0.0;
        }
        Column column = table.column(cmp.column());
        if (column == null) {
            return 0.25;
        }
        long distinct = distinct(table, column.ordinal());
        return switch (cmp.op()) {
            case EQ -> clamp(1.0 / Math.max(1, distinct));
            case NE -> clamp(1 - 1.0 / Math.max(1, distinct));
            case LT, LE -> rangeSelectivityCondition(table, column, cmp, cmp.op() == Ast.Op.LE);
            case GT, GE -> {
                double p = rangeSelectivityCondition(table, column, cmp, cmp.op() == Ast.Op.GE);
                yield clamp(1 - p);
            }
        };
    }

    private double rangeSelectivityCondition(TableMeta table, Column column, Ast.Compare cmp,
                                             boolean inclusive) {
        long rows = rowCount(table);
        Histogram histogram = histogram(table, column.name());
        if (histogram == null || histogram.isEmpty() || rows <= 0) {
            return StatsService.DEFAULT_RANGE_SELECTIVITY;
        }
        long n = inclusive
                ? histogram.countLessThanOrEqual(cmp.value(), column.type())
                : histogram.countLessThan(cmp.value(), column.type());
        return clamp((double) n / rows);
    }

    /** 主键/索引范围的行数估算(直方图不可用时 1/3)。 */
    public long estimateRangeRows(TableMeta table, Column column, Object lower, boolean hasLower,
                                  Object upper, boolean hasUpper) {
        long rows = rowCount(table);
        if (rows == 0) {
            return 0;
        }
        Histogram histogram = histogram(table, column.name());
        if (histogram == null || histogram.isEmpty()) {
            return Math.max(1, Math.round(rows * StatsService.DEFAULT_RANGE_SELECTIVITY));
        }
        long le = hasUpper
                ? histogram.countLessThanOrEqual(upper, column.type()) : rows;
        long lt = hasLower
                ? histogram.countLessThan(lower, column.type()) : 0;
        return Math.max(0, le - lt);
    }

    /** 等值命中的行数(索引点查):row_count / distinct。 */
    public long estimateEqualRows(TableMeta table, StatColumn column) {
        long rows = rowCount(table);
        if (rows == 0) {
            return 0;
        }
        long distinct = distinct(table, column.name());
        return Math.max(1, Math.round((double) rows / Math.max(1, distinct)));
    }

    /** 列名 + 类型(避免 Estimator 与调用方各查一遍 catalog)。 */
    public record StatColumn(String name, DataType type) {
    }

    public StatColumn column(TableMeta table, String name) {
        Column column = table.column(name);
        return column == null ? null : new StatColumn(column.name(), column.type());
    }

    private static double clamp(double p) {
        if (Double.isNaN(p)) {
            return 0;
        }
        return Math.max(0, Math.min(1, p));
    }
}
