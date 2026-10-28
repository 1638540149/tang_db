package com.wangyu.tangdb.query;

import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.parser.Ast;
import com.wangyu.tangdb.storage.DataType;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 逐行表达式求值(SPEC-P5 B 组"朴素逐行路径",与向量化路径对拍,FR-576)。
 *
 * <p>三值逻辑:NULL 参与比较得 UNKNOWN;WHERE 只放行 TRUE(FR-594)。</p>
 */
public final class Evaluator {

    /** 聚合结果(分组之后按 AST 节点身份取)。 */
    private final Map<Ast.Expr, Object> aggregateValues;
    /** 子查询结果(标量值 / 集合)。 */
    private final Map<Ast.Expr, Object> subqueryValues;
    /** 分组查询里"聚合节点 → 结果列序号"(按行取值,避免跨组串值)。 */
    private Map<Ast.Expr, Integer> aggregateColumns = Map.of();
    private final Warnings warnings;
    /** 子查询取值回调(SPEC-P6 FR-656:相关子查询需要"当前外层行"才能求值)。 */
    private SubqueryResolver subqueryResolver;

    /** 子查询求值:不相关的用预求值结果,相关的按当前行驱动。 */
    public interface SubqueryResolver {
        Object resolve(Ast.Expr subquery, Object[] row, Schema schema);
    }

    public Evaluator withSubqueryResolver(SubqueryResolver resolver) {
        if (resolver != null) {
            this.subqueryResolver = resolver;
        }
        return this;
    }

    public Evaluator() {
        this(new IdentityHashMap<>(), new IdentityHashMap<>(), Warnings.NONE);
    }

    public Evaluator(Map<Ast.Expr, Object> aggregateValues,
                     Map<Ast.Expr, Object> subqueryValues, Warnings warnings) {
        this.aggregateValues = aggregateValues;
        this.subqueryValues = subqueryValues;
        this.warnings = warnings;
        this.subqueryResolver = (sub, row, schema) -> subqueryValues.get(sub);
    }

    /** 设置"聚合节点 → 结果列序号";聚合结果从当前行里取。 */
    public Evaluator withAggregateColumns(Map<Ast.Expr, Integer> columns) {
        this.aggregateColumns = columns;
        return this;
    }

    /** 告警出口(GROUP_CONCAT 截断等,边界 63)。 */
    public interface Warnings {
        Warnings NONE = message -> {
        };

        void warn(String message);
    }

