package com.wangyu.tangdb.query;

import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.storage.DataType;
import com.wangyu.tangdb.storage.Values;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 标量函数(SPEC-P5 FR-597 ~ FR-598 / FR-613)与聚合函数名表(FR-581 / FR-612)。
 *
 * <p>每个函数都处理 NULL 入参:绝大多数返回 NULL(由调用方在求值前统一短路),
 * {@code COALESCE} / {@code IFNULL} 是例外。每个函数在单测里至少一条正例 + NULL 例。</p>
 */
public final class Functions {

    public static final Set<String> AGGREGATES = Set.of(
            "COUNT", "SUM", "AVG", "MIN", "MAX", "STDDEV", "VARIANCE", "GROUP_CONCAT");

    /** P12 才做的函数:明确报 1235(SC-369)。 */
    private static final Set<String> LATER_PHASE = Set.of(
            "NOW", "CURDATE", "CURTIME", "DATE_ADD", "DATE_SUB", "DATEDIFF", "DATE_DIFF",
            "TIMESTAMP", "UNIX_TIMESTAMP", "FROM_UNIXTIME");

    private Functions() {
    }

    public static boolean isAggregate(String name) {
        return AGGREGATES.contains(name.toUpperCase(Locale.ROOT));
    }

    /** 语法可识别、但归后续期次的函数(SC-369:明确报 1235,不是 1064)。 */
    public static boolean isDeferred(String name) {
        return LATER_PHASE.contains(name.toUpperCase(Locale.ROOT));
    }

