package com.wangyu.tangdb.console;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 编解码(手写,零第三方库;SPEC-P8 依赖红线:FE 不得新增任何 Java 库)。
 *
 * <p>只覆盖控制台需要的形态:对象 / 数组 / 字符串 / 数字 / 布尔 / null。
 * 序列化用 {@link #write(Object)}(支持 Map / List / String / Number / Boolean / null / {@link Raw});
 * 反序列化用 {@link #parse(String)},请求体解析失败抛 {@link IllegalArgumentException}。</p>
 */
public final class Json {

    private Json() {
    }

    /** 已经是 JSON 片段(不再转义)。 */
    public record Raw(String text) {
    }

    public static Raw raw(String text) {
        return new Raw(text);
    }

    public static Map<String, Object> object() {
        return new LinkedHashMap<>();
    }

    /** 序列化任意受支持的值。 */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof Raw raw) {
            sb.append(raw.text());
            return;
        }
        if (value instanceof String text) {
            writeString(sb, text);
            return;
        }
        if (value instanceof Boolean || value instanceof Number) {
            sb.append(value);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                writeValue(sb, entry.getValue());
            }
            sb.append('}');
            return;
        }
        if (value instanceof Iterable<?> items) {
            sb.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
            return;
        }
        if (value instanceof Object[] array) {
            sb.append('[');
            for (int i = 0; i < array.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeValue(sb, array[i]);
            }
            sb.append(']');
            return;
        }
        writeString(sb, String.valueOf(value));
    }

    private static void writeString(StringBuilder sb, String text) {
        sb.append('"').append(ConsoleApi.escape(text)).append('"');
    }

    // ------------------------------------------------------------ 解析

    /** 解析一个 JSON 文本;对象返回 {@code Map<String,Object>},数组返回 {@code List<Object>}。 */
    public static Object parse(String text) {
        Parser parser = new Parser(text == null ? "" : text);
        parser.skipWhitespace();
        Object value = parser.value();
        parser.skipWhitespace();
        if (!parser.end()) {
            throw new IllegalArgumentException("JSON 尾部有多余内容");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("请求体必须是 JSON 对象");
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean end() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        Object value() {
            skipWhitespace();
            if (end()) {
                throw new IllegalArgumentException("JSON 意外结束");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Object literal(String word, Object value) {
            if (!text.startsWith(word, pos)) {
                throw new IllegalArgumentException("JSON 字面量无效,位置 " + pos);
            }
            pos += word.length();
            return value;
        }

        Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (!end() && text.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                expect(':');
                map.put(key, value());
                skipWhitespace();
                if (end()) {
                    throw new IllegalArgumentException("JSON 对象未闭合");
                }
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                expect('}');
                return map;
            }
        }

        List<Object> array() {
            List<Object> items = new ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (!end() && text.charAt(pos) == ']') {
                pos++;
                return items;
            }
            while (true) {
                items.add(value());
                skipWhitespace();
                if (end()) {
                    throw new IllegalArgumentException("JSON 数组未闭合");
                }
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                expect(']');
                return items;
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (end()) {
                    throw new IllegalArgumentException("JSON 字符串未闭合");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (end()) {
                    throw new IllegalArgumentException("JSON 转义未完整");
                }
                char esc = text.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw new IllegalArgumentException("JSON \\u 转义不完整");
                        }
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("JSON 非法转义: \\" + esc);
                }
            }
        }

        Number number() {
            int start = pos;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e'
                        || c == 'E') {
                    pos++;
                } else {
                    break;
                }
            }
            String raw = text.substring(start, pos);
            if (raw.isEmpty()) {
                throw new IllegalArgumentException("JSON 值无效,位置 " + start);
            }
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                return Double.parseDouble(raw);
            }
            return Long.parseLong(raw);
        }

        void expect(char expected) {
            if (end() || text.charAt(pos) != expected) {
                throw new IllegalArgumentException("JSON 期望 '" + expected + "',位置 " + pos);
            }
            pos++;
        }
    }
}
