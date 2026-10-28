package com.wangyu.tangdb.stats;

import com.wangyu.tangdb.storage.DataType;
import com.wangyu.tangdb.storage.Values;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 等深直方图(SPEC-P6 FR-618 / K124 / §5.2)。
 *
 * <p>存桶上界值与桶内行数;JSON 形态固定为:</p>
 * <pre>{"buckets":64,"bounds":["10","27","55"],"counts":[120,98,101]}</pre>
 *
 * <p>{@code bounds} 与 {@code counts} 等长、长度 ≤ buckets;空表是
 * {@code {"buckets":64,"bounds":[],"counts":[]}}。JSON 由本类手写读写,不引入第三方库。</p>
 *
 * <p>{@code bounds} 内部**统一用文本**保存(与落库形态一致):构造时由值转文本,
 * 估算时按列类型把文本还原成可比的值。</p>
 */
public final class Histogram {

    /** VARCHAR 的 min/max/上界文本上限(边界 71:截断到 64 字节并保持可比较前缀)。 */
    public static final int VARCHAR_BOUND_BYTES = 64;

    private final int buckets;
    private final List<String> bounds;
    private final List<Long> counts;

    private Histogram(int buckets, List<String> bounds, List<Long> counts) {
        this.buckets = buckets;
        this.bounds = List.copyOf(bounds);
        this.counts = List.copyOf(counts);
    }

    public static Histogram empty(int buckets) {
        return new Histogram(buckets, List.of(), List.of());
    }

    /** 由现成桶重建(抽样缩放时用;bounds 文本与 counts 必须等长)。 */
    public static Histogram rebuild(int buckets, List<String> bounds, List<Long> counts) {
        int size = Math.min(bounds.size(), counts.size());
        return new Histogram(buckets, bounds.subList(0, size), counts.subList(0, size));
    }

    /**
     * 由**已排序**的值构造等深桶。
     *
     * @param sorted 升序排列的非 NULL 值(调用方保证有序)
     */
    public static Histogram build(List<Object> sorted, int buckets) {
        int b = buckets < 1 ? 64 : buckets;
        int n = sorted.size();
        if (n == 0) {
            return empty(b);
        }
        List<String> bounds = new ArrayList<>(Math.min(b, n));
        List<Long> counts = new ArrayList<>(Math.min(b, n));
        int prev = 0;
        for (int i = 1; i <= b; i++) {
            int end = (int) Math.ceil((double) i * n / b);
            if (end <= prev) {
                continue;
            }
            if (end > n) {
                end = n;
            }
            bounds.add(boundText(sorted.get(end - 1)));
            counts.add((long) (end - prev));
            prev = end;
            if (prev >= n) {
                break;
            }
        }
        return new Histogram(b, bounds, counts);
    }

    public int buckets() {
        return buckets;
    }

    public List<Long> counts() {
        return counts;
    }

    /** 桶上界的文本形态(与落库 JSON 一致)。 */
    public List<String> bounds() {
        return bounds;
    }

    public long totalCount() {
        long n = 0;
        for (long c : counts) {
            n += c;
        }
        return n;
    }

    public boolean isEmpty() {
        return bounds.isEmpty();
    }

    // ------------------------------------------------------------ 选择率

    /** 估算 {@code column < value} 的行数(直方图累计;桶内无法插值时忽略该桶,误差 ≤ 1 桶)。 */
    public long countLessThan(Object value, DataType type) {
        return countBound(value, type, true);
    }

    /** 估算 {@code column <= value} 的行数。 */
    public long countLessThanOrEqual(Object value, DataType type) {
        return countBound(value, type, false);
    }

    private long countBound(Object value, DataType type, boolean strict) {
        Object v = comparable(value, type);
        if (v == null || bounds.isEmpty()) {
            return 0;
        }
        long sum = 0;
        for (int i = 0; i < bounds.size(); i++) {
            Object bound = boundValue(bounds.get(i), type);
            int cmp;
            try {
                cmp = Values.compare(bound, column(type), v);
            } catch (RuntimeException e) {
                return sum;
            }
            boolean take = strict ? cmp < 0 : cmp <= 0;
            if (!take) {
                break;
            }
            sum += counts.get(i);
        }
        return sum;
    }

    /** 把条件里的常量按列类型归一(INT 列上的 Long 常量等),便于与上界比较。 */
    private static Object comparable(Object value, DataType type) {
        if (value == null) {
            return null;
        }
        if (type == DataType.INT || type == DataType.BIGINT) {
            if (value instanceof Number n) {
                return n.longValue();
            }
            return value;
        }
        if (type == DataType.DOUBLE && value instanceof Number n) {
            return n.doubleValue();
        }
        return value;
    }

    // ------------------------------------------------------------ JSON

