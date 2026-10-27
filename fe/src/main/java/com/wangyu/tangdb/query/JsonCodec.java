package com.wangyu.tangdb.query;

import com.wangyu.tangdb.storage.StoreException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 半结构化(SPEC-P5 §2.1-I / FR-609 ~ FR-611 / K117)。
 *
 * <p>存储编码是 <b>BSON 式二进制子集</b>:1 字节类型标记 + 长度前缀 + 字段名 + 值。
 * 支持的子集:对象、数组、字符串、整数(64 位)、小数、布尔、NULL。不做路径级列存、
 * 不做路径索引、不做整文档替换式更新。</p>
 *
 * <p>文本解析/输出是本项目自己写的最小 JSON 实现(不引第三方库)。</p>
 */
public final class JsonCodec {

    public static final byte T_DOUBLE = 0x01;
    public static final byte T_STRING = 0x02;
    public static final byte T_DOC = 0x03;
    public static final byte T_ARRAY = 0x04;
    public static final byte T_BOOL = 0x08;
    public static final byte T_NULL = 0x0A;
    public static final byte T_INT64 = 0x12;

    private JsonCodec() {
    }

    // ------------------------------------------------------------ 文本 → 结构

    /** 解析 JSON 文本;非法文档抛错(边界 61:明确报错,不写入半截值)。 */
    public static Object parseText(String text) {
        JsonParser parser = new JsonParser(text);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new StoreException("JSON 文档非法:位置 " + parser.pos + " 处有多余内容");
        }
        return value;
    }

    private static final class JsonParser {
        private final String s;
        private int pos;

        JsonParser(String s) {
            this.s = s == null ? "" : s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        Object parseValue() {
            skipWhitespace();
            if (atEnd()) {
                throw error("输入意外结束");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> parseNumber();
            };
        }

        private Object literal(String word, Object value) {
            if (!s.startsWith(word, pos)) {
                throw error("非法字面量");
            }
            pos += word.length();
            return value;
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (!atEnd() && s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || s.charAt(pos) != '"') {
                    throw error("对象的键必须是字符串");
                }
                String key = parseString();
                skipWhitespace();
                if (atEnd() || s.charAt(pos) != ':') {
                    throw error("对象的键后缺少 ':'");
                }
                pos++;
                map.put(key, parseValue());
                skipWhitespace();
                if (atEnd()) {
                    throw error("对象未闭合");
                }
                char c = s.charAt(pos++);
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw error("对象成员之间缺少 ','");
                }
            }
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (!atEnd() && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (atEnd()) {
                    throw error("数组未闭合");
                }
                char c = s.charAt(pos++);
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("数组元素之间缺少 ','");
                }
            }
        }

        private String parseString() {
            pos++; // "
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw error("字符串未闭合");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw error("转义字符被截断");
                }
                char esc = s.charAt(pos++);
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
                        if (pos + 4 > s.length()) {
                            throw error("\\u 转义被截断");
                        }
                        int cp = Integer.parseInt(s.substring(pos, pos + 4), 16);
                        pos += 4;
                        sb.append((char) cp);
                    }
                    default -> throw error("非法转义 \\" + esc);
                }
            }
        }

        private Object parseNumber() {
            int start = pos;
            if (!atEnd() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) {
                pos++;
            }
            boolean decimal = false;
            while (!atEnd()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    decimal = decimal || c == '.' || c == 'e' || c == 'E';
                    pos++;
                } else {
                    break;
                }
            }
            String token = s.substring(start, pos);
            if (token.isEmpty()) {
                throw error("这里需要一个值");
            }
            try {
                if (decimal) {
                    return Double.parseDouble(token);
                }
                return Long.parseLong(token);
            } catch (NumberFormatException e) {
                throw error("非法数字 " + token);
            }
        }

        private StoreException error(String message) {
            return new StoreException("JSON 文档非法:位置 " + pos + " " + message);
        }
    }

    // ------------------------------------------------------------ 结构 → 二进制

    public static byte[] encode(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeValue(out, value);
        return out.toByteArray();
    }

    private static void writeValue(ByteArrayOutputStream out, Object value) {
        if (value == null) {
            out.write(T_NULL);
            return;
        }
        if (value instanceof Boolean b) {
            out.write(T_BOOL);
            out.write(b ? 1 : 0);
            return;
        }
        if (value instanceof Integer || value instanceof Long) {
            out.write(T_INT64);
            long v = ((Number) value).longValue();
            for (int i = 7; i >= 0; i--) {
                out.write((int) (v >>> (i * 8)));
            }
            return;
        }
        if (value instanceof Double || value instanceof Float) {
            out.write(T_DOUBLE);
            long bits = Double.doubleToLongBits(((Number) value).doubleValue());
            for (int i = 7; i >= 0; i--) {
                out.write((int) (bits >>> (i * 8)));
            }
            return;
        }
        if (value instanceof String s) {
            out.write(T_STRING);
            writeBytes(out, s.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (value instanceof List<?> list) {
            out.write(T_ARRAY);
            writeInt32(out, list.size());
            for (Object item : list) {
                writeValue(out, item);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            out.write(T_DOC);
            writeInt32(out, map.size());
            for (Map.Entry<?, ?> e : map.entrySet()) {
                byte[] name = String.valueOf(e.getKey()).getBytes(StandardCharsets.UTF_8);
                writeInt32(out, name.length);
                out.writeBytes(name);
                writeValue(out, e.getValue());
            }
            return;
        }
        throw new StoreException("JSON 不支持的值类型: " + value.getClass().getSimpleName());
    }

    private static void writeInt32(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] b) {
        writeInt32(out, b.length);
        out.writeBytes(b);
    }

    // ------------------------------------------------------------ 二进制 → 结构

    public static Object decode(byte[] data) {
        if (data == null || data.length == 0) {
            throw new StoreException("JSON 列值为空");
        }
        Reader reader = new Reader(data);
        Object value = reader.readValue();
        if (!reader.atEnd()) {
            throw new StoreException("JSON 二进制尾部有多余字节(" + (data.length - reader.pos) + ")");
        }
        return value;
    }

    private static final class Reader {
        private final byte[] data;
        private int pos;

        Reader(byte[] data) {
            this.data = data;
        }

        boolean atEnd() {
            return pos >= data.length;
        }

        Object readValue() {
            byte type = readByte();
            return switch (type) {
                case T_NULL -> null;
                case T_BOOL -> {
                    byte b = readByte();
                    yield b != 0;
                }
                case T_INT64 -> readLong();
                case T_DOUBLE -> Double.longBitsToDouble(readLong());
                case T_STRING -> new String(readBlock(), StandardCharsets.UTF_8);
                case T_ARRAY -> {
                    int n = readInt32();
                    List<Object> list = new ArrayList<>(Math.max(0, n));
                    for (int i = 0; i < n; i++) {
                        list.add(readValue());
                    }
                    yield list;
                }
                case T_DOC -> {
                    int n = readInt32();
                    Map<String, Object> map = new LinkedHashMap<>();
                    for (int i = 0; i < n; i++) {
                        String name = new String(readBlock(), StandardCharsets.UTF_8);
                        map.put(name, readValue());
                    }
                    yield map;
                }
                default -> throw new StoreException("JSON 二进制类型标记无法识别: " + (type & 0xFF));
            };
        }

        private byte readByte() {
            if (pos >= data.length) {
                throw new StoreException("JSON 二进制被截断");
            }
            return data[pos++];
        }

        private int readInt32() {
            if (pos + 4 > data.length) {
                throw new StoreException("JSON 二进制被截断");
            }
            int v = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                    | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            pos += 4;
            if (v < 0) {
                throw new StoreException("JSON 二进制长度非法: " + v);
            }
            return v;
        }

        private long readLong() {
            if (pos + 8 > data.length) {
                throw new StoreException("JSON 二进制被截断");
            }
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (data[pos + i] & 0xFFL);
            }
            pos += 8;
            return v;
        }

        private byte[] readBlock() {
            int len = readInt32();
            if (pos + len > data.length) {
                throw new StoreException("JSON 二进制块被截断(需要 " + len + " 字节)");
            }
            byte[] b = new byte[len];
            System.arraycopy(data, pos, b, 0, len);
            pos += len;
            return b;
        }
    }

    // ------------------------------------------------------------ 结构 → 文本

    public static String toText(Object value) {
        StringBuilder sb = new StringBuilder();
        writeText(sb, value);
        return sb.toString();
    }

    private static void writeText(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
            return;
        }
        if (value instanceof Number n) {
            sb.append(n);
            return;
        }
        if (value instanceof String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            sb.append('"');
            return;
        }
        if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeText(sb, list.get(i));
            }
            sb.append(']');
            return;
        }
        if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeText(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeText(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        sb.append('"').append(value).append('"');
    }

    // ------------------------------------------------------------ json_extract(FR-610)

    /**
     * {@code json_extract(doc, '$.a.b')} / {@code '$.arr[0]'}。
     * 路径不存在、类型不符、取到对象/数组当标量 → NULL(边界 62,不报错)。
     */
    public static Object extract(Object doc, String path) {
        if (doc == null || path == null) {
            return null;
        }
        String p = path.trim();
        if (!p.startsWith("$")) {
            return null;
        }
        Object current = doc;
        int i = 1;
        while (i < p.length()) {
            char c = p.charAt(i);
            if (c == '.') {
                i++;
                int start = i;
                while (i < p.length() && p.charAt(i) != '.' && p.charAt(i) != '[') {
                    i++;
                }
                if (start == i) {
                    return null;
                }
                String key = p.substring(start, i);
                if (!(current instanceof Map<?, ?> map)) {
                    return null;
                }
                current = map.get(key);
                if (current == null && !map.containsKey(key)) {
                    return null;
                }
            } else if (c == '[') {
                int close = p.indexOf(']', i);
                if (close < 0) {
                    return null;
                }
                String indexText = p.substring(i + 1, close).trim();
                int index;
                try {
                    index = Integer.parseInt(indexText);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (!(current instanceof List<?> list) || index < 0 || index >= list.size()) {
                    return null;
                }
                current = list.get(index);
                i = close + 1;
            } else {
                return null;
            }
        }
        if (current instanceof Map || current instanceof List) {
            return null; // 取对象/数组当标量 → NULL
        }
        return current;
    }
}
