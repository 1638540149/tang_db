package com.wangyu.tangdb.query;

import com.wangyu.tangdb.parser.Ast;

import java.util.ArrayList;
import java.util.List;

/**
 * 常量折叠与布尔短路(SPEC-P6 FR-646)。
 *
 * <p>在建计划阶段折叠,不进执行期:</p>
 * <ul>
 *   <li>{@code NOT NOT x} → {@code x};</li>
 *   <li>{@code 1 = 1} / {@code 1 = 2} → 恒真 / 恒假;</li>
 *   <li>{@code amount / 0} → NULL(K109);</li>
 *   <li>{@code TRUE AND x} / {@code FALSE OR x} 等布尔短路。</li>
 * </ul>
 *
 * <p>只折叠**无副作用**的字面量运算;含列 / 子查询 / 聚合的节点原样返回(其子节点仍会被折叠)。
 * 三值逻辑保持:NULL 参与比较恒为 UNKNOWN。</p>
 */
public final class ExprOptimizer {

    private ExprOptimizer() {
    }

    public static Ast.Expr fold(Ast.Expr expr) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof Ast.Lit || expr instanceof Ast.Col) {
            return expr;
        }
        if (expr instanceof Ast.Not not) {
            Ast.Expr operand = fold(not.operand());
            if (operand instanceof Ast.Not inner) {
                return fold(inner.operand());
            }
            if (operand instanceof Ast.Lit lit) {
                Boolean truth = ValueOps.truth(lit.value());
                return new Ast.Lit(truth == null ? null : !truth, null);
            }
            return new Ast.Not(operand);
        }
        if (expr instanceof Ast.Arith arith) {
            Ast.Expr left = fold(arith.left());
            Ast.Expr right = fold(arith.right());
            if (left instanceof Ast.Lit a && right instanceof Ast.Lit b) {
                if (a.value() == null || b.value() == null) {
                    return new Ast.Lit(null, "NULL");
                }
                try {
                    Object value = ValueOps.arith(arith.op(), a.value(), b.value());
                    return new Ast.Lit(value, null);
                } catch (RuntimeException e) {
                    return new Ast.Arith(arith.op(), left, right); // 类型问题留给执行期报准错
                }
            }
            return new Ast.Arith(arith.op(), left, right);
        }
        if (expr instanceof Ast.Neg neg) {
            Ast.Expr operand = fold(neg.operand());
            if (operand instanceof Ast.Lit lit) {
                if (lit.value() == null) {
                    return new Ast.Lit(null, "NULL");
                }
                try {
                    return new Ast.Lit(ValueOps.negate(lit.value()), null);
                } catch (RuntimeException e) {
                    return new Ast.Neg(operand);
                }
            }
            return new Ast.Neg(operand);
        }
        if (expr instanceof Ast.Cmp cmp) {
            Ast.Expr left = fold(cmp.left());
            Ast.Expr right = fold(cmp.right());
            if (left instanceof Ast.Lit a && right instanceof Ast.Lit b) {
                if (a.value() == null || b.value() == null) {
                    return new Ast.Lit(null, "NULL");
                }
                try {
                    int c = ValueOps.compare(a.value(), b.value());
                    boolean value = switch (cmp.op()) {
                        case EQ -> c == 0;
                        case NE -> c != 0;
                        case LT -> c < 0;
                        case LE -> c <= 0;
                        case GT -> c > 0;
                        case GE -> c >= 0;
                    };
                    return new Ast.Lit(value, value ? "TRUE" : "FALSE");
                } catch (RuntimeException e) {
                    return new Ast.Cmp(cmp.op(), left, right);
                }
            }
            return new Ast.Cmp(cmp.op(), left, right);
        }
        if (expr instanceof Ast.LogicalAnd and) {
            Ast.Expr left = fold(and.left());
            Ast.Expr right = fold(and.right());
            Boolean l = literalTruth(left);
            Boolean r = literalTruth(right);
            if (Boolean.FALSE.equals(l) || Boolean.FALSE.equals(r)) {
                return new Ast.Lit(false, "FALSE");
            }
            if (Boolean.TRUE.equals(l)) {
                return right;
            }
            if (Boolean.TRUE.equals(r)) {
                return left;
            }
            return new Ast.LogicalAnd(left, right);
        }
        if (expr instanceof Ast.LogicalOr or) {
            Ast.Expr left = fold(or.left());
            Ast.Expr right = fold(or.right());
            Boolean l = literalTruth(left);
            Boolean r = literalTruth(right);
            if (Boolean.TRUE.equals(l) || Boolean.TRUE.equals(r)) {
                return new Ast.Lit(true, "TRUE");
            }
            if (Boolean.FALSE.equals(l)) {
                return right;
            }
            if (Boolean.FALSE.equals(r)) {
                return left;
            }
            return new Ast.LogicalOr(left, right);
        }
        if (expr instanceof Ast.IsNull isNull) {
            Ast.Expr operand = fold(isNull.operand());
            if (operand instanceof Ast.Lit lit) {
                boolean value = lit.value() == null;
                return new Ast.Lit(isNull.negated() != value, null);
            }
            return new Ast.IsNull(operand, isNull.negated());
        }
        if (expr instanceof Ast.Between between) {
            return new Ast.Between(fold(between.operand()), fold(between.lower()),
                    fold(between.upper()), between.negated());
        }
        if (expr instanceof Ast.Like like) {
            return new Ast.Like(fold(like.operand()), fold(like.pattern()), like.escape());
        }
        if (expr instanceof Ast.InExpr in) {
            List<Ast.Expr> values = new ArrayList<>(in.values().size());
            for (Ast.Expr value : in.values()) {
                values.add(fold(value));
            }
            return new Ast.InExpr(fold(in.operand()), values, in.negated());
        }
        if (expr instanceof Ast.Case caseExpr) {
            List<Ast.When> whens = new ArrayList<>(caseExpr.whens().size());
            for (Ast.When when : caseExpr.whens()) {
                whens.add(new Ast.When(fold(when.condition()), fold(when.result())));
            }
            return new Ast.Case(fold(caseExpr.base()), whens, fold(caseExpr.otherwise()));
        }
        if (expr instanceof Ast.Cast cast) {
            Ast.Expr operand = fold(cast.operand());
            if (operand instanceof Ast.Lit lit && lit.value() != null) {
                try {
                    return new Ast.Lit(Functions.cast(lit.value(), cast.type()), null);
                } catch (RuntimeException e) {
                    return new Ast.Cast(operand, cast.type());
                }
            }
            return new Ast.Cast(operand, cast.type());
        }
        if (expr instanceof Ast.Func func) {
            List<Ast.Expr> args = new ArrayList<>(func.args().size());
            for (Ast.Expr arg : func.args()) {
                args.add(fold(arg));
            }
            return new Ast.Func(func.name(), args, func.distinct(), func.star());
        }
        // 子查询 / 未知形态原样返回
        return expr;
    }

    /** 字面量节点在布尔语境下的三值真值;非字面量返回 null(不是"UNKNOWN",是"不确定")。 */
    private static Boolean literalTruth(Ast.Expr expr) {
        if (!(expr instanceof Ast.Lit lit)) {
            return null;
        }
        return ValueOps.truth(lit.value());
    }
}