    public Object eval(Ast.Expr expr, Object[] row, Schema schema) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof Ast.Lit lit) {
            return lit.value();
        }
        if (expr instanceof Ast.Col col) {
            return row[schema.resolve(col.qualifier(), col.name())];
        }
        if (expr instanceof Ast.Arith arith) {
            return ValueOps.arith(arith.op(), eval(arith.left(), row, schema),
                    eval(arith.right(), row, schema));
        }
        if (expr instanceof Ast.Neg neg) {
            return ValueOps.negate(eval(neg.operand(), row, schema));
        }
        if (expr instanceof Ast.Not not) {
            Boolean t = ValueOps.truth(eval(not.operand(), row, schema));
            return t == null ? null : !t;
        }
        if (expr instanceof Ast.Cmp cmp) {
            Object left = eval(cmp.left(), row, schema);
            Object right = eval(cmp.right(), row, schema);
            if (left == null || right == null) {
                return null;
            }
            int c = ValueOps.compare(left, right);
            return switch (cmp.op()) {
                case EQ -> c == 0;
                case NE -> c != 0;
                case LT -> c < 0;
                case LE -> c <= 0;
                case GT -> c > 0;
                case GE -> c >= 0;
            };
        }
        if (expr instanceof Ast.LogicalAnd and) {
            Boolean left = ValueOps.truth(eval(and.left(), row, schema));
            if (Boolean.FALSE.equals(left)) {
                return false;
            }
            Boolean right = ValueOps.truth(eval(and.right(), row, schema));
            if (Boolean.FALSE.equals(right)) {
                return false;
            }
            return (left == null || right == null) ? null : true;
        }
        if (expr instanceof Ast.LogicalOr or) {
            Boolean left = ValueOps.truth(eval(or.left(), row, schema));
            if (Boolean.TRUE.equals(left)) {
                return true;
            }
            Boolean right = ValueOps.truth(eval(or.right(), row, schema));
            if (Boolean.TRUE.equals(right)) {
                return true;
            }
            return (left == null || right == null) ? null : false;
        }
        if (expr instanceof Ast.IsNull isNull) {
            boolean isNullValue = eval(isNull.operand(), row, schema) == null;
            return isNull.negated() ? !isNullValue : isNullValue;
        }
        if (expr instanceof Ast.Like like) {
            Object value = eval(like.operand(), row, schema);
            Object pattern = eval(like.pattern(), row, schema);
            if (value == null || pattern == null) {
                return null;
            }
            String text = value instanceof byte[] b ? JsonCodec.toText(JsonCodec.decode(b))
                    : com.wangyu.tangdb.storage.Values.display(value);
            return ValueOps.like(text, com.wangyu.tangdb.storage.Values.display(pattern),
                    like.escape());
        }
        if (expr instanceof Ast.Between between) {
            Object value = eval(between.operand(), row, schema);
            Object lower = eval(between.lower(), row, schema);
            Object upper = eval(between.upper(), row, schema);
            if (value == null || lower == null || upper == null) {
                return null;
            }
            boolean in = ValueOps.compare(value, lower) >= 0 && ValueOps.compare(value, upper) <= 0;
            return between.negated() ? !in : in;
        }
        if (expr instanceof Ast.InExpr in) {
            Object value = eval(in.operand(), row, schema);
            if (value == null) {
                return null;
            }
            boolean sawNull = false;
            boolean match = false;
            for (Ast.Expr item : in.values()) {
                Object candidate = eval(item, row, schema);
                if (candidate == null) {
                    sawNull = true;
                } else if (ValueOps.compare(value, candidate) == 0) {
                    match = true;
                    break;
                }
            }
            if (match) {
                return !in.negated();
            }
            if (sawNull) {
                return null;
            }
            return in.negated();
        }
        if (expr instanceof Ast.InSubquery in) {
            Object value = eval(in.operand(), row, schema);
            if (value == null) {
                return null;
            }
            Object cached = subqueryResolver.resolve(expr, row, schema);
            if (!(cached instanceof InSet set)) {
                throw new SqlError(SqlError.ER_UNKNOWN_ERROR, "HY000", "IN 子查询结果缺失");
            }
            if (set.contains(value)) {
                return !in.negated();
            }
            if (set.hasNull()) {
                return null;
            }
            return in.negated();
        }
        if (expr instanceof Ast.ScalarSubquery) {
            return subqueryResolver.resolve(expr, row, schema);
        }
        if (expr instanceof Ast.Exists exists) {
            Object value = subqueryResolver.resolve(exists, row, schema);
            return Boolean.TRUE.equals(value);
        }
        if (expr instanceof Ast.Quantified quantified) {
            return quantified(quantified, row, schema);
        }
        if (expr instanceof Ast.Func func) {
            if (Functions.isAggregate(func.name())) {
                Integer column = aggregateColumns.get(expr);
                if (column != null) {
                    return row[column];
                }
                if (!aggregateValues.containsKey(expr)) {
                    throw new SqlError(SqlError.ER_MIX_OF_GROUP_FUNC_AND_FIELDS, "42000",
                            "聚合函数 " + func.name() + " 只能在聚合查询里使用");
                }
                return aggregateValues.get(expr);
            }
            Object[] args = new Object[func.args().size()];
            for (int i = 0; i < args.length; i++) {
                args[i] = eval(func.args().get(i), row, schema);
            }
            return Functions.call(func.name(), args);
        }
        if (expr instanceof Ast.Case caseExpr) {
            if (caseExpr.base() != null) {
                Object base = eval(caseExpr.base(), row, schema);
                for (Ast.When when : caseExpr.whens()) {
                    Object candidate = eval(when.condition(), row, schema);
                    if (base != null && candidate != null && ValueOps.compare(base, candidate) == 0) {
                        return eval(when.result(), row, schema);
                    }
                }
            } else {
                for (Ast.When when : caseExpr.whens()) {
                    if (Boolean.TRUE.equals(ValueOps.truth(eval(when.condition(), row, schema)))) {
                        return eval(when.result(), row, schema);
                    }
                }
            }
            return caseExpr.otherwise() == null ? null : eval(caseExpr.otherwise(), row, schema);
        }
        if (expr instanceof Ast.Cast cast) {
            return Functions.cast(eval(cast.operand(), row, schema), cast.type());
        }
        throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                "不支持的表达式: " + ExprPrinter.print(expr));
    }

    /**
     * 比较量词的三值语义(SPEC-P6 FR-658):
     *
     * <pre>
     * x op ANY(S): S 空 → FALSE;任一 TRUE → TRUE;有 NULL 无匹配 → NULL;否则 FALSE
     * x op ALL(S): S 空 → TRUE;任一 FALSE → FALSE;有 NULL 无 FALSE → NULL;否则 TRUE
     * </pre>
     */
    private Object quantified(Ast.Quantified quantified, Object[] row, Schema schema) {
        Object left = eval(quantified.operand(), row, schema);
        Object resolved = subqueryResolver.resolve(quantified, row, schema);
        if (!(resolved instanceof List<?> values)) {
            throw new SqlError(SqlError.ER_UNKNOWN_ERROR, "HY000", "量词子查询结果缺失");
        }
        boolean any = quantified.quantifier() == Ast.Quantifier.ANY;
        if (values.isEmpty()) {
            return !any; // ANY 空集 FALSE;ALL 空集 TRUE
        }
        boolean sawNull = false;
        for (Object value : values) {
            if (left == null || value == null) {
                sawNull = true;
                continue;
            }
            int c = ValueOps.compare(left, value);
            boolean matched = switch (quantified.op()) {
                case EQ -> c == 0;
                case NE -> c != 0;
                case LT -> c < 0;
                case LE -> c <= 0;
                case GT -> c > 0;
                case GE -> c >= 0;
            };
            if (any && matched) {
                return true;
            }
            if (!any && !matched) {
                return false;
            }
        }
        return sawNull ? null : !any;
    }

    /** 解析表达式的列引用,提前校验(不存在 / 歧义在绑定阶段报错)。 */
    public static void validate(Ast.Expr expr, Schema schema) {
        if (expr == null) {
            return;
        }
        if (expr instanceof Ast.Col col) {
            schema.resolve(col.qualifier(), col.name());
            return;
        }
        // 聚合参数在外层(源 schema)已经校验过;这里只校验聚合结果列。
        if (expr instanceof Ast.Func func && Functions.isAggregate(func.name())) {
            return;
        }
        if (expr instanceof Ast.InSubquery || expr instanceof Ast.ScalarSubquery
                || expr instanceof Ast.Exists || expr instanceof Ast.Quantified) {
            return;
        }
        for (Ast.Expr child : children(expr)) {
            validate(child, schema);
        }
    }

    public static List<Ast.Expr> children(Ast.Expr expr) {
        if (expr instanceof Ast.Arith e) {
            return List.of(e.left(), e.right());
        }
        if (expr instanceof Ast.Neg e) {
            return List.of(e.operand());
        }
        if (expr instanceof Ast.Not e) {
            return List.of(e.operand());
        }
        if (expr instanceof Ast.Cmp e) {
            return List.of(e.left(), e.right());
        }
        if (expr instanceof Ast.LogicalAnd e) {
            return List.of(e.left(), e.right());
        }
        if (expr instanceof Ast.LogicalOr e) {
            return List.of(e.left(), e.right());
        }
        if (expr instanceof Ast.IsNull e) {
            return List.of(e.operand());
        }
        if (expr instanceof Ast.Like e) {
            return List.of(e.operand(), e.pattern());
        }
        if (expr instanceof Ast.Between e) {
            return List.of(e.operand(), e.lower(), e.upper());
        }
        if (expr instanceof Ast.InExpr e) {
            java.util.List<Ast.Expr> out = new java.util.ArrayList<>(e.values().size() + 1);
            out.add(e.operand());
            out.addAll(e.values());
            return out;
        }
        if (expr instanceof Ast.InSubquery e) {
            return List.of(e.operand());
        }
        if (expr instanceof Ast.Exists) {
            return List.of();
        }
        if (expr instanceof Ast.Quantified e) {
            return List.of(e.operand());
        }
        if (expr instanceof Ast.Func e) {
            return e.args();
        }
        if (expr instanceof Ast.Case e) {
            java.util.List<Ast.Expr> out = new java.util.ArrayList<>();
            if (e.base() != null) {
                out.add(e.base());
            }
            for (Ast.When when : e.whens()) {
                out.add(when.condition());
                out.add(when.result());
            }
            if (e.otherwise() != null) {
                out.add(e.otherwise());
            }
            return out;
        }
        if (expr instanceof Ast.Cast e) {
            return List.of(e.operand());
        }
        return List.of();
    }

    /** 收集表达式里的聚合节点(同一 AST 节点只收一次)。 */
    public static void collectAggregates(Ast.Expr expr, Map<Ast.Expr, Ast.Func> out) {
        if (expr == null) {
            return;
        }
        if (expr instanceof Ast.Func func && Functions.isAggregate(func.name())) {
            out.putIfAbsent(expr, func);
            return;
        }
        for (Ast.Expr child : children(expr)) {
            collectAggregates(child, out);
        }
    }

    /** 收集子查询节点。 */
    public static void collectSubqueries(Ast.Expr expr, List<Ast.Expr> out) {
        if (expr == null) {
            return;
        }
        if (expr instanceof Ast.InSubquery || expr instanceof Ast.ScalarSubquery
                || expr instanceof Ast.Exists || expr instanceof Ast.Quantified) {
            out.add(expr);
            return;
        }
        for (Ast.Expr child : children(expr)) {
            collectSubqueries(child, out);
        }
    }

    /** 是否含列引用(给"常量投影"判断用)。 */
    public static boolean hasColumn(Ast.Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof Ast.Col) {
            return true;
        }
        for (Ast.Expr child : children(expr)) {
            if (hasColumn(child)) {
                return true;
            }
        }
        return false;
    }

    static DataType typeOf(Ast.Expr expr) {
        if (expr instanceof Ast.Cast cast) {
            return cast.type();
        }
        return DataType.VARCHAR;
    }
}
