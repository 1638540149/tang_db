package com.wangyu.tangdb.storage;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * key 编码(SPEC-P3 §5.1 / §5.2)。
 *
 * <pre>
 * 表ID 4B(BE) | 索引ID 4B(BE) | 索引列值(主键保序编码) | 主键值(主键索引为空)
 * └──────────────── FE 编码:用户键(不含版本段)────────────────┘
 *                                                        + 版本段 8B(BE 维护)
 * </pre>
 *
 * <p>P3 起 FE **不再拼版本段**:版本段由 BE 在写入时追加 {@code ^commit_ts},FE 只按
 * 用户键比较与拼接(FR-349 ~ FR-351)。范围边界也用用户键表达。</p>
 */
public final class KeyCodec {

    public static final int INDEX_ID_PRIMARY = 0;

    /** 版本段长度(BE 维护,FE 只在读取结果时剥掉它)。 */
    public static final int VERSION_BYTES = 8;

    /**
     * 9 个 0xFF:比任何"8 字节版本段"都大的后缀。
     *
     * <p>用来表达"包含某个主键的全部版本"(上界)或"严格排在该主键所有版本之后"
     * (排他下界)。它不假设版本段的具体内容,只依赖"版本段恰好 8 字节"这一结构约定;
     * 9 个字节保证即使版本段是 {@code 0xFF×8} 也仍然更大。</p>
     */
    private static final byte[] AFTER_ALL_VERSIONS = {
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

    private KeyCodec() {
    }

    /** 表前缀:表 ID + 索引 ID,共 8 字节。 */
    public static byte[] prefix(long tableId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8);
        writeBe4(out, tableId);
        writeBe4(out, INDEX_ID_PRIMARY);
        return out.toByteArray();
    }

    /** 用户表的完整用户键(不含版本段):表前缀 + 主键保序编码。 */
    public static byte[] rowKey(long tableId, DataType pkType, Object pkValue) {
        byte[] pk = encodePrimaryKey(pkType, pkValue);
        byte[] out = new byte[8 + pk.length];
        System.arraycopy(prefix(tableId), 0, out, 0, 8);
        System.arraycopy(pk, 0, out, 8, pk.length);
        return out;
    }

    /** 系统表的用户键(不含版本段):表前缀 + 由 catalog 自行编码的主键。 */
    public static byte[] systemRowKey(long tableId, byte[] encodedPrimaryKey) {
        byte[] out = new byte[8 + encodedPrimaryKey.length];
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
     * 主键范围下界(用户键级)。
     *
     * @param inclusive true 表示 {@code pk >= value};false 表示 {@code pk > value}
     */
    public static byte[] lowerBound(long tableId, DataType pkType, Object pkValue, boolean inclusive) {
        byte[] pk = encodePrimaryKey(pkType, pkValue);
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 + pk.length);
        out.writeBytes(prefix(tableId));
        out.writeBytes(pk);
        if (!inclusive) {
            out.writeBytes(AFTER_ALL_VERSIONS);
        }
        return out.toByteArray();
    }

    /**
     * 主键范围上界(用户键级)。
     *
     * @param inclusive true 表示 {@code pk <= value};false 表示 {@code pk < value}
     */
    public static byte[] upperBound(long tableId, DataType pkType, Object pkValue, boolean inclusive) {
        byte[] pk = encodePrimaryKey(pkType, pkValue);
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 + pk.length);
        out.writeBytes(prefix(tableId));
        out.writeBytes(pk);
        if (inclusive) {
            out.writeBytes(AFTER_ALL_VERSIONS);
        }
        return out.toByteArray();
    }

    /**
     * 从 BE 返回的完整 key 上剥掉尾部 8 字节版本段,得到用户键(FR §5.2)。
     *
     * <p>FE 在任何地方都不解释版本段的字节内容,只是把它去掉。</p>
     */
    public static byte[] userKey(byte[] fullKey) {
        if (fullKey == null || fullKey.length <= VERSION_BYTES) {
            throw new StoreException("BE 返回的 key 长度非法(至少应包含 8 字节版本段): "
                    + (fullKey == null ? "null" : fullKey.length));
        }
        byte[] out = new byte[fullKey.length - VERSION_BYTES];
        System.arraycopy(fullKey, 0, out, 0, out.length);
        return out;
    }

    /** 从用户键的前 4 字节读表 ID。 */
    public static long tableId(byte[] userKey) {
        if (userKey == null || userKey.length < 8) {
            throw new StoreException("用户键长度非法: " + (userKey == null ? "null" : userKey.length));
        }
        return readBe4(userKey, 0);
    }

    /**
     * 从用户键解码主键值(用于把冲突 key 翻译成"表 t 主键 x")。
     *
     * <p>只用于诊断与错误提示,不参与读写路径的语义。</p>
     */
    public static Object decodePrimaryKey(TableMeta table, byte[] userKey) {
        if (table == null || userKey == null || userKey.length < 8) {
            throw new StoreException("无法解码主键:表或 key 非法");
        }
        Column pk = table.primaryKey();
        if (pk == null) {
            throw new StoreException("表 " + table.name() + " 没有主键");
        }
        int pos = 8;
        switch (pk.type()) {
            case INT: {
                require(userKey, pos, 4);
                int raw = (int) readBe4(userKey, pos);
                return raw ^ 0x80000000;
            }
            case BIGINT: {
                require(userKey, pos, 8);
                long raw = 0;
                for (int i = 0; i < 8; i++) {
                    raw = (raw << 8) | (userKey[pos + i] & 0xFFL);
                }
                return raw ^ Long.MIN_VALUE;
            }
            case DATE: {
                require(userKey, pos, 4);
                return LocalDate.ofEpochDay(readBe4(userKey, pos));
            }
            case VARCHAR: {
                int end = pos;
                while (end < userKey.length && userKey[end] != 0) {
                    end++;
                }
                return new String(userKey, pos, end - pos, StandardCharsets.UTF_8);
            }
            default:
                throw new StoreException("主键类型不支持: " + pk.type().sqlName());
        }
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

    private static long readBe4(byte[] buf, int pos) {
        return ((buf[pos] & 0xFFL) << 24)
                | ((buf[pos + 1] & 0xFFL) << 16)
                | ((buf[pos + 2] & 0xFFL) << 8)
                | (buf[pos + 3] & 0xFFL);
    }

    private static void require(byte[] data, int pos, int len) {
        if (len < 0 || pos + len > data.length) {
            throw new StoreException("用户键被截断,无法解码主键");
        }
    }
}
