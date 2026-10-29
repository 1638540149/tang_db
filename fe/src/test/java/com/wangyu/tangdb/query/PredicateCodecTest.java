package com.wangyu.tangdb.query;

import com.wangyu.tangdb.parser.Ast;
import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.DataType;
import com.wangyu.tangdb.storage.ProjectedCodec;
import com.wangyu.tangdb.storage.TableMeta;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 谓词字节码编译 + 等价性(SPEC-P6.1 FR-703 / FR-727 / SC-412)。
 *
 * <p>FE 侧求值用真实的 {@link Evaluator};BE 侧求值用测试里的字节码解释器
 * (与 {@code be/internal/exec/predicate.go} 同一套指令),两边逐行对拍。</p>
 */
class PredicateCodecTest {

    private static TableMeta table() {
        return new TableMeta(16, "t", TableMeta.STATE_ACTIVE, "", List.of(
                new Column("id", DataType.INT, 0, false, true, 0),
                new Column("name", DataType.VARCHAR, 100, true, false, 1),
                new Column("score", DataType.DOUBLE, 0, true, false, 2),
                new Column("birthday", DataType.DATE, 0, true, false, 3),
                new Column("doc", DataType.JSON, 0, true, false, 4)
        ), false);
    }

    private static Schema schema(TableMeta table) {
        return Schema.ofTable("t", table.columns());
    }

    private static Ast.Expr col(String name) {
        return new Ast.Col(null, name);
    }

    private static Ast.Expr lit(Object value) {
        return new Ast.Lit(value, String.valueOf(value));
    }

    private static Ast.Expr eq(String name, Object value) {
        return new Ast.Cmp(Ast.Op.EQ, col(name), lit(value));
    }

    private static Ast.Expr gt(String name, Object value) {
        return new Ast.Cmp(Ast.Op.GT, col(name), lit(value));
    }

    private static Ast.Expr lt(String name, Object value) {
        return new Ast.Cmp(Ast.Op.LT, col(name), lit(value));
    }

    private static Ast.Expr and(Ast.Expr a, Ast.Expr b) {
        return new Ast.LogicalAnd(a, b);
    }

    private static Ast.Expr or(Ast.Expr a, Ast.Expr b) {
        return new Ast.LogicalOr(a, b);
    }

    private static Ast.Expr not(Ast.Expr a) {
        return new Ast.Not(a);
    }

    // ------------------------------------------------------------ 编译形态

    @Test
    void compilesOnlySupportedForms() {
        TableMeta t = table();
        assertNotNull(PredicateCodec.compile(eq("id", 1), t, "t"));
        assertNotNull(PredicateCodec.compile(new Ast.IsNull(col("name"), false), t, "t"));
        assertNotNull(PredicateCodec.compile(new Ast.Between(col("id"), lit(1), lit(5), false), t, "t"));
        assertNotNull(PredicateCodec.compile(new Ast.InExpr(col("id"),
                List.of(lit(1), lit(2)), false), t, "t"));
        assertNotNull(PredicateCodec.compile(and(gt("id", 1), lt("id", 9)), t, "t"));

        // 表达式下推不做(只做单列比较,I45)
        assertNull(PredicateCodec.compile(new Ast.Cmp(Ast.Op.GT,
                new Ast.Arith(Ast.ArithOp.ADD, col("id"), lit(1)), lit(5)), t, "t"));
        // JSON 列不下推(FR-704)
        assertNull(PredicateCodec.compile(eq("doc", "x"), t, "t"));
        // OR 一侧不可编译 → 整体不下推
        assertNull(PredicateCodec.compile(or(eq("id", 1),
                new Ast.Like(col("name"), lit("a%"), "\\")), t, "t"));
        // 类型不匹配(INT 列比 1.5)→ 不下推
        assertNull(PredicateCodec.compile(eq("id", 1.5), t, "t"));
        // 别的表的列
        assertNull(PredicateCodec.compile(new Ast.Cmp(Ast.Op.EQ,
                new Ast.Col("u", "id"), lit(1)), t, "t"));
    }

    @Test
    void andAllowsPartialPushdown() {
        TableMeta t = table();
        byte[] code = PredicateCodec.compile(and(eq("id", 7),
                new Ast.Like(col("name"), lit("a%"), "\\")), t, "t");
        assertNotNull(code, "AND 的一半可下推时应只下推那一半");
        assertTrue(BytecodeTestSupport.eval(code, Arrays.asList(7, "zzz", 1.0, null, null)),
                "id=7 应通过下推的 id=7 部分");
        assertFalse(BytecodeTestSupport.eval(code, Arrays.asList(8, "zzz", 1.0, null, null)));
    }

    // ------------------------------------------------------------ 等价性矩阵

