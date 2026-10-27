package com.wangyu.tangdb.storage;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.Arrays;

/**
 * 索引 key 的编码/解码(SPEC-P4 §5.1 ~ §5.3 / §5.5 / K76 / K77)。
 *
 * <pre>
 * 二级索引用户键: [表ID 4B][索引ID 4B][索引列值保序编码][主键值保序编码]
 * 唯一索引用户键: [表ID 4B][索引ID 4B][索引列值保序编码]      value = 主键值保序编码
 * </pre>
 *
 * <p><b>写路径与读路径必须共用本类</b>(FR-497 / FR-521):索引值的编码只有这里一份实现。</p>
 *
 * <p>VARCHAR 的索引列值编码按 K77 自定界:值内 {@code 0x00} 转义成 {@code 0x00 0xFF},
 * 结尾固定 {@code 0x00 0x00};其它类型复用主键的定长保序编码。由此
 * {@code [索引列值][主键值]} 的拼接天然无歧义(§6 第 1 条)。</p>
 */
public final class IndexCodec {

    private IndexCodec() {
    }

    // ------------------------------------------------------------ 元数据辅助

    /** 索引列定义;索引指向不存在的列属于元数据错误(FR-476)。 */
    public static Column indexColumn(TableMeta table, IndexMeta index) {
        Column column = table.column(index.columnName());
        if (column == null) {
            throw new StoreException("索引 " + index.name() + " 指向不存在的列 "
                    + index.columnName() + "(表 " + table.name() + ")");
        }
        return column;
    }

    /** 该列类型能否建索引(FR-456:INT / BIGINT / VARCHAR / DATE / BOOLEAN)。 */
    public static boolean indexable(DataType type) {
        return type == DataType.INT || type == DataType.BIGINT || type == DataType.VARCHAR
                || type == DataType.DATE || type == DataType.BOOLEAN;
    }

    // ------------------------------------------------------------ 索引项 key / value

    /** 索引前缀:表 ID + 索引 ID(8 字节)。 */
    public static byte[] prefix(long tableId, int indexId) {
        return KeyCodec.prefix(tableId, indexId);
    }

    /**
     * 一条索引项的完整用户键。
     *
     * @param columnValue 索引列的值(非 NULL;NULL 行不入索引,K79)
     * @param pkValue     主键值(唯一索引忽略它,key 里故意不含主键,K76)
     */
    public static byte[] entryKey(TableMeta table, IndexMeta index, Object columnValue, Object pkValue) {
        if (columnValue == null) {
            throw new StoreException("索引 " + index.name() + " 不接受 NULL 值(K79:NULL 行不入索引)");
        }
        Column column = indexColumn(table, index);
        byte[] head = prefix(table.id(), index.indexId());
        byte[] encodedValue = encodeValue(column.type(), columnValue);
        if (index.unique()) {
            return concat(head, encodedValue);
        }
        Column pk = table.primaryKey();
        if (pkValue == null) {
            throw new StoreException("索引项缺少主键值(表 " + table.name() + ")");
        }
        return concat(head, encodedValue, KeyCodec.encodePrimaryKey(pk.type(), pkValue));
    }

    /** 唯一索引项的 value:主键的保序编码(回表与冲突报错都要用,§5.2)。 */
    public static byte[] entryValue(TableMeta table, IndexMeta index, Object pkValue) {
        Column pk = table.primaryKey();
        if (pkValue == null) {
            throw new StoreException("索引项缺少主键值(表 " + table.name() + ")");
        }
        return KeyCodec.encodePrimaryKey(pk.type(), pkValue);
    }

    /** 索引项墓碑沿用行墓碑标记(K83 / §5.2)。 */
    public static byte[] tombstone(long tableId) {
        return RowCodec.encodeTombstone(tableId);
    }

    // ------------------------------------------------------------ 范围边界(用户键级)

    /** 索引整段扫描起点(含)。 */
    public static byte[] indexStart(long tableId, int indexId) {
        return prefix(tableId, indexId);
    }

    /** 索引整段扫描终点(不含):下一个索引 ID 的前缀。 */
    public static byte[] indexEnd(long tableId, int indexId) {
        return prefix(tableId, indexId + 1);
    }

