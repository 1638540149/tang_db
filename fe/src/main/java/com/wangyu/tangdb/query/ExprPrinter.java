package com.wangyu.tangdb.query;

import com.wangyu.tangdb.parser.Ast;
import com.wangyu.tangdb.storage.Values;

import java.util.List;
import java.util.Locale;

/**
 * 把表达式还原成 SQL 文本(SPEC-P5 K107:表达式投影的列名;EXPLAIN 展示)。
 */
public final class ExprPrinter {

    private ExprPrinter() {
    }

    public static String print(Ast.Expr expr) {
        if (expr == null) {
            return "";
        }
        if (expr instanceof Ast.Lit lit) {
            return lit.text() != null ? lit.text() : literalText(lit.value());
        }
        if (expr instanceof Ast.Col col) {
            return col.qualifier() == null ? col.name() : col.qualifier() + "." + col.name();
        }
        if (expr instanceof Ast.Arith e) {
            return print(e.left()) + " " + e.op().text() + " " + print(e.right());
        }
        if (expr instanceof Ast.Neg e) {
            return "-" + wrap(e.operand());
        }
        if (expr instanceof Ast.Not e) {
            return "NOT " + wrap(e.operand());
        }
        if (expr instanceof Ast.Cmp e) {
            return print(e.left()) + " " + e.op().text() + " " + print(e.right());
        }
        if (expr instanceof Ast.LogicalAnd e) {
            return print(e.left()) + " AND " + print(e.right());
        }
        if (expr instanceof Ast.LogicalOr e) {
            return print(e.left()) + " OR " + print(e.right());
        }
        if (expr instanceof Ast.IsNull e) {
            return print(e.operand()) + (e.negated() ? " IS NOT NULL" : " IS NULL");
        }
        if (expr instanceof Ast.Like e) {
            return print(e.operand()) + " LIKE " + print(e.pattern());
        }
        if (expr instanceof Ast.Between e) {
            return print(e.operand()) + (e.negated() ? " NOT BETWEEN " : " BETWEEN ")
                    + print(e.lower()) + " AND " + print(e.upper());
        }
        if (expr instanceof Ast.InExpr e) {
            StringBuilder sb = new StringBuilder(print(e.operand()))
                    .append(e.negated() ? " NOT IN (" : " IN (");
            for (int i = 0; i < e.values().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(print(e.values().get(i)));
            }
            return sb.append(')').toString();
        }
        if (expr instanceof Ast.InSubquery e) {
            return print(e.operand()) + (e.negated() ? " NOT IN (SELECT ...)" : " IN (SELECT ...)");
        }
        if (expr instanceof Ast.ScalarSubquery) {
            return "(SELECT ...)";
        }
        if (expr instanceof Ast.Func f) {
            StringBuilder sb = new StringBuilder(f.name()).append('(');
            if (f.star()) {
                sb.append('*');
            } else {
                if (f.distinct()) {
                    sb.append("DISTINCT ");
                }
                for (int i = 0; i < f.args().size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(print(f.args().get(i)));
                }
            }
            return sb.append(')').toString();
        }
        if (expr instanceof Ast.Case c) {
            StringBuilder sb = new StringBuilder("CASE");
            if (c.base() != null) {
                sb.append(' ').append(print(c.base()));
            }
            for (Ast.When when : c.whens()) {
                sb.append(" WHEN ").append(print(when.condition()))
                        .append(" THEN ").append(print(when.result()));
            }
            if (c.otherwise() != null) {
                sb.append(" ELSE ").append(print(c.otherwise()));
            }
            return sb.append(" END").toString();
        }
        if (expr instanceof Ast.Cast c) {
            return "CAST(" + print(c.operand()) + " AS " + c.type().sqlName() + ")";
        }
        return "?";
    }

    private static String wrap(Ast.Expr expr) {
        String text = print(expr);
        if (expr instanceof Ast.Lit || expr instanceof Ast.Col || expr instanceof Ast.Func) {
            return text;
        }
        return "(" + text + ")";
    }

    private static String literalText(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof String s) {
            return "'" + s.replace("'", "''") + "'";
        }
        if (value instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        return Values.display(value);
    }

    /** 输出列名(K107):有别名用别名,否则用表达式原文。 */
    public static List<String> names(List<Ast.SelectItem> items) {
        java.util.List<String> names = new java.util.ArrayList<>(items.size());
        for (Ast.SelectItem item : items) {
            if (item.alias() != null) {
                names.add(item.alias().toLowerCase(Locale.ROOT));
            } else if (item.star()) {
                names.add("*");
            } else {
                names.add(print(item.expr()));
            }
        }
        return names;
    }
}