    @Test
    void predicateEquivalenceMatrix() {
        TableMeta t = table();
        Schema s = schema(t);
        Evaluator evaluator = new Evaluator();

        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{1, "abc", 1.5, LocalDate.of(2020, 1, 1), null});
        rows.add(new Object[]{2, "abd", -2.5, LocalDate.of(2021, 6, 30), null});
        rows.add(new Object[]{3, "", 0.0, null, null});
        rows.add(new Object[]{4, null, null, null, null});
        rows.add(new Object[]{5, "超长" + "x".repeat(300), 1e10, LocalDate.of(1970, 1, 2), null});
        rows.add(new Object[]{6, "ABC", 1.5, LocalDate.of(2020, 1, 1), null});
        rows.add(new Object[]{Integer.MAX_VALUE, "zzz", Double.MAX_VALUE, null, null});
        rows.add(new Object[]{Integer.MIN_VALUE, "aaa", -Double.MAX_VALUE, null, null});

        List<Ast.Expr> predicates = List.of(
                eq("id", 1),
                gt("id", 2),
                lt("score", 0.0),
                new Ast.Cmp(Ast.Op.GE, col("score"), lit(1.5)),
                new Ast.Cmp(Ast.Op.LE, col("id"), lit(Integer.MIN_VALUE)),
                eq("name", ""),
                eq("name", "abc"),
                new Ast.Cmp(Ast.Op.GT, col("name"), lit("abd")),
                new Ast.IsNull(col("name"), false),
                new Ast.IsNull(col("name"), true),
                new Ast.IsNull(col("birthday"), false),
                new Ast.Between(col("id"), lit(2), lit(4), false),
                new Ast.Between(col("id"), lit(2), lit(4), true),
                new Ast.InExpr(col("id"), List.of(lit(1), lit(3), lit(5)), false),
                new Ast.InExpr(col("id"), List.of(lit(1), lit(3)), true),
                and(gt("id", 1), lt("id", 5)),
                or(eq("id", 1), eq("name", "abd")),
                not(gt("id", 3)),
                and(eq("name", "abc"), new Ast.IsNull(col("birthday"), false)),
                or(new Ast.IsNull(col("score"), false), gt("score", 1e9)),
                // 字面量在左
                new Ast.Cmp(Ast.Op.LT, lit(3), col("id")),
                // 边界
                eq("id", Integer.MAX_VALUE),
                eq("id", Integer.MIN_VALUE),
                gt("score", Double.MAX_VALUE),
                lt("score", -Double.MAX_VALUE),
                eq("birthday", LocalDate.of(2020, 1, 1)),
                new Ast.IsNull(col("birthday"), true),
                and(new Ast.IsNull(col("name"), true), eq("id", 4)),
                // 三值逻辑:NULL 参与比较一律不命中
                eq("score", 1.5),
                not(new Ast.Cmp(Ast.Op.EQ, col("score"), lit(1.5)))
        );

