package com.wangyu.tangdb.query;

import com.wangyu.tangdb.execute.SqlError;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Locale;

/**
 * P5 表达式的运行时值语义(SPEC-P5 F 组)。
 *
 * <ul>
 *   <li>三值逻辑:任何一侧为 NULL 的比较结果都是 UNKNOWN(null)(FR-594);</li>
 *   <li>算术:{@code + - * / %} ,除零返回 NULL(K109),整数按 64 位回绕(边界 58);</li>
 *   <li>比较:数值按数值比,字符串按 UTF-8 字节序,DATE 按日期,BOOLEAN 按布尔;
 *       其它组合明确报类型错误(E-5 / FR-592);</li>
 *   <li>LIKE 不区分大小写(K106)。</li>
 * </ul>
 */
public final class ValueOps {

    private ValueOps() {
    }

    public static boolean isNumeric(Object v) {
        return v instanceof Number;
    }

    public static boolean isIntegral(Object v) {
        return v instanceof Integer || v instanceof Long;
    }

    /** 三值真值:Boolean / null。 */
    public static Boolean truth(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (v instanceof String s) {
            return !s.isEmpty() && !"0".equals(s);
        }
        throw new SqlError(SqlError.ER_WRONG_ARGUMENTS, "HY000",
                "条件表达式的结果不是布尔值: " + v);
    }

    public static int compare(Object a, Object b) {
        if (a == null || b == null) {
            throw new IllegalStateException("NULL 比较应由三值逻辑处理");
        }
        if (a instanceof Number na && b instanceof Number nb) {
            if (a instanceof Double || b instanceof Double) {
                return Double.compare(na.doubleValue(), nb.doubleValue());
            }
            return Long.compare(na.longValue(), nb.longValue());
        }
        // DATE 与字符串字面量比较:按日期解析(MySQL 口径;不改变连接列类型检查 E-5)。
        if (a instanceof LocalDate da && b instanceof String sb) {
            LocalDate other = parseDate(sb);
            if (other == null) {
                throw new SqlError(SqlError.ER_WRONG_ARGUMENTS, "HY000",
                        "字符串 '" + sb + "' 不是合法日期");
            }
            return da.compareTo(other);
        }
        if (a instanceof String sa && b instanceof LocalDate db) {
            LocalDate other = parseDate(sa);
            if (other == null) {
                throw new SqlError(SqlError.ER_WRONG_ARGUMENTS, "HY000",
                        "字符串 '" + sa + "' 不是合法日期");
            }
            return other.compareTo(db);
        }
        // 数值与字符串字面量比较:先按数值解析(MySQL 口径)。
        if (a instanceof Number na2 && b instanceof String sb) {
            Object parsed = toNumberOrNull(sb);
            if (parsed == null) {
                throw new SqlError(SqlError.ER_WRONG_ARGUMENTS, "HY000",
                        "字符串 '" + sb + "' 不是合法数值");
            }
            return compare(na2, parsed);
        }
        if (a instanceof String sa && b instanceof Number nb2) {
            Object parsed = toNumberOrNull(sa);
            if (parsed == null) {
                throw new SqlError(SqlError.ER_WRONG_ARGUMENTS, "HY000",
                        "字符串 '" + sa + "' 不是合法数值");
            }
            return compare(parsed, nb2);
        }
        if (a instanceof String sa && b instanceof String sb) {
            return Arrays.compare(sa.getBytes(StandardCharsets.UTF_8),
                    sb.getBytes(StandardCharsets.UTF_8));
        }
        if (a instanceof LocalDate da && b instanceof LocalDate db) {
            return da.compareTo(db);
        }
        if (a instanceof Boolean ba && b instanceof Boolean bb) {
            return Boolean.compare(ba, bb);
        }
        throw new SqlError(SqlError.ER_WRONG_ARGUMENTS, "HY000",
                "两个值不可比较(" + typeName(a) + " 与 " + typeName(b) + "),不做静默强转");
    }

