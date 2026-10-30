package com.wangyu.tangdb.slow;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 慢查询用的 SQL 文本处理(SPEC-P8 FR-832 / K190 / 边界 189)。
 *
 * <p>摘要:超过 4KB 时保留首尾、中间打 {@code ...};指纹:去掉字面量、折叠空白后的
 * 规范化文本(同族语句聚合用,可复现)。</p>
 */
public final class SqlText {

    /** 摘要上限(FR-832:截断到 4KB)。 */
    public static final int MAX_SUMMARY_BYTES = 4096;

    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^'\\\\]|\\\\.|'')*'");
    private static final Pattern NUMBER_LITERAL =
            Pattern.compile("(?<![A-Za-z0-9_$.])-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private SqlText() {
    }

    /**
     * SQL 摘要:超过 4KB 时保留首 3KB + {@code ...} + 尾 1KB(边界 189)。
     * 按 UTF-8 字节截断,不会把多字节字符切坏。
     */
    public static String summary(String sql) {
        String text = sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= MAX_SUMMARY_BYTES) {
            return text;
        }
        String separator = " ... ";
        int tailBytes = 1024;
        int headBytes = MAX_SUMMARY_BYTES - tailBytes - separator.length();
        String head = cutUtf8(bytes, 0, headBytes);
        String tail = tailUtf8(bytes, tailBytes);
        return head + separator + tail;
    }

    private static String cutUtf8(byte[] bytes, int from, int length) {
        int end = Math.min(bytes.length, from + length);
        // 回退到字符边界:UTF-8 续字节以 10xxxxxx 开头
        while (end > from && end < bytes.length && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, from, end - from, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String tailUtf8(byte[] bytes, int tailBytes) {
        int start = Math.max(0, bytes.length - tailBytes);
        while (start < bytes.length && (bytes[start] & 0xC0) == 0x80) {
            start++;
        }
        return new String(bytes, start, bytes.length - start,
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * SQL 指纹(FR-832 / K190):字符串与数字字面量替换为 {@code ?},空白折叠为单空格,
     * 去掉首尾分号。同一条 SQL 同版本永远得到同一指纹(FR-836 的可复现要求)。
     */
    public static String fingerprint(String sql) {
        if (sql == null) {
            return "";
        }
        String text = STRING_LITERAL.matcher(sql).replaceAll("?");
        text = NUMBER_LITERAL.matcher(text).replaceAll("?");
        text = WHITESPACE.matcher(text).replaceAll(" ").trim();
        while (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        return text;
    }

    /** 取语句的第一个关键字(大写);空语句返回空串。 */
    public static String leadingKeyword(String sql) {
        if (sql == null) {
            return "";
        }
        Matcher matcher = Pattern.compile("\\s*([A-Za-z]+)").matcher(sql);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : "";
    }
}