    public String toJson() {
        StringBuilder sb = new StringBuilder(32 + bounds.size() * 12);
        sb.append("{\"buckets\":").append(buckets).append(",\"bounds\":[");
        for (int i = 0; i < bounds.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(bounds.get(i))).append('"');
        }
        sb.append("],\"counts\":[");
        for (int i = 0; i < counts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(counts.get(i));
        }
        return sb.append("]}").toString();
    }

    /** 解析 {@link #toJson()} 的形态;非法 JSON 返回空直方图(统计损坏不应打断查询)。 */
    public static Histogram parse(String json, int defaultBuckets) {
        if (json == null || json.isBlank()) {
            return empty(defaultBuckets);
        }
        try {
            int[] pos = {0};
            int buckets = defaultBuckets;
            List<String> bounds = new ArrayList<>();
            List<Long> counts = new ArrayList<>();
            expect(json, pos, '{');
            while (true) {
                if (json.charAt(pos[0]) == '}') {
                    break;
                }
                String key = readString(json, pos);
                expect(json, pos, ':');
                if ("buckets".equals(key)) {
                    buckets = (int) readLong(json, pos);
                } else if ("bounds".equals(key)) {
                    expect(json, pos, '[');
                    while (json.charAt(pos[0]) != ']') {
                        bounds.add(readString(json, pos));
                        if (json.charAt(pos[0]) == ',') {
                            pos[0]++;
                        }
                    }
                    pos[0]++;
                } else if ("counts".equals(key)) {
                    expect(json, pos, '[');
                    while (json.charAt(pos[0]) != ']') {
                        counts.add(readLong(json, pos));
                        if (json.charAt(pos[0]) == ',') {
                            pos[0]++;
                        }
                    }
                    pos[0]++;
                } else {
                    skipValue(json, pos);
                }
                if (json.charAt(pos[0]) == ',') {
                    pos[0]++;
                    continue;
                }
                break;
            }
            expect(json, pos, '}');
            int size = Math.min(bounds.size(), counts.size());
            return new Histogram(buckets, bounds.subList(0, size), counts.subList(0, size));
        } catch (RuntimeException e) {
            return empty(defaultBuckets);
        }
    }

    private static void expect(String json, int[] pos, char c) {
        if (pos[0] >= json.length() || json.charAt(pos[0]) != c) {
            throw new IllegalArgumentException("期望 '" + c + "'");
        }
        pos[0]++;
    }

    private static String readString(String json, int[] pos) {
        expect(json, pos, '"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = json.charAt(pos[0]++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char e = json.charAt(pos[0]++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(json.substring(pos[0], pos[0] + 4), 16));
                        pos[0] += 4;
                    }
                    default -> sb.append(e);
                }
                continue;
            }
            sb.append(c);
        }
    }

    private static long readLong(String json, int[] pos) {
        int start = pos[0];
        while (pos[0] < json.length()
                && (Character.isDigit(json.charAt(pos[0])) || json.charAt(pos[0]) == '-')) {
            pos[0]++;
        }
        return Long.parseLong(json.substring(start, pos[0]));
    }

    private static void skipValue(String json, int[] pos) {
        int depth = 0;
        while (pos[0] < json.length()) {
            char c = json.charAt(pos[0]);
            if (c == '"') {
                readString(json, pos);
                continue;
            }
            if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                if (depth == 0) {
                    return;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                return;
            }
            pos[0]++;
        }
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 文本 ⇄ 值

    /** min/max 与直方图上界的文本表示(§5.2):DATE = YYYY-MM-DD,DOUBLE 用最短往返文本。 */
    public static String boundText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return truncateUtf8(s, VARCHAR_BOUND_BYTES);
        }
        return Values.display(value);
    }

    /** 把存储的文本还原成列类型对应的值;无法解析返回文本本身(不打断查询)。 */
    public static Object boundValue(String text, DataType type) {
        if (text == null) {
            return null;
        }
        try {
            return switch (type) {
                case INT, BIGINT -> Long.parseLong(text);
                case DOUBLE -> Double.parseDouble(text);
                case DATE -> LocalDate.parse(text);
                case BOOLEAN -> "true".equalsIgnoreCase(text);
                default -> text;
            };
        } catch (RuntimeException e) {
            return text;
        }
    }

    /** 截断到最多 maxBytes 个 UTF-8 字节,且不切断多字节字符。 */
    public static String truncateUtf8(String s, int maxBytes) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return s;
        }
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    /** 直方图估算用的列定义(只为复用 {@link Values#compare} 的类型分支)。 */
    private static com.wangyu.tangdb.storage.Column column(DataType type) {
        return new com.wangyu.tangdb.storage.Column("_hist", type, 65535, true, false, 0);
    }

    static double clamp(double p) {
        if (Double.isNaN(p)) {
            return 0;
        }
        return Math.max(0, Math.min(1, p));
    }
}
