package com.wangyu.tangdb.storage;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * 行值编码(规约 §5.3)。
 *
 * <pre>
 * 行:   [格式版本 1B=0x01][表 ID 4B 小端][列数 2B 小端][NULL 位图 ⌈列数/8⌉ B][列值...]
 * 墓碑: [格式版本 1B=0x02][表 ID 4B 小端]
 * </pre>
 *
 * <p>NULL 位图 1 表示该列为 NULL;NULL 列不占列值空间。格式版本不识别必须报错,不得猜。</p>
 */
public final class RowCodec {

    public static final byte FORMAT_ROW = 0x01;
    public static final byte FORMAT_TOMBSTONE = 0x02;

    private RowCodec() {
    }

    /** 解码结果。tombstone 为 true 时 values 为 null。 */
    public record Decoded(boolean tombstone, Object[] values) {
    }

    /** 编码一行。values 的顺序必须与表定义的列顺序一致。 */
    public static byte[] encode(TableMeta table, Object[] values) {
        if (values.length != table.columnCount()) {
            throw new StoreException("行数据列数不匹配(表 " + table.name() + " 期望 "
                    + table.columnCount() + ",实际 " + values.length + ")");
        }

        int nullBitmapLen = (values.length + 7) / 8;
        byte[] body = new byte[1 + 4 + 2 + nullBitmapLen + estimate(values)];
        int pos = 0;
        body[pos++] = FORMAT_ROW;
        pos = writeLe4(body, pos, table.id());
        pos = writeLe2(body, pos, values.length);
        int bitmapPos = pos;
        pos += nullBitmapLen;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                body[bitmapPos + i / 8] |= (byte) (1 << (i % 8));
            }
        }
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                continue;
            }
            pos = writeColumnValue(body, pos, table.columns().get(i), values[i]);
        }
        return java.util.Arrays.copyOf(body, pos);
    }

    /** 编码墓碑。 */
    public static byte[] encodeTombstone(long tableId) {
        byte[] out = new byte[5];
        out[0] = FORMAT_TOMBSTONE;
        writeLe4(out, 1, tableId);
        return out;
    }

    /** 解码一行;版本不识别、表 ID 不符、列数不符都报错。 */
    public static Decoded decode(TableMeta table, byte[] data) {
        if (data == null || data.length < 1) {
            throw new StoreException("行数据为空");
        }
        byte version = data[0];
        if (version == FORMAT_TOMBSTONE) {
            if (data.length < 5) {
                throw new StoreException("墓碑数据长度非法: " + data.length);
            }
            long tableId = readLe4(data, 1);
            if (tableId != table.id()) {
                throw new StoreException("墓碑表 ID 不匹配(期望 " + table.id() + ",实际 " + tableId + ")");
            }
            return new Decoded(true, null);
        }
        if (version != FORMAT_ROW) {
            throw new StoreException("未知的行格式版本: " + (version & 0xff));
        }
        if (data.length < 7) {
            throw new StoreException("行数据长度非法: " + data.length);
        }
        long tableId = readLe4(data, 1);
        if (tableId != table.id()) {
            throw new StoreException("行表 ID 不匹配(期望 " + table.id() + ",实际 " + tableId + ")");
        }
        int columnCount = readLe2(data, 5);
        if (columnCount != table.columnCount()) {
            throw new StoreException("行数据列数不匹配(表 " + table.name() + " 期望 "
                    + table.columnCount() + ",实际 " + columnCount + ")");
        }
        int nullBitmapLen = (columnCount + 7) / 8;
        int pos = 7 + nullBitmapLen;
        if (pos > data.length) {
            throw new StoreException("行数据 NULL 位图被截断");
        }
        Object[] values = new Object[columnCount];
        for (int i = 0; i < columnCount; i++) {
            boolean isNull = (data[7 + i / 8] & (1 << (i % 8))) != 0;
            if (isNull) {
                values[i] = null;
                continue;
            }
            Column column = table.columns().get(i);
            switch (column.type()) {
                case INT:
                    require(data, pos, 4);
                    values[i] = readLe4Int(data, pos);
                    pos += 4;
                    break;
                case BIGINT:
                    require(data, pos, 8);
                    values[i] = readLe8(data, pos);
                    pos += 8;
                    break;
                case DOUBLE:
                    require(data, pos, 8);
                    values[i] = Double.longBitsToDouble(readLe8(data, pos));
                    pos += 8;
                    break;
                case BOOLEAN:
                    require(data, pos, 1);
                    values[i] = data[pos++] != 0;
                    break;
                case DATE:
                    require(data, pos, 4);
                    values[i] = LocalDate.ofEpochDay(readLe4Int(data, pos));
                    pos += 4;
                    break;
                case VARCHAR: {
                    require(data, pos, 4);
                    int len = readLe4Int(data, pos);
                    pos += 4;
                    require(data, pos, len);
                    values[i] = new String(data, pos, len, StandardCharsets.UTF_8);
                    pos += len;
                    break;
                }
                case JSON: {
                    require(data, pos, 4);
                    int len = readLe4Int(data, pos);
                    pos += 4;
                    require(data, pos, len);
                    byte[] raw = new byte[len];
                    System.arraycopy(data, pos, raw, 0, len);
                    values[i] = raw;
                    pos += len;
                    break;
                }
                default:
                    throw new StoreException("不支持的列类型: " + column.type());
            }
        }
        if (pos != data.length) {
            throw new StoreException("行数据尾部有多余字节(表 " + table.name() + ")");
        }
        return new Decoded(false, values);
    }

    private static int writeColumnValue(byte[] buf, int pos, Column column, Object value) {
        switch (column.type()) {
            case INT:
                return writeLe4(buf, pos, ((Number) value).intValue());
            case BIGINT:
                return writeLe8(buf, pos, ((Number) value).longValue());
            case DOUBLE:
                return writeLe8(buf, pos, Double.doubleToLongBits(((Number) value).doubleValue()));
            case BOOLEAN:
                buf[pos] = (byte) ((Boolean) value ? 1 : 0);
                return pos + 1;
            case DATE:
                return writeLe4(buf, pos, (int) ((LocalDate) value).toEpochDay());
            case VARCHAR: {
                byte[] raw = ((String) value).getBytes(StandardCharsets.UTF_8);
                pos = writeLe4(buf, pos, raw.length);
                System.arraycopy(raw, 0, buf, pos, raw.length);
                return pos + raw.length;
            }
            case JSON: {
                byte[] raw = (byte[]) value;
                pos = writeLe4(buf, pos, raw.length);
                System.arraycopy(raw, 0, buf, pos, raw.length);
                return pos + raw.length;
            }
            default:
                throw new StoreException("不支持的列类型: " + column.type());
        }
    }

    private static int estimate(Object[] values) {
        int n = 0;
        for (Object v : values) {
            if (v == null) {
                continue;
            }
            if (v instanceof String s) {
                n += 4 + s.getBytes(StandardCharsets.UTF_8).length;
            } else if (v instanceof byte[] b) {
                n += 4 + b.length;
            } else if (v instanceof Double || v instanceof Long) {
                n += 8;
            } else if (v instanceof Integer || v instanceof LocalDate) {
                n += 4;
            } else if (v instanceof Boolean) {
                n += 1;
            }
        }
        return n;
    }

    private static void require(byte[] data, int pos, int len) {
        if (len < 0 || pos + len > data.length) {
            throw new StoreException("行数据被截断");
        }
    }

    private static int writeLe4(byte[] buf, int pos, long v) {
        buf[pos] = (byte) v;
        buf[pos + 1] = (byte) (v >>> 8);
        buf[pos + 2] = (byte) (v >>> 16);
        buf[pos + 3] = (byte) (v >>> 24);
        return pos + 4;
    }

    private static int writeLe2(byte[] buf, int pos, int v) {
        buf[pos] = (byte) v;
        buf[pos + 1] = (byte) (v >>> 8);
        return pos + 2;
    }

    private static int writeLe8(byte[] buf, int pos, long v) {
        for (int i = 0; i < 8; i++) {
            buf[pos + i] = (byte) (v >>> (i * 8));
        }
        return pos + 8;
    }

    private static long readLe4(byte[] buf, int pos) {
        return (buf[pos] & 0xffL)
                | ((buf[pos + 1] & 0xffL) << 8)
                | ((buf[pos + 2] & 0xffL) << 16)
                | ((buf[pos + 3] & 0xffL) << 24);
    }

    private static int readLe4Int(byte[] buf, int pos) {
        return (int) readLe4(buf, pos);
    }

    private static int readLe2(byte[] buf, int pos) {
        return (buf[pos] & 0xff) | ((buf[pos + 1] & 0xff) << 8);
    }

    private static long readLe8(byte[] buf, int pos) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (buf[pos + i] & 0xffL);
        }
        return v;
    }
}
