package com.wangyu.tangdb.tools;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 标准 CSV 解析器(SPEC-P5 边界 54 / 55):支持字段内逗号、换行、双引号转义({@code ""}),
 * 以及 {@code CRLF} / {@code LF} 两种行尾。**不引第三方库**。
 *
 * <p>按字节解析(分隔符都是 ASCII),字段内容按 UTF-8 解码;同时记录每条记录的
 * 起始 / 结束**字节偏移**,供断点续传(FR-608)。</p>
 */
public final class CsvParser implements Closeable {

    /** 一个字段;quoted 区分 {@code ""}(空串)与未加引号的空字段(NULL)。 */
    public record Field(String value, boolean quoted) {
        public boolean isNull() {
            return !quoted && value.isEmpty();
        }
    }

    /** 一条记录。startOffset / endOffset 是字节偏移(含行尾)。 */
    public record Record(List<Field> fields, long startOffset, long endOffset) {
    }

    private final CountingInputStream in;
    private long offset;

    public CsvParser(InputStream raw, long startOffset) throws IOException {
        this.in = new CountingInputStream(raw);
        this.offset = 0;
        if (startOffset > 0) {
            in.skipNBytes(startOffset);
            this.offset = startOffset;
        }
    }

    /** 读取下一条记录;文件结束返回 null。 */
    public Record next() throws IOException {
        int first = read();
        if (first < 0) {
            return null;
        }
        long start = offset - 1;
        List<Field> fields = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean inQuotes = false;
        boolean fieldStarted = false;

        int b = first;
        while (true) {
            if (b < 0) {
                fields.add(new Field(cell.toString(), quoted));
                return new Record(fields, start, offset);
            }
            if (inQuotes) {
                if (b == '"') {
                    int nextByte = read();
                    if (nextByte == '"') {
                        cell.append('"');
                        b = read();
                        continue;
                    }
                    inQuotes = false;
                    b = nextByte;
                    continue;
                }
                appendUtf8(cell, b);
                b = read();
                continue;
            }
            switch (b) {
                case '"' -> {
                    if (cell.length() > 0) {
                        throw new IOException("CSV 非法:第 " + offset + " 字节处引号不在字段开头");
                    }
                    quoted = true;
                    inQuotes = true;
                    fieldStarted = true;
                    b = read();
                }
                case ',' -> {
                    fields.add(new Field(cell.toString(), quoted));
                    cell.setLength(0);
                    quoted = false;
                    fieldStarted = false;
                    b = read();
                }
                case '\r' -> {
                    int nextByte = read();
                    if (nextByte != '\n' && nextByte >= 0) {
                        throw new IOException("CSV 非法:CR 之后不是 LF(第 " + offset + " 字节)");
                    }
                    fields.add(new Field(cell.toString(), quoted));
                    return new Record(fields, start, offset);
                }
                case '\n' -> {
                    fields.add(new Field(cell.toString(), quoted));
                    return new Record(fields, start, offset);
                }
                default -> {
                    appendUtf8(cell, b);
                    fieldStarted = true;
                    b = read();
                }
            }
            if (fieldStarted) {
                // 仅为可读性;fieldStarted 不参与语义
            }
        }
    }

    private void appendUtf8(StringBuilder sb, int firstByte) throws IOException {
        if (firstByte < 0x80) {
            sb.append((char) firstByte);
            return;
        }
        int extra;
        if ((firstByte & 0xE0) == 0xC0) {
            extra = 1;
        } else if ((firstByte & 0xF0) == 0xE0) {
            extra = 2;
        } else if ((firstByte & 0xF8) == 0xF0) {
            extra = 3;
        } else {
            throw new IOException("CSV 非法:UTF-8 起始字节非法 " + firstByte);
        }
        byte[] bytes = new byte[1 + extra];
        bytes[0] = (byte) firstByte;
        for (int i = 1; i < bytes.length; i++) {
            int next = read();
            if (next < 0) {
                throw new EOFException("CSV 非法:UTF-8 字符被截断");
            }
            bytes[i] = (byte) next;
        }
        sb.append(new String(bytes, StandardCharsets.UTF_8));
    }

    private int read() throws IOException {
        int b = in.read();
        if (b >= 0) {
            offset++;
        }
        return b;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    /** 统计字节流里的换行数(粗估总行数,用于 progress 的分母)。 */
    public static long estimateLines(InputStream raw) throws IOException {
        long lines = 0;
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = raw.read(buf)) > 0) {
            for (int i = 0; i < n; i++) {
                if (buf[i] == '\n') {
                    lines++;
                }
            }
        }
        return lines;
    }

    private static final class CountingInputStream extends FilterInputStream {
        private long position;

        CountingInputStream(InputStream in) {
            super(in instanceof BufferedInputStream ? in : new BufferedInputStream(in, 1 << 16));
            if (in instanceof BufferedInputStream) {
                // 已经是缓冲流(estimateLines 之后重新打开时不会走到这里)
                position = 0;
            }
        }

        long position() {
            return position;
        }

        @Override
        public void skipNBytes(long n) throws IOException {
            long remaining = n;
            while (remaining > 0) {
                long skipped = in.skip(remaining);
                if (skipped <= 0) {
                    if (in.read() < 0) {
                        throw new EOFException("跳过的字节数超过文件长度");
                    }
                    skipped = 1;
                }
                remaining -= skipped;
                position += skipped;
            }
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                position++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                position += n;
            }
            return n;
        }
    }
}
