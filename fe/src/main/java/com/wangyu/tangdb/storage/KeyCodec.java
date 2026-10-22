package com.wangyu.tangdb.storage;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * key 编码(规约 §5.1 / §5.2)。
 *
 * <pre>
 * 表ID 4B(BE) | 索引ID 4B(BE) | 索引列值(主键保序编码) | 主键值(主键索引为空) | 版本号 8B(BE)
 * </pre>
 *
 * <p>本期版本号固定写 0(K15),不参与语义。主键索引的索引 ID 固定为 0。</p>
 */
public final class KeyCodec {

    public static final int INDEX_ID_PRIMARY = 0;
    public static final int VERSION_BYTES = 8;

    private KeyCodec() {
    }

    /** 表前缀:表 ID + 索引 ID,共 8 字节。 */
    public static byte[] prefix(long tableId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8);
        writeBe4(out, tableId);
        writeBe4(out, INDEX_ID_PRIMARY);
        return out.toByteArray();
    }

    /** 用户表的完整行 key:表前缀 + 主键保序编码 + 版本号(固定 0)。 */
    public static byte[] rowKey(long tableId, DataType pkType, Object pkValue) {
        byte[] pk = encodePrimaryKey(pkType, pkValue);
        byte[] out = new byte[8 + pk.length + VERSION_BYTES];
        System.arraycopy(prefix(tableId), 0, out, 0, 8);
        System.arraycopy(pk, 0, out, 8, pk.length);
        return out;
    }

    /** 系统表的行 key:表前缀 + 由 catalog 自行编码的主键 + 版本号(固定 0)。 */
    public static byte[] systemRowKey(long tableId, byte[] encodedPrimaryKey) {
        byte[] out = new byte[8 + encodedPrimaryKey.length + VERSION_BYTES];
        System.arraycopy(prefix(tableId), 0, out, 0, 8);
        System.arraycopy(encodedPrimaryKey, 0, out, 8, encodedPrimaryKey.length);
        return out;
    }

    /** 整张表的扫描起点(含)。 */
    public static byte[] tableRangeStart(long tableId) {
        return prefix(tableId);
    }

    /** 整张表的扫描终点(不含):下一张表的表前缀。 */
    public static byte[] tableRangeEnd(long tableId) {
        return prefix(tableId + 1);
    }

    /**
     * 主键范围下界。
     *
     * @param inclusive true 表示 {@code pk >= value};false 表示 {@code pk > value}
     */
    public static byte[] lowerBound(long tableId, DataType pkType, Object pkValue, boolean inclusive) {
        byte[] pk = encodePrimaryKey(pkType, pkValue);
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 + pk.length);
        out.writeBytes(prefix(tableId));
        out.writeBytes(pk);
        if (!inclusive) {
            // 同主键的完整 key 还带 8 字节版本号;多追加 1 字节即严格排在其后,
            // 且仍小于任何更大的主键(定长编码或带 0x00 终止符,不存在前缀关系)。
            out.writeBytes(new byte[VERSION_BYTES + 1]);
        }
        return out.toByteArray();
    }

    /**
     * 主键范围上界。
     *
     * @param inclusive true 表示 {@code pk <= value};false 表示 {@code pk < value}
     */
    public static byte[] upperBound(long tableId, DataType pkType, Object pkValue, boolean inclusive) {
        byte[] pk = encodePrimaryKey(pkType, pkValue);
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 + pk.length);
        out.writeBytes(prefix(tableId));
        out.writeBytes(pk);
        if (inclusive) {
            out.writeBytes(new byte[VERSION_BYTES + 1]);
        }
        return out.toByteArray();
    }

    /** 主键列值的保序编码:字节序 = 数值/字典序(规约 §5.2)。 */
    public static byte[] encodePrimaryKey(DataType type, Object value) {
        if (value == null) {
            throw new StoreException("主键值不能为 NULL");
        }
        switch (type) {
            case INT: {
                int v = ((Number) value).intValue();
                int flipped = v ^ 0x80000000;
                return new byte[]{
                        (byte) (flipped >>> 24), (byte) (flipped >>> 16),
                        (byte) (flipped >>> 8), (byte) flipped};
            }
            case BIGINT: {
                long v = ((Number) value).longValue();
                long flipped = v ^ Long.MIN_VALUE;
                byte[] out = new byte[8];
                for (int i = 0; i < 8; i++) {
                    out[i] = (byte) (flipped >>> (56 - i * 8));
                }
                return out;
            }
            case DATE: {
                int days = (int) ((LocalDate) value).toEpochDay();
                return new byte[]{
                        (byte) (days >>> 24), (byte) (days >>> 16),
                        (byte) (days >>> 8), (byte) days};
            }
            case VARCHAR: {
                byte[] raw = ((String) value).getBytes(StandardCharsets.UTF_8);
                byte[] out = new byte[raw.length + 1];
                System.arraycopy(raw, 0, out, 0, raw.length);
                out[raw.length] = 0;
                return out;
            }
            default:
                throw new StoreException("主键类型不支持: " + type.sqlName()
                        + "(只允许 INT / BIGINT / VARCHAR / DATE)");
        }
    }

    /** 大端 4 字节。 */
    public static byte[] be4(long v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    /** 大端 2 字节。 */
    public static byte[] be2(int v) {
        return new byte[]{(byte) (v >>> 8), (byte) v};
    }

    public static void writeBe4(ByteArrayOutputStream out, long v) {
        out.writeBytes(be4(v));
    }
}