        for (Ast.Expr predicate : predicates) {
            byte[] code = PredicateCodec.compile(predicate, t, "t");
            assertNotNull(code, "应可编译: " + predicate);
            for (Object[] row : rows) {
                Object expected = evaluator.eval(predicate, row, s);
                boolean fePass = Boolean.TRUE.equals(expected);
                boolean bePass = BytecodeTestSupport.eval(code, Arrays.asList(row));
                assertEquals(fePass, bePass,
                        "谓词 " + predicate + " 在行 " + Arrays.toString(row) + " 上不一致");
            }
        }
    }

    /**
     * 字节码解释器(测试用):与 BE 的 exec/predicate.go 同一套指令与三值逻辑,
     * 作用是把"FE 编译出来的字节"真正执行一遍。
     */
    static final class BytecodeTestSupport {

        private static final class V {
            final boolean isNull;
            final boolean isBool;
            final boolean boolVal;
            final byte[] bytes;

            V(boolean isNull, boolean isBool, boolean boolVal, byte[] bytes) {
                this.isNull = isNull;
                this.isBool = isBool;
                this.boolVal = boolVal;
                this.bytes = bytes;
            }

            static V ofBytes(byte[] b) {
                return new V(false, false, false, b);
            }

            static V ofBool(boolean b) {
                return new V(false, true, b, null);
            }

            static V nul() {
                return new V(true, false, false, null);
            }

            boolean truth() {
                return !isNull && isBool && boolVal;
            }
        }

        static boolean eval(byte[] code, List<Object> row) {
            List<V> stack = new ArrayList<>();
            int pos = 0;
            while (pos < code.length) {
                int op = code[pos++] & 0xff;
                switch (op) {
                    case 0x01 -> {
                        int ordinal = (code[pos] & 0xff) | ((code[pos + 1] & 0xff) << 8);
                        pos += 2;
                        Object value = ordinal < row.size() ? row.get(ordinal) : null;
                        stack.add(value == null ? V.nul() : V.ofBytes(valueOf(ordinal, value)));
                    }
                    case 0x02 -> {
                        int len = ProjectedCodec.readLe4(code, pos);
                        pos += 4;
                        stack.add(V.ofBytes(Arrays.copyOfRange(code, pos, pos + len)));
                        pos += len;
                    }
                    case 0x03 -> stack.add(V.nul());
                    case 0x16, 0x17 -> {
                        V v = pop(stack);
                        boolean truth = op == 0x16 ? v.isNull : !v.isNull;
                        stack.add(V.ofBool(truth));
                    }
                    case 0x22 -> {
                        V v = pop(stack);
                        if (v.isNull || !v.isBool) {
                            stack.add(V.nul());
                        } else {
                            stack.add(V.ofBool(!v.boolVal));
                        }
                    }
                    case 0x20, 0x21 -> {
                        V rhs = pop(stack);
                        V lhs = pop(stack);
                        stack.add(andOr(lhs, rhs, op == 0x20));
                    }
                    case 0x10, 0x11, 0x12, 0x13, 0x14, 0x15 -> {
                        V rhs = pop(stack);
                        V lhs = pop(stack);
                        stack.add(compare(lhs, rhs, op));
                    }
                    case 0x18 -> {
                        V hi = pop(stack);
                        V lo = pop(stack);
                        V v = pop(stack);
                        stack.add(andOr(compare(v, lo, 0x15), compare(v, hi, 0x13), true));
                    }
                    case 0x19 -> {
                        int n = (code[pos] & 0xff) | ((code[pos + 1] & 0xff) << 8);
                        pos += 2;
                        V v = pop(stack);
                        V acc = V.ofBool(false);
                        for (int i = 0; i < n; i++) {
                            int len = ProjectedCodec.readLe4(code, pos);
                            pos += 4;
                            byte[] candidate = Arrays.copyOfRange(code, pos, pos + len);
                            pos += len;
                            acc = andOr(acc, compare(v, V.ofBytes(candidate), 0x10), false);
                        }
                        stack.add(acc);
                    }
                    case 0x30 -> {
                        assertEquals(1, stack.size(), "END 时栈应只剩一个值");
                        return stack.get(0).truth();
                    }
                    default -> fail("未知操作码: 0x" + Integer.toHexString(op));
                }
            }
            fail("缺少 END");
            return false;
        }

        private static byte[] valueOf(int ordinal, Object value) {
            // 行里的第 0..3 列是 INT / VARCHAR / DOUBLE / DATE(见 table())
            if (ordinal == 0) {
                int v = ((Number) value).intValue();
                return new byte[]{(byte) ((v >>> 24) ^ 0x80), (byte) (v >>> 16), (byte) (v >>> 8),
                        (byte) v};
            }
            if (ordinal == 2) {
                long bits = Double.doubleToLongBits(((Number) value).doubleValue());
                bits = bits < 0 ? ~bits : bits ^ Long.MIN_VALUE;
                byte[] out = new byte[8];
                for (int i = 0; i < 8; i++) {
                    out[i] = (byte) (bits >>> (56 - i * 8));
                }
                return out;
            }
            if (ordinal == 3) {
                long day = ((LocalDate) value).toEpochDay();
                int v = (int) day;
                return new byte[]{(byte) ((v >>> 24) ^ 0x80), (byte) (v >>> 16), (byte) (v >>> 8),
                        (byte) v};
            }
            byte[] raw = String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (int i = 0; i < raw.length; i++) {
                raw[i] ^= (byte) 0x80;
            }
            return raw;
        }

        private static V compare(V lhs, V rhs, int op) {
            if (lhs.isNull || rhs.isNull || lhs.isBool || rhs.isBool) {
                return V.nul();
            }
            int cmp = Arrays.compareUnsigned(lhs.bytes, rhs.bytes);
            boolean res = switch (op) {
                case 0x10 -> cmp == 0;
                case 0x11 -> cmp != 0;
                case 0x12 -> cmp < 0;
                case 0x13 -> cmp <= 0;
                case 0x14 -> cmp > 0;
                case 0x15 -> cmp >= 0;
                default -> throw new IllegalStateException();
            };
            return V.ofBool(res);
        }

        private static V andOr(V lhs, V rhs, boolean isAnd) {
            boolean lNull = lhs.isNull || !lhs.isBool;
            boolean rNull = rhs.isNull || !rhs.isBool;
            if (isAnd) {
                if ((!lNull && !lhs.boolVal) || (!rNull && !rhs.boolVal)) {
                    return V.ofBool(false);
                }
                if (lNull || rNull) {
                    return V.nul();
                }
                return V.ofBool(true);
            }
            if ((!lNull && lhs.boolVal) || (!rNull && rhs.boolVal)) {
                return V.ofBool(true);
            }
            if (lNull || rNull) {
                return V.nul();
            }
            return V.ofBool(false);
        }

        private static V pop(List<V> stack) {
            assertFalse(stack.isEmpty(), "栈空");
            return stack.remove(stack.size() - 1);
        }
    }
}
