package com.wangyu.tangdb.storage;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;

/**
 * 值的类型校验、隐式转换、比较与展示。
 *
 * <p>类型口径见 P2 规约 §4.5 / §8:
 * <ul>
 *   <li>数值类型之间按 INT → BIGINT → DOUBLE 隐式提升;</li>
 *   <li>字符串按 UTF-8 字节序比较;</li>
 *   <li>DATE 解析成自 1970-01-01 的天数后比较;</li>
 *   <li>NULL 参与比较结果为 UNKNOWN(三值逻辑,由调用方处理)。</li>
 * </ul></p>
 */
public final class Values {

    private Values() {
    }

    /** 主键/列值的强制类型校验。返回规范化后的值;literal 为 null 时按列可空性校验。 */
    public static Object coerce(Object literal, Column column) {
        if (literal == null) {
            if (column.primaryKey()) {
                throw new StoreException("主键列 " + column.name() + " 不能为 NULL");
            }
            if (!column.nullable()) {
                throw new StoreException("列 " + column.name() + " 不允许为 NULL");
            }
            return null;
        }

        switch (column.type()) {
            case INT:
                return coerceInt(literal, column);
            case BIGINT:
                return coerceBigint(literal, column);
            case DOUBLE:
                return coerceDouble(literal, column);
            case VARCHAR:
                if (!(literal instanceof String s)) {
                    throw typeMismatch(column);
                }
                int bytes = s.getBytes(StandardCharsets.UTF_8).length;
                if (bytes > column.length()) {
                    throw new StoreException("列 " + column.name() + " 超长(" + column.typeName()
                            + ",实际 " + bytes + " 字节)");
                }
                return s;
            case DATE:
                if (!(literal instanceof String s)) {
                    throw typeMismatch(column);
                }
                try {
                    return LocalDate.parse(s);
                } catch (DateTimeParseException e) {
                    throw new StoreException("列 " + column.name() + " 的日期格式非法(应为 YYYY-MM-DD): " + s);
                }
            case BOOLEAN:
                if (literal instanceof Boolean b) {
                    return b;
                }
                if (literal instanceof String s) {
                    if ("true".equalsIgnoreCase(s)) {
                        return Boolean.TRUE;
                    }
                    if ("false".equalsIgnoreCase(s)) {
                        return Boolean.FALSE;
                    }
                }
                throw typeMismatch(column);
            default:
                throw typeMismatch(column);
        }
    }

