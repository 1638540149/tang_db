package com.wangyu.tangdb.query;

import com.wangyu.tangdb.parser.Ast;
import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.DataType;
import com.wangyu.tangdb.storage.ProjectedCodec;
import com.wangyu.tangdb.storage.TableMeta;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把单表谓词编译成 BE 可执行的字节码(SPEC-P6.1 FR-703 / K146)。
 *
 * <p>指令集与 {@code be/internal/exec/predicate.go} 一一对应;字面量按
 * {@link ProjectedCodec#encodeLiteral} 的保序编码写入。BE 只做字节比较。</p>
 *
 * <p><b>保守原则</b>:只编译"单列 op 字面量"这一窄子集;任何拿不准的形态
 * (表达式、函数、子查询、JSON 列、类型不匹配、OR 的一侧不可编译)都返回 null,
 * 由 FE 侧残余过滤执行 —— 下推只能减少扫描量,不能改变结果集。</p>
 */
public final class PredicateCodec {

    private static final byte OP_PUSH_SLOT = 0x01;
    private static final byte OP_PUSH_LIT = 0x02;
    private static final byte OP_PUSH_NULL = 0x03;
    private static final byte OP_EQ = 0x10;
    private static final byte OP_NE = 0x11;
    private static final byte OP_LT = 0x12;
    private static final byte OP_LE = 0x13;
    private static final byte OP_GT = 0x14;
    private static final byte OP_GE = 0x15;
    private static final byte OP_IS_NULL = 0x16;
    private static final byte OP_IS_NOT_NULL = 0x17;
    private static final byte OP_BETWEEN = 0x18;
    private static final byte OP_IN = 0x19;
    private static final byte OP_AND = 0x20;
    private static final byte OP_OR = 0x21;
    private static final byte OP_NOT = 0x22;
    private static final byte OP_END = 0x30;

    private final TableMeta table;
    private final String qualifier;
    /** true = AND 的两侧都必须可下推(用于 LIMIT 下推:BE 必须先过滤干净)。 */
    private boolean requireFull;

    private PredicateCodec(TableMeta table, String qualifier) {
        this.table = table;
        this.qualifier = qualifier == null ? null : qualifier.toLowerCase(Locale.ROOT);
    }

    /**
     * 编译谓词。
     *
     * @param qualifier 该来源的表名 / 别名;null 表示未限定
     * @return 字节码;不可下推时返回 null
     */
    public static byte[] compile(Ast.Expr expr, TableMeta table, String qualifier) {
        if (expr == null || table == null) {
            return null;
        }
        PredicateCodec codec = new PredicateCodec(table, qualifier);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!codec.emit(expr, out)) {
            return null;
        }
        out.write(OP_END);
        return out.toByteArray();
    }

    /**
     * 严格编译:要求整个谓词都能下推(AND 不允许只推一半)。
     *
     * <p>只有这种形态才允许把 LIMIT 下推到扫描 —— 否则先截断会漏行
     * (SPEC-P6.1 SC-406 与边界 #120)。</p>
     */
    public static byte[] compileFully(Ast.Expr expr, TableMeta table, String qualifier) {
        if (expr == null || table == null) {
            return null;
        }
        PredicateCodec codec = new PredicateCodec(table, qualifier);
        codec.requireFull = true;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!codec.emit(expr, out)) {
            return null;
        }
        out.write(OP_END);
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- 表达式

    private boolean emit(Ast.Expr expr, ByteArrayOutputStream out) {
        if (expr instanceof Ast.LogicalAnd and) {
            // AND 允许只下推一半:剩下那半由 FE 残余过滤兜底,结果不变。
            ByteArrayOutputStream left = new ByteArrayOutputStream();
            ByteArrayOutputStream right = new ByteArrayOutputStream();
            boolean leftOk = emit(and.left(), left);
            boolean rightOk = emit(and.right(), right);
            if (leftOk && rightOk) {
                out.writeBytes(left.toByteArray());
                out.writeBytes(right.toByteArray());
                out.write(OP_AND);
                return true;
            }
            if (requireFull) {
                return false;
            }
            if (leftOk) {
                out.writeBytes(left.toByteArray());
                return true;
            }
            if (rightOk) {
                out.writeBytes(right.toByteArray());
                return true;
            }
            return false;
        }
        if (expr instanceof Ast.LogicalOr or) {
            // OR 必须两侧都能下推,否则 BE 会漏掉另一侧满足的行。
            ByteArrayOutputStream l = new ByteArrayOutputStream();
            ByteArrayOutputStream r = new ByteArrayOutputStream();
            if (!emit(or.left(), l) || !emit(or.right(), r)) {
                return false;
            }
            out.writeBytes(l.toByteArray());
            out.writeBytes(r.toByteArray());
            out.write(OP_OR);
            return true;
        }
        if (expr instanceof Ast.Not not) {
            ByteArrayOutputStream inner = new ByteArrayOutputStream();
            if (!emit(not.operand(), inner)) {
                return false;
            }
            out.writeBytes(inner.toByteArray());
            out.write(OP_NOT);
            return true;
        }
        if (expr instanceof Ast.IsNull isNull) {
            Integer ordinal = columnOf(isNull.operand());
            if (ordinal == null) {
                return false;
            }
            pushSlot(out, ordinal);
            out.write(isNull.negated() ? OP_IS_NOT_NULL : OP_IS_NULL);
            return true;
        }
        if (expr instanceof Ast.Cmp cmp) {
            return emitCmp(cmp, out);
        }
        if (expr instanceof Ast.Between between) {
            Integer ordinal = columnOf(between.operand());
            if (ordinal == null) {
                return false;
            }
            byte[] lo = literalFor(ordinal, between.lower());
            byte[] hi = literalFor(ordinal, between.upper());
            if (lo == null || hi == null) {
                return false;
            }
            pushSlot(out, ordinal);
            pushLit(out, lo);
            pushLit(out, hi);
            out.write(OP_BETWEEN);
            if (between.negated()) {
                out.write(OP_NOT);
            }
            return true;
        }
        if (expr instanceof Ast.InExpr in) {
            Integer ordinal = columnOf(in.operand());
            if (ordinal == null) {
                return false;
            }
            List<byte[]> values = new ArrayList<>();
            for (Ast.Expr value : in.values()) {
                byte[] lit = literalFor(ordinal, value);
                if (lit == null) {
                    return false; // 含 NULL / 非字面量 / 类型不匹配 → 不下推
                }
                values.add(lit);
            }
            if (values.isEmpty()) {
                return false;
            }
            pushSlot(out, ordinal);
            out.write(OP_IN);
            writeLe2(out, values.size());
            for (byte[] value : values) {
                writeLe4(out, value.length);
                out.writeBytes(value);
            }
            if (in.negated()) {
                out.write(OP_NOT);
            }
            return true;
        }
        return false;
    }

    private boolean emitCmp(Ast.Cmp cmp, ByteArrayOutputStream out) {
        Integer leftOrdinal = columnOf(cmp.left());
        Integer rightOrdinal = columnOf(cmp.right());
        byte[] leftLit = leftOrdinal == null ? null : literalFor(leftOrdinal, cmp.right());
        byte[] rightLit = rightOrdinal == null ? null : literalFor(rightOrdinal, cmp.left());

        if (leftOrdinal != null && leftLit != null) {
            pushSlot(out, leftOrdinal);
            pushLit(out, leftLit);
            out.write(opcode(cmp.op()));
            return true;
        }
        if (rightOrdinal != null && rightLit != null) {
            // 字面量在左:交换操作数并翻转比较方向。
            pushSlot(out, rightOrdinal);
            pushLit(out, rightLit);
            out.write(opcode(flip(cmp.op())));
            return true;
        }
        return false;
    }

    private static Ast.Op flip(Ast.Op op) {
        return switch (op) {
            case EQ -> Ast.Op.EQ;
            case NE -> Ast.Op.NE;
            case LT -> Ast.Op.GT;
            case LE -> Ast.Op.GE;
            case GT -> Ast.Op.LT;
            case GE -> Ast.Op.LE;
        };
    }

    private static byte opcode(Ast.Op op) {
        return switch (op) {
            case EQ -> OP_EQ;
            case NE -> OP_NE;
            case LT -> OP_LT;
            case LE -> OP_LE;
            case GT -> OP_GT;
            case GE -> OP_GE;
        };
    }

    /** 列属于本表时返回列序号。 */
    private Integer columnOf(Ast.Expr expr) {
        if (!(expr instanceof Ast.Col col)) {
            return null;
        }
        if (!belongsToSource(col)) {
            return null;
        }
        int index = table.columnIndex(col.name());
        if (index < 0) {
            return null;
        }
        Column column = table.columns().get(index);
        if (column.type() == DataType.JSON) {
            return null; // FR-704:JSON 列不下推
        }
        return index;
    }

    private boolean belongsToSource(Ast.Col col) {
        if (col.qualifier() == null) {
            return true;
        }
        String q = col.qualifier().toLowerCase(Locale.ROOT);
        return qualifier != null && qualifier.equals(q);
    }

    /**
     * 字面量按**列的类型**编码成比较态字节(与 BE 的列变换同一套字节序)。
     * 值的形态与列类型对不上(如 INT 列比 1.5)时不下推 —— 字节比较无法表达。
     */
    private byte[] literalFor(int ordinal, Ast.Expr expr) {
        if (!(expr instanceof Ast.Lit lit) || lit.value() == null) {
            return null;
        }
        Column column = table.columns().get(ordinal);
        Object value = coerce(column.type(), lit.value());
        if (value == null) {
            return null;
        }
        return ProjectedCodec.encodeLiteral(column.type(), value);
    }

    private static Object coerce(DataType type, Object value) {
        switch (type) {
            case INT: {
                if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
                    return value;
                }
                if (value instanceof Long l && l == l.intValue()) {
                    return l.intValue();
                }
                return null;
            }
            case BIGINT: {
                if (value instanceof Integer || value instanceof Long
                        || value instanceof Short || value instanceof Byte) {
                    return ((Number) value).longValue();
                }
                return null;
            }
            case DOUBLE: {
                return value instanceof Number n ? (Object) n.doubleValue() : null;
            }
            case DATE: {
                return value instanceof java.time.LocalDate ? value : null;
            }
            case VARCHAR: {
                return value instanceof String ? value : null;
            }
            case BOOLEAN: {
                return value instanceof Boolean ? value : null;
            }
            default:
                return null; // JSON 不支持下推
        }
    }

    // ---------------------------------------------------------------- 字节写入

    private static void pushSlot(ByteArrayOutputStream out, int ordinal) {
        out.write(OP_PUSH_SLOT);
        writeLe2(out, ordinal);
    }

    private static void pushLit(ByteArrayOutputStream out, byte[] value) {
        out.write(OP_PUSH_LIT);
        writeLe4(out, value.length);
        out.writeBytes(value);
    }

    private static void writeLe2(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
    }

    private static void writeLe4(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 24) & 0xff);
    }

    /** 供单测检查:本类只下推这些形态。 */
    static Set<Class<?>> compilableForms() {
        return Set.of(Ast.LogicalAnd.class, Ast.LogicalOr.class, Ast.Not.class, Ast.IsNull.class,
                Ast.Cmp.class, Ast.Between.class, Ast.InExpr.class);
    }
}