    /** 某个索引列值的等值扫描起点(含)。 */
    public static byte[] equalityStart(TableMeta table, IndexMeta index, Object value) {
        Column column = indexColumn(table, index);
        return concat(prefix(table.id(), index.indexId()), encodeValue(column.type(), value));
    }

    /**
     * 某个索引列值的等值扫描终点(不含)。
     *
     * <p>用 9 个 0xFF 作为"晚于该列值的全部索引项"的后缀:它比任何编码段都大,
     * 因此同样适用于"唯一索引(键到此结束)"与"二级索引(后面还跟主键编码)"。</p>
     */
    public static byte[] equalityEnd(TableMeta table, IndexMeta index, Object value) {
        return concat(equalityStart(table, index, value), KeyCodec.afterAllVersions());
    }

    /**
     * 索引列范围下界(用户键级)。
     *
     * @param inclusive true 表示 {@code col >= value};false 表示 {@code col > value}
     */
    public static byte[] lowerBound(TableMeta table, IndexMeta index, Object value, boolean inclusive) {
        byte[] start = equalityStart(table, index, value);
        return inclusive ? start : concat(start, KeyCodec.afterAllVersions());
    }

    /**
     * 索引列范围上界(用户键级)。
     *
     * @param inclusive true 表示 {@code col <= value};false 表示 {@code col < value}
     */
    public static byte[] upperBound(TableMeta table, IndexMeta index, Object value, boolean inclusive) {
        byte[] end = equalityStart(table, index, value);
        return inclusive ? concat(end, KeyCodec.afterAllVersions()) : end;
    }

    // ------------------------------------------------------------ 编码 / 解码

    /** 索引列值的保序编码(K77)。 */
    public static byte[] encodeValue(DataType type, Object value) {
        if (value == null) {
            throw new StoreException("索引列值不能为 NULL");
        }
        if (type == DataType.VARCHAR) {
            byte[] raw = ((String) value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length + 2);
            for (byte b : raw) {
                if (b == 0) {
                    out.write(0x00);
                    out.write(0xFF);
                } else {
                    out.write(b);
                }
            }
            out.write(0x00);
            out.write(0x00);
            return out.toByteArray();
        }
        if (type == DataType.BOOLEAN) {
            return new byte[]{(byte) (Boolean.TRUE.equals(value) ? 1 : 0)};
        }
        if (type == DataType.INT || type == DataType.BIGINT || type == DataType.DATE) {
            return KeyCodec.encodePrimaryKey(type, value);
        }
        throw new StoreException("列类型不支持索引编码: " + type.sqlName()
                + "(只允许 INT / BIGINT / VARCHAR / DATE / BOOLEAN)");
    }

    /** 解码结果:列值 + 占用的字节数。 */
    public record DecodedValue(Object value, int consumed) {
    }