    private static Object coerceInt(Object literal, Column column) {
        try {
            long v;
            if (literal instanceof Integer i) {
                v = i;
            } else if (literal instanceof Long l) {
                v = l;
            } else if (literal instanceof String s) {
                v = Long.parseLong(s.trim());
            } else {
                throw typeMismatch(column);
            }
            if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                throw new StoreException("列 " + column.name() + " 超出 INT 范围: " + v);
            }
            return (int) v;
        } catch (NumberFormatException e) {
            throw typeMismatch(column);
        }
    }

    private static Object coerceBigint(Object literal, Column column) {
        try {
            if (literal instanceof Integer i) {
                return i.longValue();
            }
            if (literal instanceof Long l) {
                return l;
            }
            if (literal instanceof String s) {
                return Long.parseLong(s.trim());
            }
        } catch (NumberFormatException e) {
            throw typeMismatch(column);
        }
        throw typeMismatch(column);
    }

    private static Object coerceDouble(Object literal, Column column) {
        try {
            if (literal instanceof Integer i) {
                return i.doubleValue();
            }
            if (literal instanceof Long l) {
                return l.doubleValue();
            }
            if (literal instanceof Double d) {
                return d;
            }
            if (literal instanceof String s) {
                return Double.parseDouble(s.trim());
            }
        } catch (NumberFormatException e) {
            throw typeMismatch(column);
        }
        throw typeMismatch(column);
    }

    public static StoreException typeMismatch(Column column) {
        return new StoreException("列 " + column.name() + " 类型不匹配(期望 " + column.typeName() + ")");
    }

    /** 在绑定阶段提前校验"列 与 常量"能否比较,避免扫描到一半才报错。 */
    public static void checkComparable(Column column, Object literal) {
        if (literal == null) {
            return;
        }
        switch (column.type()) {
            case INT:
            case BIGINT:
                if (literal instanceof Integer || literal instanceof Long || literal instanceof Double) {
                    return;
                }
                if (literal instanceof String s) {
                    try {
                        Long.parseLong(s.trim());
                        return;
                    } catch (NumberFormatException ignored) {
                        // fallthrough
                    }
                }
                throw typeMismatch(column);
            case DOUBLE:
                if (literal instanceof Number) {
                    return;
                }
                if (literal instanceof String s) {
                    try {
                        Double.parseDouble(s.trim());
                        return;
                    } catch (NumberFormatException ignored) {
                        // fallthrough
                    }
                }
                throw typeMismatch(column);
            case VARCHAR:
                if (literal instanceof String) {
                    return;
                }
                throw typeMismatch(column);
            case DATE:
                if (literal instanceof String s) {
                    try {
                        LocalDate.parse(s);
                        return;
                    } catch (DateTimeParseException e) {
                        throw new StoreException("列 " + column.name()
                                + " 的日期格式非法(应为 YYYY-MM-DD): " + s);
                    }
                }
                throw typeMismatch(column);
            case BOOLEAN:
                if (literal instanceof Boolean) {
                    return;
                }
                throw typeMismatch(column);
            default:
                throw typeMismatch(column);
        }
    }

    /**
     * 比较列值与常量。返回负数/0/正数。literal 为 null 时调用方不应调用(三值逻辑)。
     */
    public static int compare(Object columnValue, Column column, Object literal) {
        if (columnValue == null || literal == null) {
            throw new IllegalStateException("NULL 比较应由三值逻辑处理");
        }
        switch (column.type()) {
            case INT:
            case BIGINT:
                if (literal instanceof Double d) {
                    return Double.compare(((Number) columnValue).doubleValue(), d);
                }
                return Long.compare(toLong(columnValue), toLongForCompare(literal, column));
            case DOUBLE:
                return Double.compare(((Number) columnValue).doubleValue(), toDoubleForCompare(literal, column));
            case VARCHAR:
                if (!(literal instanceof String s)) {
                    throw typeMismatch(column);
                }
                return Arrays.compare(
                        ((String) columnValue).getBytes(StandardCharsets.UTF_8),
                        s.getBytes(StandardCharsets.UTF_8));
            case DATE:
                LocalDate d;
                if (literal instanceof LocalDate ld) {
                    d = ld;
                } else if (literal instanceof String s) {
                    try {
                        d = LocalDate.parse(s);
                    } catch (DateTimeParseException e) {
                        throw new StoreException("列 " + column.name()
                                + " 的日期格式非法(应为 YYYY-MM-DD): " + s);
                    }
                } else {
                    throw typeMismatch(column);
                }
                return ((LocalDate) columnValue).compareTo(d);
            case BOOLEAN:
                if (!(literal instanceof Boolean b)) {
                    throw typeMismatch(column);
                }
                return Boolean.compare((Boolean) columnValue, b);
            default:
                throw typeMismatch(column);
        }
    }

    private static long toLong(Object v) {
        return ((Number) v).longValue();
    }

    private static long toLongForCompare(Object literal, Column column) {
        if (literal instanceof Integer i) {
            return i;
        }
        if (literal instanceof Long l) {
            return l;
        }
        if (literal instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                throw typeMismatch(column);
            }
        }
        throw typeMismatch(column);
    }

    private static double toDoubleForCompare(Object literal, Column column) {
        if (literal instanceof Number n) {
            return n.doubleValue();
        }
        if (literal instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw typeMismatch(column);
            }
        }
        throw typeMismatch(column);
    }

    /** 结果表格里显示的值。NULL 显示为 {@code NULL}。 */
    public static String display(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Double d) {
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                return Long.toString(d.longValue());
            }
            return Double.toString(d);
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        return value.toString();
    }
}
