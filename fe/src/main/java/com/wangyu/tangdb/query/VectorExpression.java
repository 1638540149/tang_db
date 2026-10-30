package com.wangyu.tangdb.query;

import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.parser.Ast;
import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.DataType;
import com.wangyu.tangdb.storage.StoreException;
import com.wangyu.tangdb.storage.TableMeta;
import com.wangyu.tangdb.storage.VectorValues;

import java.util.List;
import java.util.Locale;

/**
 * SPEC-P7 A 组的表达式侧约束:向量列只能进距离函数、{@code ai_embed} 只能进投影。
 *
 * <p>所有违规统一报 <b>1235</b>(FR-734 / FR-763 / FR-778 / FR-151),消息里说明正确用法,
 * 不留"看起来能跑但语义奇怪"的空子。</p>
 */
public final class VectorExpression {

    private VectorExpression() {
    }

    /** 距离函数里的"查询向量"部分:一列 + 一个字面量。 */
    public record QueryOperand(Column column, float[] vector) {
    }

    /**
     * 校验一条查询里向量 / 推理函数的用法。
     *
     * @param table 单表查询的表;多表 / 派生表传 null(只校验 ai_embed 的位置)
     */
    public static void validateUsage(Ast.QueryBody body, TableMeta table) {
        boolean[] isVector = vectorColumns(table);
        for (Ast.SelectItem item : body.items()) {
            check(item.expr(), table, isVector, Place.PROJECTION);
        }
        check(body.where(), table, isVector, Place.WHERE);
        check(body.having(), table, isVector, Place.HAVING);
        for (Ast.Expr group : body.groupBy()) {
            check(group, table, isVector, Place.GROUP_BY);
        }
        for (Ast.OrderItem order : body.orderBy()) {
            check(order.expr(), table, isVector, Place.ORDER_BY);
        }
    }

    private enum Place {
        PROJECTION("投影"), WHERE("WHERE"), GROUP_BY("GROUP BY"), ORDER_BY("ORDER BY"),
        HAVING("HAVING");

        private final String text;

        Place(String text) {
            this.text = text;
        }
    }

    private static boolean[] vectorColumns(TableMeta table) {
        if (table == null) {
            return new boolean[0];
        }
        boolean[] out = new boolean[table.columnCount()];
        for (int i = 0; i < out.length; i++) {
            out[i] = table.columns().get(i).type() == DataType.VECTOR;
        }
        return out;
    }

    private static void check(Ast.Expr expr, TableMeta table, boolean[] isVector, Place place) {
        if (expr == null) {
            return;
        }
        if (expr instanceof Ast.Func func) {
            if (Functions.isAiFunction(func.name())) {
                if (place != Place.PROJECTION) {
                    throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                            "ai_embed() 不能出现在 " + place.text
                                    + "(推理函数只允许出现在投影与 INSERT ... SELECT,FR-778)");
                }
                for (Ast.Expr arg : func.args()) {
                    check(arg, table, isVector, place);
                }
                return;
            }
            if (Functions.isDistance(func.name())) {
                checkDistanceArgs(func, table);
                return;
            }
        }
        if (expr instanceof Ast.Col col) {
            Column column = resolve(table, col, isVector);
            if (column != null && column.type() == DataType.VECTOR) {
                throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                        "VECTOR 列 " + column.name() + " 不能直接出现在 " + place.text
                                + "(只允许 l2_distance / cosine_distance / inner_product,"
                                + "FR-734 / FR-763)");
            }
            return;
        }
        for (Ast.Expr child : Evaluator.children(expr)) {
            check(child, table, isVector, place);
        }
    }

    /** 距离函数的两个参数必须是"向量列 / 向量字面量 / 另一向量列",不能是别的类型的列。 */
    private static void checkDistanceArgs(Ast.Func func, TableMeta table) {
        if (func.args().size() != 2) {
            throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                    func.name() + "() 需要两个参数(向量列 + 向量字面量,或两个向量列,FR-735)");
        }
        for (Ast.Expr arg : func.args()) {
            if (arg instanceof Ast.Lit) {
                continue;
            }
            if (arg instanceof Ast.Col col) {
                Column column = table == null ? null : table.column(col.name());
                if (column != null && column.type() != DataType.VECTOR) {
                    throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                            func.name() + "() 的参数必须是 VECTOR 列(实际是 " + column.typeName()
                                    + " 列 " + column.name() + ")");
                }
                // 未知列留给绑定阶段的 1054,这里不抢先报
                continue;
            }
            throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                    func.name() + "() 的参数只支持向量列或向量字面量 '[a,b,…]'");
        }
    }

    private static Column resolve(TableMeta table, Ast.Col col, boolean[] isVector) {
        if (table == null) {
            return null;
        }
        return table.column(col.name());
    }

    /** ORDER BY 里唯一一项是不是向量距离函数。 */
    public static Ast.Func distanceOrderBy(Ast.QueryBody body) {
        if (body.orderBy() == null || body.orderBy().size() != 1) {
            return null;
        }
        Ast.Expr expr = body.orderBy().get(0).expr();
        if (expr instanceof Ast.Func func && Functions.isDistance(func.name())) {
            return func;
        }
        return null;
    }

    /**
     * 从距离函数里取出"向量列 + 查询向量"。列在左、字面量在右(或反过来)都接受。
     *
     * @return 无法走索引检索(两列比较 / 字面量缺失)时返回 null,调用方退回普通全排序
     */
    public static QueryOperand queryOperand(Ast.Func func, TableMeta table) {
        if (table == null || func.args().size() != 2) {
            return null;
        }
        Column column;
        Ast.Expr literal;
        Column left = columnOf(func.args().get(0), table);
        Column right = columnOf(func.args().get(1), table);
        if (left != null && left.type() == DataType.VECTOR && isVectorLiteral(func.args().get(1))) {
            column = left;
            literal = func.args().get(1);
        } else if (right != null && right.type() == DataType.VECTOR
                && isVectorLiteral(func.args().get(0))) {
            column = right;
            literal = func.args().get(0);
        } else {
            return null;
        }
        try {
            float[] vec = VectorValues.parseLiteral((String) ((Ast.Lit) literal).value(),
                    column.vectorDim());
            return new QueryOperand(column, vec);
        } catch (StoreException e) {
            throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                    column.name() + " 的查询向量非法: " + e.getMessage());
        }
    }

    private static Column columnOf(Ast.Expr expr, TableMeta table) {
        if (expr instanceof Ast.Col col) {
            return table.column(col.name());
        }
        return null;
    }

    private static boolean isVectorLiteral(Ast.Expr expr) {
        return expr instanceof Ast.Lit lit && lit.value() instanceof String;
    }

    /** 距离函数的度量操作码;不是距离函数返回 -1。 */
    public static int metricOf(Ast.Func func) {
        return Functions.distanceMetric(func.name());
    }

    /** 结果自描述四字段的文本(EXPLAIN / CLI 注释共用,K164)。 */
    public static String describe(String path, String mode, int ef, int candidates,
                                  boolean partial, double selectivity, String allowListSource) {
        StringBuilder sb = new StringBuilder();
        sb.append("向量检索: 路径=").append(path)
                .append(", 模式=").append(mode);
        if (ef > 0) {
            sb.append(", ef=").append(ef);
        }
        sb.append(", 候选=").append(candidates)
                .append(", partial=").append(partial);
        if (selectivity >= 0) {
            sb.append(", 选择率=").append(String.format(Locale.ROOT, "%.4f", selectivity));
        }
        if (allowListSource != null) {
            sb.append(", 允许集来源=").append(allowListSource);
        }
        return sb.toString();
    }
}