    /** 从 buf[pos] 开始解码一个索引列值。 */
    public static DecodedValue decodeValue(DataType type, byte[] buf, int pos) {
        switch (type) {
            case INT: {
                require(buf, pos, 4);
                int raw = ((buf[pos] & 0xFF) << 24) | ((buf[pos + 1] & 0xFF) << 16)
                        | ((buf[pos + 2] & 0xFF) << 8) | (buf[pos + 3] & 0xFF);
                return new DecodedValue(raw ^ 0x80000000, 4);
            }
            case BIGINT: {
                require(buf, pos, 8);
                long raw = 0;
                for (int i = 0; i < 8; i++) {
                    raw = (raw << 8) | (buf[pos + i] & 0xFFL);
                }
                return new DecodedValue(raw ^ Long.MIN_VALUE, 8);
            }
            case DATE: {
                require(buf, pos, 4);
                int days = ((buf[pos] & 0xFF) << 24) | ((buf[pos + 1] & 0xFF) << 16)
                        | ((buf[pos + 2] & 0xFF) << 8) | (buf[pos + 3] & 0xFF);
                return new DecodedValue(LocalDate.ofEpochDay(days), 4);
            }
            case BOOLEAN: {
                require(buf, pos, 1);
                return new DecodedValue(buf[pos] != 0, 1);
            }
            case VARCHAR: {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int i = pos;
                while (i < buf.length) {
                    int b = buf[i] & 0xFF;
                    if (b != 0) {
                        out.write(b);
                        i++;
                        continue;
                    }
                    if (i + 1 >= buf.length) {
                        throw new StoreException("索引列值被截断(缺少终止符)");
                    }
                    int next = buf[i + 1] & 0xFF;
                    if (next == 0xFF) {
                        out.write(0);
                        i += 2;
                        continue;
                    }
                    if (next == 0x00) {
                        i += 2;
                        return new DecodedValue(
                                new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8),
                                i - pos);
                    }
                    throw new StoreException("索引列值含非法转义字节: 0x00 0x" + Integer.toHexString(next));
                }
                throw new StoreException("索引列值被截断(缺少终止符)");
            }
            default:
                throw new StoreException("列类型不支持索引解码: " + type.sqlName());
        }
    }

    /** 解码后的一条索引项。 */
    public record DecodedEntry(Object columnValue, Object primaryKey, byte[] encodedPrimaryKey,
                               boolean tombstone) {
    }

    /**
     * 解码一条索引项(§5.5:扫索引项时能从 key 里解出列值 + 主键)。
     *
     * @param key   用户键(不含版本段)
     * @param value 索引项 value(二级索引为空;唯一索引为主键编码;墓碑为行墓碑标记)
     */
    public static DecodedEntry decodeEntry(TableMeta table, IndexMeta index,
                                           byte[] key, byte[] value) {
        if (key == null || key.length < 8) {
            throw new StoreException("索引项 key 长度非法");
        }
        if (KeyCodec.tableId(key) != table.id() || KeyCodec.indexId(key) != index.indexId()) {
            throw new StoreException("索引项 key 与索引元数据不匹配(索引 " + index.name() + ")");
        }
        Column column = indexColumn(table, index);
        DecodedValue decoded = decodeValue(column.type(), key, 8);
        int pos = 8 + decoded.consumed();
        Column pk = table.primaryKey();

        if (index.unique()) {
            byte[] stored = value == null ? new byte[0] : value;
            if (isTombstone(table, stored)) {
                return new DecodedEntry(decoded.value(), null, null, true);
            }
            if (stored.length == 0) {
                throw new StoreException("唯一索引 " + index.name() + " 的索引项缺少主键值");
            }
            return new DecodedEntry(decoded.value(),
                    KeyCodec.decodePrimaryKeyValue(pk.type(), stored), stored, false);
        }

        if (value != null && isTombstone(table, value)) {
            return new DecodedEntry(decoded.value(), null, null, true);
        }
        if (pos >= key.length) {
            throw new StoreException("二级索引项缺少主键编码(索引 " + index.name() + ")");
        }
        byte[] encodedPk = Arrays.copyOfRange(key, pos, key.length);
        return new DecodedEntry(decoded.value(),
                KeyCodec.decodePrimaryKeyValue(pk.type(), encodedPk), encodedPk, false);
    }

    /** 判断 value 是否为本表的行墓碑(与 RowCodec 的标记一致,K83)。 */
    public static boolean isTombstone(TableMeta table, byte[] value) {
        return value != null && value.length == 5 && value[0] == RowCodec.FORMAT_TOMBSTONE
                && readLe4(value, 1) == table.id();
    }

    // ------------------------------------------------------------ 工具

    /** 十六进制摘要(EXPLAIN / 日志用);过长时截断并标注。 */
    public static String hex(byte[] bytes, int maxBytes) {
        if (bytes == null) {
            return "null";
        }
        int n = Math.min(bytes.length, Math.max(0, maxBytes));
        StringBuilder sb = new StringBuilder(n * 2 + 8);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02X", bytes[i] & 0xFF));
        }
        if (n < bytes.length) {
            sb.append("…(").append(bytes.length).append("B)");
        }
        return sb.toString();
    }

    public static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] part : parts) {
            len += part.length;
        }
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, pos, part.length);
            pos += part.length;
        }
        return out;
    }

    private static long readLe4(byte[] buf, int pos) {
        return (buf[pos] & 0xFFL)
                | ((buf[pos + 1] & 0xFFL) << 8)
                | ((buf[pos + 2] & 0xFFL) << 16)
                | ((buf[pos + 3] & 0xFFL) << 24);
    }

    private static void require(byte[] data, int pos, int len) {
        if (len < 0 || pos + len > data.length) {
            throw new StoreException("索引编码被截断");
        }
    }
}