    /** ORDER BY 比较:NULL 位置按 K105(升序在最前,降序在最后)。 */
    public static int compareForSort(Object a, Object b, boolean descending) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return descending ? 1 : -1;
        }
        if (b == null) {
            return descending ? -1 : 1;
        }
        int c = compare(a, b);
        return descending ? -c : c;
    }

    public static String typeName(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof Integer) {
            return "INT";
        }
        if (v instanceof Long) {
            return "BIGINT";
        }
        if (v instanceof Double) {
            return "DOUBLE";
        }
        if (v instanceof String) {
            return "VARCHAR";
        }
        if (v instanceof Boolean) {
            return "BOOLEAN";
        }
        if (v instanceof LocalDate) {
            return "DATE";
        }
        if (v instanceof byte[]) {
            return "JSON";
        }
        return v.getClass().getSimpleName();
    }

    // ------------------------------------------------------------ 算术

    public static Object arith(com.wangyu.tangdb.parser.Ast.ArithOp op, Object a, Object b) {
        if (a == null || b == null) {
            return null;
        }
        return switch (op) {
            case ADD -> add(a, b);
            case SUB -> subtract(a, b);
            case MUL -> multiply(a, b);
            case DIV -> divide(a, b);
            case MOD -> modulo(a, b);
        };
    }

    private static Object add(Object a, Object b) {
        if (a instanceof String || b instanceof String) {
            Object na = toNumberOrNull(a);
            Object nb = toNumberOrNull(b);
            if (na == null || nb == null) {
                return null;
            }
            return add(na, nb);
        }
        if (a instanceof Double || b instanceof Double) {
            return toDouble(a) + toDouble(b);
        }
        return toLong(a) + toLong(b); // 64 位回绕(边界 58)
    }

    private static Object subtract(Object a, Object b) {
        if (a instanceof Double || b instanceof Double) {
            return toDouble(a) - toDouble(b);
        }
        return toLong(a) - toLong(b);
    }

    private static Object multiply(Object a, Object b) {
        if (a instanceof Double || b instanceof Double) {
            return toDouble(a) * toDouble(b);
        }
        return toLong(a) * toLong(b);
    }

    private static Object divide(Object a, Object b) {
        double d = toDouble(b);
        if (d == 0.0) {
            return null; // K109
        }
        return toDouble(a) / d;
    }

    private static Object modulo(Object a, Object b) {
        if (a instanceof Double || b instanceof Double) {
            double d = toDouble(b);
            if (d == 0.0) {
                return null;
            }
            return toDouble(a) % d;
        }
        long d = toLong(b);
        if (d == 0) {
            return null;
        }
        return toLong(a) % d;
    }

    public static Object negate(Object a) {
        if (a == null) {
            return null;
        }
        if (a instanceof Double) {
            return -toDouble(a);
        }
        return -toLong(a);
    }

    public static double toDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw new SqlError(SqlError.ER_WRONG_ARGUMENTS, "HY000",
                        "字符串 '" + s + "' 不是合法数值");
            }
        }
        throw new SqlError(SqlError.ER_WRONG_ARGUMENTS, "HY000",
                "值不是数值: " + typeName(v));
    }

    public static long toLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                throw new SqlError(SqlError.ER_WRONG_ARGUMENTS, "HY000",
                        "字符串 '" + s + "' 不是合法整数");
            }
        }
        throw new SqlError(SqlError.ER_WRONG_ARGUMENTS, "HY000",
                "值不是整数: " + typeName(v));
    }

    /** 宽松数值转换:失败返回 null(用于 CAST / 字符串参与算术)。 */
    public static Object toNumberOrNull(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v;
        }
        if (v instanceof String s) {
            String t = s.trim();
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException ignored) {
                try {
                    return Double.parseDouble(t);
                } catch (NumberFormatException ignored2) {
                    return null;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------ LIKE(K106)

    /** LIKE:不区分大小写,支持 {@code %} / {@code _},默认反斜杠转义。 */
    public static boolean like(String value, String pattern, String escape) {
        char esc = 0;
        if (escape != null && !escape.isEmpty()) {
            esc = escape.charAt(0);
        }
        String v = value.toLowerCase(Locale.ROOT);
        String p = pattern.toLowerCase(Locale.ROOT);
        return likeMatch(v, 0, p, 0, esc);
    }

    private static boolean likeMatch(String v, int vi, String p, int pi, char esc) {
        while (pi < p.length()) {
            char pc = p.charAt(pi);
            if (esc != 0 && pc == esc) {
                pi++;
                if (pi >= p.length()) {
                    return false;
                }
                if (vi >= v.length() || v.charAt(vi) != p.charAt(pi)) {
                    return false;
                }
                vi++;
                pi++;
                continue;
            }
            if (pc == '%') {
                while (pi < p.length() && p.charAt(pi) == '%') {
                    pi++;
                }
                if (pi >= p.length()) {
                    return true;
                }
                for (int k = vi; k <= v.length(); k++) {
                    if (likeMatch(v, k, p, pi, esc)) {
                        return true;
                    }
                }
                return false;
            }
            if (pc == '_') {
                if (vi >= v.length()) {
                    return false;
                }
                vi++;
                pi++;
                continue;
            }
            if (vi >= v.length() || v.charAt(vi) != pc) {
                return false;
            }
            vi++;
            pi++;
        }
        return vi == v.length();
    }

    /** DISTINCT / GROUP BY 的键:保留类型,避免 1 与 "1" 混为一组。 */
    public static Object key(Object v) {
        if (v instanceof byte[] b) {
            return "json:" + java.util.HexFormat.of().formatHex(b);
        }
        return v;
    }

    static boolean parseBoolean(String s) {
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    static LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