    public static boolean exists(String name) {
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "ABS", "ROUND", "CEIL", "CEILING", "FLOOR", "MOD", "POWER", "SQRT",
                    "LENGTH", "CHAR_LENGTH", "UPPER", "LOWER", "SUBSTR", "SUBSTRING", "CONCAT",
                    "TRIM", "LTRIM", "RTRIM", "REPLACE", "COALESCE", "IFNULL", "NULLIF",
                    "CAST", "YEAR", "MONTH", "DAY", "DAYOFMONTH", "JSON_EXTRACT" -> true;
            default -> AGGREGATES.contains(name.toUpperCase(Locale.ROOT));
        };
    }

    /** 标量函数求值;参数已求值(可能含 null)。 */
    public static Object call(String rawName, Object[] args) {
        String name = rawName.toUpperCase(Locale.ROOT);
        if (LATER_PHASE.contains(name)) {
            throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                    name + "() 归 P12(本期只做 YEAR/MONTH/DAY,FR-613)");
        }
        return switch (name) {
            case "COALESCE" -> {
                for (Object arg : args) {
                    if (arg != null) {
                        yield arg;
                    }
                }
                yield null;
            }
            case "IFNULL" -> {
                requireArgs(name, args, 2);
                yield args[0] != null ? args[0] : args[1];
            }
            case "NULLIF" -> {
                requireArgs(name, args, 2);
                if (args[0] == null || args[1] == null) {
                    yield args[0];
                }
                yield ValueOps.compare(args[0], args[1]) == 0 ? null : args[0];
            }
            case "CONCAT" -> concat(args);
            default -> {
                if (anyNull(args)) {
                    yield null;
                }
                yield switch (name) {
                    case "ABS" -> abs(args);
                    case "ROUND" -> round(args);
                    case "CEIL", "CEILING" -> (long) Math.ceil(number(name, args, 1));
                    case "FLOOR" -> (long) Math.floor(number(name, args, 1));
                    case "MOD" -> {
                        requireArgs(name, args, 2);
                        yield ValueOps.arith(com.wangyu.tangdb.parser.Ast.ArithOp.MOD,
                                args[0], args[1]);
                    }
                    case "POWER" -> {
                        requireArgs(name, args, 2);
                        yield Math.pow(number(name, args, 2), number(name, args, 2));
                    }
                    case "SQRT" -> Math.sqrt(number(name, args, 1));
                    case "LENGTH" -> (long) string(name, args[0]).getBytes(StandardCharsets.UTF_8).length;
                    case "CHAR_LENGTH" -> (long) string(name, args[0]).length();
                    case "UPPER" -> string(name, args[0]).toUpperCase(Locale.ROOT);
                    case "LOWER" -> string(name, args[0]).toLowerCase(Locale.ROOT);
                    case "SUBSTR", "SUBSTRING" -> substring(name, args);
                    case "TRIM" -> string(name, args[0]).trim();
                    case "LTRIM" -> string(name, args[0]).stripLeading();
                    case "RTRIM" -> string(name, args[0]).stripTrailing();
                    case "REPLACE" -> {
                        requireArgs(name, args, 3);
                        yield string(name, args[0]).replace(
                                string(name, args[1]), string(name, args[2]));
                    }
                    case "YEAR" -> datePart(name, args[0], 1);
                    case "MONTH" -> datePart(name, args[0], 2);
                    case "DAY", "DAYOFMONTH" -> datePart(name, args[0], 3);
                    case "JSON_EXTRACT" -> {
                        requireArgs(name, args, 2);
                        Object doc = args[0] instanceof byte[] raw ? JsonCodec.decode(raw) : args[0];
                        yield JsonCodec.extract(doc, string(name, args[1]));
                    }
                    default -> throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                            "不支持的函数: " + rawName);
                };
            }
        };
    }

    private static Object abs(Object[] args) {
        requireArgs("ABS", args, 1);
        Object v = args[0];
        if (v instanceof Double || v instanceof Float) {
            return Math.abs(ValueOps.toDouble(v));
        }
        long n = ValueOps.toLong(v);
        return n == Long.MIN_VALUE ? n : Math.abs(n);
    }

    private static Object round(Object[] args) {
        requireArgs("ROUND", args, 1, 2);
        Object v = args[0];
        int digits = args.length > 1 ? (int) ValueOps.toLong(args[1]) : 0;
        if (v instanceof Integer || v instanceof Long) {
            return digits >= 0 ? ValueOps.toLong(v) : 0L;
        }
        double d = ValueOps.toDouble(v);
        double factor = Math.pow(10, digits);
        return Math.round(d * factor) / factor;
    }

    private static Object substring(String name, Object[] args) {
        requireArgs(name, args, 2, 3);
        String s = string(name, args[0]);
        long pos = ValueOps.toLong(args[1]);
        int len = args.length > 2 ? (int) ValueOps.toLong(args[2]) : Integer.MAX_VALUE;
        if (len <= 0) {
            return "";
        }
        int start;
        if (pos > 0) {
            start = (int) pos - 1;
        } else if (pos < 0) {
            start = s.length() + (int) pos;
        } else {
            return "";
        }
        if (start < 0 || start >= s.length()) {
            return "";
        }
        int end = (int) Math.min((long) s.length(), (long) start + len);
        return s.substring(start, end);
    }

    private static Object datePart(String name, Object value, int part) {
        LocalDate date;
        if (value instanceof LocalDate d) {
            date = d;
        } else if (value instanceof String s) {
            date = ValueOps.parseDate(s);
            if (date == null) {
                throw new SqlError(SqlError.ER_WRONG_ARGUMENTS, "HY000",
                        name + " 的入参不是合法日期: " + s);
            }
        } else {
            throw new SqlError(SqlError.ER_WRONG_ARGUMENTS, "HY000",
                    name + " 只接受 DATE 入参,实际是 " + ValueOps.typeName(value));
        }
        return switch (part) {
            case 1 -> (long) date.getYear();
            case 2 -> (long) date.getMonthValue();
            default -> (long) date.getDayOfMonth();
        };
    }

    private static Object concat(Object[] args) {
        StringBuilder sb = new StringBuilder();
        for (Object arg : args) {
            if (arg == null) {
                return null; // MySQL 口径:任一 NULL → NULL
            }
            sb.append(Values.display(arg));
        }
        return sb.toString();
    }

    private static String string(String fn, Object v) {
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof byte[] b) {
            return JsonCodec.toText(JsonCodec.decode(b));
        }
        if (v instanceof java.time.LocalDate d) {
            return d.toString();
        }
        return Values.display(v);
    }

    private static double number(String fn, Object[] args, int expected) {
        requireArgs(fn, args, expected);
        return ValueOps.toDouble(args[0]);
    }

    private static void requireArgs(String fn, Object[] args, int... allowed) {
        for (int n : allowed) {
            if (args.length == n) {
                return;
            }
        }
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < allowed.length; i++) {
            if (i > 0) {
                expected.append(" 或 ");
            }
            expected.append(allowed[i]);
        }
        throw new SqlError(SqlError.ER_WRONG_ARGUMENTS, "HY000",
                "函数 " + fn + " 需要 " + expected + " 个参数,实际 " + args.length);
    }

    private static boolean anyNull(Object[] args) {
        for (Object arg : args) {
            if (arg == null) {
                return true;
            }
        }
        return false;
    }

    /** CAST(expr AS type);非法转换返回 NULL(边界 59),不报错。 */
    public static Object cast(Object value, DataType type) {
        if (value == null) {
            return null;
        }
        try {
            return switch (type) {
                case INT -> {
                    Object n = ValueOps.toNumberOrNull(value);
                    if (n == null) {
                        yield null;
                    }
                    long v = n instanceof Double d ? Math.round(d) : ((Number) n).longValue();
                    if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                        yield (int) v; // 回绕,与 MySQL 的宽松口径一致
                    }
                    yield (int) v;
                }
                case BIGINT -> {
                    Object n = ValueOps.toNumberOrNull(value);
                    if (n == null) {
                        yield null;
                    }
                    yield n instanceof Double d ? (long) Math.round(d) : ((Number) n).longValue();
                }
                case DOUBLE -> {
                    Object n = ValueOps.toNumberOrNull(value);
                    yield n == null ? null : ((Number) n).doubleValue();
                }
                case VARCHAR -> string("CAST", value);
                case DATE -> value instanceof LocalDate d ? d
                        : (value instanceof String s ? ValueOps.parseDate(s) : null);
                case BOOLEAN -> value instanceof Boolean b ? b
                        : (value instanceof Number n ? n.doubleValue() != 0 : null);
                case JSON -> value instanceof byte[] b ? b : JsonCodec.encode(value);
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 函数名表(给 EXPLAIN / 测试用)。 */
    public static List<String> supportedNames() {
        return List.of("ABS", "ROUND", "CEIL", "CEILING", "FLOOR", "MOD", "POWER", "SQRT",
                "LENGTH", "CHAR_LENGTH", "UPPER", "LOWER", "SUBSTR", "SUBSTRING", "CONCAT",
                "TRIM", "LTRIM", "RTRIM", "REPLACE", "COALESCE", "IFNULL", "NULLIF",
                "CAST", "YEAR", "MONTH", "DAY", "DAYOFMONTH", "JSON_EXTRACT");
    }
}
