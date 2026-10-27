package com.wangyu.tangdb.query;

import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.parser.Ast;

import java.util.ArrayList;
import java.util.List;

/**
 * 按列求值的向量化表达式执行器(SPEC-P5 FR-572 / FR-574)。
 *
 * <p>所有中间结果都是 {@link Batch.Column}(values + validity 位图),一次处理整列;
 * NULL 只在位图上运算,不按行判断(FR-573)。</p>
 */
public final class BatchEvaluator {

    private final java.util.Map<Ast.Expr, Object> subqueryValues;

    public BatchEvaluator(java.util.Map<Ast.Expr, Object> subqueryValues) {
        this.subqueryValues = subqueryValues;
    }

    /** 求值一整列。 */
    public Batch.Column eval(Ast.Expr expr, Batch batch, Schema schema) {
        int n = batch.rows;
        if (expr instanceof Ast.Col col) {
            return batch.column(schema.resolve(col.qualifier(), col.name()));
        }
        if (expr instanceof Ast.Lit lit) {
            Object[] values = new Object[n];
            boolean[] valid = new boolean[n];
            for (int i = 0; i < n; i++) {
                values[i] = lit.value();
                valid[i] = lit.value() != null;
            }
            return new Batch.Column(values, valid);
        }
        if (expr instanceof Ast.Arith e) {
            Batch.Column l = eval(e.left(), batch, schema);
            Batch.Column r = eval(e.right(), batch, schema);
            return combine(n, l, r, (a, b) -> ValueOps.arith(e.op(), a, b));
        }
        if (expr instanceof Ast.Neg e) {
            return combine(n, eval(e.operand(), batch, schema), null,
                    (a, b) -> ValueOps.negate(a));
        }
        if (expr instanceof Ast.Not e) {
            return combine(n, eval(e.operand(), batch, schema), null, (a, b) -> {
                Boolean t = ValueOps.truth(a);
                return t == null ? null : !t;
            });
        }
        if (expr instanceof Ast.Cmp e) {
            Batch.Column l = eval(e.left(), batch, schema);
            Batch.Column r = eval(e.right(), batch, schema);
            return combine(n, l, r, (a, b) -> {
                if (a == null || b == null) {
                    return null;
                }
                int c = ValueOps.compare(a, b);
                return switch (e.op()) {
                    case EQ -> c == 0;
                    case NE -> c != 0;
                    case LT -> c < 0;
                    case LE -> c <= 0;
                    case GT -> c > 0;
                    case GE -> c >= 0;
                };
            });
        }
        if (expr instanceof Ast.LogicalAnd e) {
            Batch.Column l = eval(e.left(), batch, schema);
            Batch.Column r = eval(e.right(), batch, schema);
            return combine(n, l, r, (a, b) -> and(ValueOps.truth(a), ValueOps.truth(b)));
        }
        if (expr instanceof Ast.LogicalOr e) {
            Batch.Column l = eval(e.left(), batch, schema);
            Batch.Column r = eval(e.right(), batch, schema);
            return combine(n, l, r, (a, b) -> or(ValueOps.truth(a), ValueOps.truth(b)));
        }
        if (expr instanceof Ast.IsNull e) {
            Batch.Column v = eval(e.operand(), batch, schema);
            Object[] values = new Object[n];
            boolean[] valid = new boolean[n];
            for (int i = 0; i < n; i++) {
                boolean isNull = !v.valid()[i] || v.values()[i] == null;
                values[i] = e.negated() ? !isNull : isNull;
                valid[i] = true;
            }
            return new Batch.Column(values, valid);
        }
        if (expr instanceof Ast.Like e) {
            Batch.Column v = eval(e.operand(), batch, schema);
            Batch.Column p = eval(e.pattern(), batch, schema);
            return combine(n, v, p, (a, b) -> {
                if (a == null || b == null) {
                    return null;
                }
                String text = a instanceof byte[] bytes
                        ? JsonCodec.toText(JsonCodec.decode(bytes))
                        : com.wangyu.tangdb.storage.Values.display(a);
                return ValueOps.like(text, com.wangyu.tangdb.storage.Values.display(b),
                        e.escape());
            });
        }
        if (expr instanceof Ast.Between e) {
            Batch.Column v = eval(e.operand(), batch, schema);
            Batch.Column lo = eval(e.lower(), batch, schema);
            Batch.Column hi = eval(e.upper(), batch, schema);
            Object[] values = new Object[n];
            boolean[] valid = new boolean[n];
            for (int i = 0; i < n; i++) {
                Object a = v.get(i);
                Object l = lo.get(i);
                Object h = hi.get(i);
                if (a == null || l == null || h == null) {
                    valid[i] = false;
                    continue;
                }
                boolean in = ValueOps.compare(a, l) >= 0 && ValueOps.compare(a, h) <= 0;
                values[i] = e.negated() ? !in : in;
                valid[i] = true;
            }
            return new Batch.Column(values, valid);
        }
        if (expr instanceof Ast.InExpr e) {
            Batch.Column v = eval(e.operand(), batch, schema);
            List<Batch.Column> candidates = new ArrayList<>(e.values().size());
            for (Ast.Expr item : e.values()) {
                candidates.add(eval(item, batch, schema));
            }
            Object[] values = new Object[n];
            boolean[] valid = new boolean[n];
            for (int i = 0; i < n; i++) {
                Object a = v.get(i);
                if (a == null) {
                    valid[i] = false;
                    continue;
                }
                boolean sawNull = false;
                boolean match = false;
                for (Batch.Column c : candidates) {
                    Object b = c.get(i);
                    if (b == null) {
                        sawNull = true;
                    } else if (ValueOps.compare(a, b) == 0) {
                        match = true;
                        break;
                    }
                }
                if (match) {
                    values[i] = !e.negated();
                    valid[i] = true;
                } else if (sawNull) {
                    valid[i] = false;
                } else {
                    values[i] = e.negated();
                    valid[i] = true;
                }
            }
            return new Batch.Column(values, valid);
        }
        if (expr instanceof Ast.InSubquery e) {
            Batch.Column v = eval(e.operand(), batch, schema);
            Object cached = subqueryValues.get(expr);
            if (!(cached instanceof InSet set)) {
                throw new SqlError(SqlError.ER_UNKNOWN_ERROR, "HY000", "IN 子查询结果缺失");
            }
            Object[] values = new Object[n];
            boolean[] valid = new boolean[n];
            for (int i = 0; i < n; i++) {
                Object a = v.get(i);
                if (a == null) {
                    valid[i] = false;
                    continue;
                }
                if (set.contains(a)) {
                    values[i] = !e.negated();
                    valid[i] = true;
                } else if (set.hasNull()) {
                    valid[i] = false;
                } else {
                    values[i] = e.negated();
                    valid[i] = true;
                }
            }
            return new Batch.Column(values, valid);
        }
        if (expr instanceof Ast.ScalarSubquery) {
            Object v = subqueryValues.get(expr);
            Object[] values = new Object[n];
            boolean[] valid = new boolean[n];
            for (int i = 0; i < n; i++) {
                values[i] = v;
                valid[i] = v != null;
            }
            return new Batch.Column(values, valid);
        }
        if (expr instanceof Ast.Func f) {
            List<Batch.Column> args = new ArrayList<>(f.args().size());
            for (Ast.Expr arg : f.args()) {
                args.add(eval(arg, batch, schema));
            }
            Object[] values = new Object[n];
            boolean[] valid = new boolean[n];
            for (int i = 0; i < n; i++) {
                Object[] rowArgs = new Object[args.size()];
                for (int j = 0; j < args.size(); j++) {
                    rowArgs[j] = args.get(j).get(i);
                }
                Object out = Functions.call(f.name(), rowArgs);
                values[i] = out;
                valid[i] = out != null;
            }
            return new Batch.Column(values, valid);
        }
        if (expr instanceof Ast.Case e) {
            return evalCase(e, batch, schema);
        }
        if (expr instanceof Ast.Cast e) {
            Batch.Column v = eval(e.operand(), batch, schema);
            Object[] values = new Object[n];
            boolean[] valid = new boolean[n];
            for (int i = 0; i < n; i++) {
                Object out = Functions.cast(v.get(i), e.type());
                values[i] = out;
                valid[i] = out != null;
            }
            return new Batch.Column(values, valid);
        }
        throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                "不支持的表达式: " + ExprPrinter.print(expr));
    }

    private Batch.Column evalCase(Ast.Case e, Batch batch, Schema schema) {
        int n = batch.rows;
        Batch.Column base = e.base() == null ? null : eval(e.base(), batch, schema);
        List<Batch.Column> conds = new ArrayList<>(e.whens().size());
        List<Batch.Column> results = new ArrayList<>(e.whens().size());
        for (Ast.When when : e.whens()) {
            conds.add(eval(when.condition(), batch, schema));
            results.add(eval(when.result(), batch, schema));
        }
        Batch.Column other = e.otherwise() == null ? null : eval(e.otherwise(), batch, schema);
        Object[] values = new Object[n];
        boolean[] valid = new boolean[n];
        for (int i = 0; i < n; i++) {
            Object out = null;
            boolean matched = false;
            if (base != null) {
                Object b = base.get(i);
                if (b != null) {
                    for (int k = 0; k < conds.size(); k++) {
                        Object c = conds.get(k).get(i);
                        if (c != null && ValueOps.compare(b, c) == 0) {
                            out = results.get(k).get(i);
                            matched = true;
                            break;
                        }
                    }
                }
            } else {
                for (int k = 0; k < conds.size(); k++) {
                    if (Boolean.TRUE.equals(ValueOps.truth(conds.get(k).get(i)))) {
                        out = results.get(k).get(i);
                        matched = true;
                        break;
                    }
                }
            }
            if (!matched && other != null) {
                out = other.get(i);
            }
            values[i] = out;
            valid[i] = out != null;
        }
        return new Batch.Column(values, valid);
    }

    private static Batch.Column combine(int n, Batch.Column left, Batch.Column right,
                                        java.util.function.BiFunction<Object, Object, Object> fn) {
        Object[] values = new Object[n];
        boolean[] valid = new boolean[n];
        for (int i = 0; i < n; i++) {
            Object a = left.get(i);
            Object b = right == null ? null : right.get(i);
            if (right != null && (a == null || b == null)) {
                valid[i] = false;
                continue;
            }
            Object out = fn.apply(a, b);
            values[i] = out;
            valid[i] = out != null;
        }
        return new Batch.Column(values, valid);
    }

    private static Boolean and(Boolean a, Boolean b) {
        if (Boolean.FALSE.equals(a) || Boolean.FALSE.equals(b)) {
            return false;
        }
        return (a == null || b == null) ? null : true;
    }

    private static Boolean or(Boolean a, Boolean b) {
        if (Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b)) {
            return true;
        }
        return (a == null || b == null) ? null : false;
    }
}
