package com.wangyu.tangdb.storage;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 行框架协议(SPEC-P6.1 FR-701 / FR-702 / K145 / K152)的 FE 侧编解码。
 *
 * <pre>
 * column_slots: [u16 槽数] 然后每列 [u16 列序号][u8 kind][u8 width]
 *   kind 0 FIXED(BOOLEAN 等本身保序) / 1 VARLEN(JSON) / 5 VARLEN_SIGNED(VARCHAR) /
 *        2 INT32 / 3 INT64 / 4 FLOAT64(数值列比较时做保序变换) /
 *        6 VECTOR_FIXED(SPEC-P7:定长 4n 字节,宽度放不进 u8,见下面的宽槽段)
 *   宽槽段(仅当存在 VECTOR_FIXED 时追加在槽表末尾):
 *        [u16 宽槽数] 然后每项 [u16 列序号][u32 宽度]
 * row_frame:    [u8 nullmap 字节数][u16 列数][u16 NULL 位图前的前缀字节数]
 * 投影帧:       [nullmap][len u32][载荷] × 选中列数
 * columnar_block: [u32 行数][u32 偏移×(行数+1)][帧…],每帧 = [u32 keyLen][key][投影帧]
 * </pre>
 *
 * <p>BE 只按这里的字节偏移切片,不知道列名/类型;磁盘格式保持不变。</p>
 */
public final class ProjectedCodec {

    public static final byte SLOT_FIXED = 0;
    public static final byte SLOT_VARLEN = 1;
    public static final byte SLOT_INT32 = 2;
    public static final byte SLOT_INT64 = 3;
    public static final byte SLOT_FLOAT64 = 4;
    /** 变长、按有符号字节序比较(与 FE 的 ValueOps.compare 一致)。 */
    public static final byte SLOT_VARLEN_SIGNED = 5;
    /**
     * P7:定长向量槽(SPEC-P7 §5.1 / FR-732 / K161)。宽度 = 4×dim,超过 u8 表示范围,
     * 因此主槽表里 width 恒 0、真实宽度在末尾的宽槽段里。BE 只按偏移切片。
     */
    public static final byte SLOT_VECTOR_FIXED = 6;

    /** 行值里 NULL 位图之前的前缀:版本 1 + 表 ID 4 + 列数 2。 */
    public static final int ROW_PREFIX_BYTES = 7;

    private ProjectedCodec() {
    }

    /** 一张表的投影方案。 */
    public record Plan(int columnCount, int[] selectedOrdinals, byte[] columnSlots,
                       byte[] rowFrame, int mask, int nullmapBytes) {

        public int selectedCount() {
            return selectedOrdinals.length;
        }
    }

    /**
     * 根据列裁剪掩码生成投影方案。
     *
     * @param needed 与列数等长;true = 需要该列。始终保留主键列(ordinal 0)。
     * @return 表列数超过 32(proto 的 uint32 位图放不下)时返回 null,调用方走全行
     */
    public static Plan plan(TableMeta table, boolean[] needed) {
        int n = table.columnCount();
        if (n <= 0 || n > 32) {
            return null;
        }
        List<Integer> selected = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (i == 0 || needed == null || i >= needed.length || needed[i]) {
                selected.add(i);
            }
        }
        if (selected.isEmpty()) {
            selected.add(0);
        }
        int mask = 0;
        for (int ordinal : selected) {
            mask |= 1 << ordinal;
        }
        byte[] slots = encodeSlots(table);
        byte[] frame = new byte[5];
        frame[0] = (byte) ((n + 7) / 8);
        putLe2(frame, 1, n);
        putLe2(frame, 3, ROW_PREFIX_BYTES);
        int[] ordinals = selected.stream().mapToInt(Integer::intValue).toArray();
        return new Plan(n, ordinals, slots, frame, mask, (ordinals.length + 7) / 8);
    }

    /** 生成列槽表(BE 用它跳过它不关心的列)。 */
    public static byte[] encodeSlots(TableMeta table) {
        int n = table.columnCount();
        int wide = 0;
        for (int i = 0; i < n; i++) {
            if (slotKind(table.columns().get(i)) == SLOT_VECTOR_FIXED) {
                wide++;
            }
        }
        byte[] out = new byte[2 + n * 4 + (wide == 0 ? 0 : 2 + wide * 6)];
        putLe2(out, 0, n);
        int widePos = 2 + n * 4;
        if (wide > 0) {
            putLe2(out, widePos, wide);
            widePos += 2;
        }
        for (int i = 0; i < n; i++) {
            Column column = table.columns().get(i);
            int base = 2 + i * 4;
            putLe2(out, base, i);
            byte kind = slotKind(column);
            out[base + 2] = kind;
            if (kind == SLOT_VECTOR_FIXED) {
                out[base + 3] = 0; // 真实宽度见宽槽段
                putLe2(out, widePos, i);
                putLe4(out, widePos + 2, slotWidth(column));
                widePos += 6;
            } else {
                out[base + 3] = (byte) slotWidth(column);
            }
        }
        return out;
    }

    public static byte slotKind(DataType type) {
        return slotKind(new Column("c", type, 1, true, false, 0));
    }

    /** 列级槽种类:VECTOR 需要维度(宽度 4n),所以必须看列而不只看类型。 */
    public static byte slotKind(Column column) {
        DataType type = column.type();
        if (type == DataType.VECTOR) {
            return SLOT_VECTOR_FIXED;
        }
        return switch (type) {
            case INT, DATE -> SLOT_INT32;
            case BIGINT -> SLOT_INT64;
            case DOUBLE -> SLOT_FLOAT64;
            case VARCHAR -> SLOT_VARLEN_SIGNED;
            case JSON -> SLOT_VARLEN;
            case BOOLEAN -> SLOT_FIXED;
            case VECTOR -> SLOT_VECTOR_FIXED;
        };
    }

    public static int slotWidth(DataType type) {
        return slotWidth(new Column("c", type, 1, true, false, 0));
    }

    public static int slotWidth(Column column) {
        return switch (column.type()) {
            case INT, DATE -> 4;
            case BIGINT, DOUBLE -> 8;
            case VARCHAR, JSON -> 4; // 4 字节长度前缀
            case BOOLEAN -> 1;
            case VECTOR -> 4 * column.vectorDim();
        };
    }

    /**
     * 把投影帧还原成与表定义等宽的 Object[](未选中的列为 null)。
     *
     * <p>每次调用新建行数组,适合一次性解码;连续解码同一 Plan 的多行请用
     * {@link Decoder}(复用缓冲,FR-716)。</p>
     */
    public static Object[] decode(TableMeta table, Plan plan, byte[] frame) {
        return new Decoder(table, plan).decodeInto(frame, new Object[table.columnCount()]);
    }

    /**
     * 可复用缓冲的行解码器(SPEC-P6.1 FR-716)。
     *
     * <ul>
     *   <li>行数组可由调用方提供({@link #decodeInto}),解码本身不再分配 {@code new Object[列数]};</li>
     *   <li>数值/日期列走定长槽位缓存,重复值直接复用装箱对象,消除逐行装箱;</li>
     *   <li>短 VARCHAR 走内容校验的小缓存(命中时零分配,未命中才 new String);</li>
     *   <li>解码结果与 {@link #decode} 逐格一致,被截断/多字节的帧仍抛同样的异常。</li>
     * </ul>
     *
     * <p>非线程安全:一个 Decoder 实例只能被一个线程使用。</p>
     */
    public static final class Decoder {

        /** 定长列装箱缓存槽数(直接映射,2 的幂)。 */
        private static final int BOX_CACHE = 1024;
        /** 短字符串缓存槽数。 */
        private static final int STRING_CACHE = 256;
        /** 只缓存不超过该字节数的 VARCHAR。 */
        private static final int STRING_CACHE_MAX_BYTES = 32;

        private final TableMeta table;
        private final Plan plan;
        private final Object[] values;

        private final Integer[] intCache = new Integer[BOX_CACHE];
        private final Long[] longCache = new Long[BOX_CACHE];
        private final Double[] doubleCache = new Double[BOX_CACHE];
        private final LocalDate[] dateCache = new LocalDate[BOX_CACHE];
        private final String[] stringText = new String[STRING_CACHE];
        private final byte[][] stringRaw = new byte[STRING_CACHE][];

        public Decoder(TableMeta table, Plan plan) {
            if (table == null || plan == null) {
                throw new StoreException("解码器缺少表或投影方案");
            }
            this.table = table;
            this.plan = plan;
            this.values = new Object[table.columnCount()];
        }

        /** 复用的行缓冲(上一次 {@link #decode} 的结果)。 */
        public Object[] reusableRow() {
            return values;
        }

        /** 解码到复用的行缓冲;返回值与 {@link #reusableRow()} 同一实例。 */
        public Object[] decode(byte[] frame) {
            return decodeInto(frame, values);
        }

        /**
         * 解码到调用方提供的行数组。
         *
         * @param target 宽度必须不小于表列数;未选中的列为 null
         */
        public Object[] decodeInto(byte[] frame, Object[] target) {
            if (frame == null) {
                throw new StoreException("投影帧为空");
            }
            if (target == null || target.length < values.length) {
                throw new StoreException("解码目标行缓冲太窄(需要 " + values.length + " 列)");
            }
            int nullmapLen = plan.nullmapBytes();
            if (frame.length < nullmapLen) {
                throw new StoreException("投影帧被截断(nullmap)");
            }
            int pos = nullmapLen;
            int[] ordinals = plan.selectedOrdinals();
            for (int i = 0; i < ordinals.length; i++) {
                int ordinal = ordinals[i];
                if ((frame[i / 8] & (1 << (i % 8))) != 0) {
                    target[ordinal] = null;
                    continue;
                }
                if (pos + 4 > frame.length) {
                    throw new StoreException("投影帧被截断(长度前缀)");
                }
                int len = readLe4(frame, pos);
                pos += 4;
                if (len < 0 || pos + len > frame.length) {
                    throw new StoreException("投影帧被截断(载荷):列序号 " + ordinal
                            + ",长度 " + len + ",位置 " + pos + ",帧长 " + frame.length);
                }
                Column column = table.columns().get(ordinal);
                target[ordinal] = decodeColumn(column, frame, pos, len);
                pos += len;
            }
            if (pos != frame.length) {
                throw new StoreException("投影帧尾部有多余字节");
            }
            return target;
        }

        private Object decodeColumn(Column column, byte[] data, int pos, int len) {
            DataType type = column.type();
            switch (type) {
                case INT -> {
                    require(data, pos, 4, len);
                    return boxed(readLe4Int(data, pos));
                }
                case BIGINT -> {
                    require(data, pos, 8, len);
                    return boxed(readLe8(data, pos));
                }
                case DOUBLE -> {
                    require(data, pos, 8, len);
                    return boxed(Double.longBitsToDouble(readLe8(data, pos)));
                }
                case BOOLEAN -> {
                    require(data, pos, 1, len);
                    return data[pos] != 0 ? Boolean.TRUE : Boolean.FALSE;
                }
                case DATE -> {
                    require(data, pos, 4, len);
                    return boxedDate(readLe4Int(data, pos));
                }
                case VARCHAR -> {
                    return cachedString(data, pos, len);
                }
                case JSON -> {
                    byte[] raw = new byte[len];
                    System.arraycopy(data, pos, raw, 0, len);
                    return raw;
                }
                case VECTOR -> {
                    int width = 4 * column.vectorDim();
                    if (len != width) {
                        throw new StoreException("投影帧里的向量宽度非法(期望 " + width
                                + ",实际 " + len + ")");
                    }
                    return RowCodec.readFloats(data, pos, width / 4);
                }
                default -> throw new StoreException("不支持的列类型: " + type);
            }
        }

        private Integer boxed(int v) {
            int slot = (v * 0x9E3779B1) >>> 22 & (BOX_CACHE - 1);
            Integer cached = intCache[slot];
            if (cached != null && cached == v) {
                return cached;
            }
            Integer boxed = v;
            intCache[slot] = boxed;
            return boxed;
        }

        private Long boxed(long v) {
            int slot = (int) ((v ^ (v >>> 32)) * 0x9E3779B97F4A7C15L >>> 22) & (BOX_CACHE - 1);
            Long cached = longCache[slot];
            if (cached != null && cached == v) {
                return cached;
            }
            Long boxed = v;
            longCache[slot] = boxed;
            return boxed;
        }

        private Double boxed(double v) {
            int slot = (int) ((Double.doubleToLongBits(v) * 0x9E3779B97F4A7C15L) >>> 22)
                    & (BOX_CACHE - 1);
            Double cached = doubleCache[slot];
            if (cached != null && Double.compare(cached, v) == 0) {
                return cached;
            }
            Double boxed = v;
            doubleCache[slot] = boxed;
            return boxed;
        }

        private LocalDate boxedDate(int epochDay) {
            int slot = (epochDay * 0x9E3779B1) >>> 22 & (BOX_CACHE - 1);
            LocalDate cached = dateCache[slot];
            if (cached != null && cached.toEpochDay() == epochDay) {
                return cached;
            }
            LocalDate date = LocalDate.ofEpochDay(epochDay);
            dateCache[slot] = date;
            return date;
        }

        private String cachedString(byte[] data, int pos, int len) {
            if (len > STRING_CACHE_MAX_BYTES) {
                return new String(data, pos, len, StandardCharsets.UTF_8);
            }
            int hash = 17;
            for (int i = pos; i < pos + len; i++) {
                hash = hash * 31 + data[i];
            }
            int slot = (hash & 0x7FFFFFFF) % STRING_CACHE;
            byte[] raw = stringRaw[slot];
            if (raw != null && raw.length == len
                    && java.util.Arrays.equals(raw, 0, len, data, pos, pos + len)) {
                return stringText[slot];
            }
            String text = new String(data, pos, len, StandardCharsets.UTF_8);
            stringRaw[slot] = java.util.Arrays.copyOfRange(data, pos, pos + len);
            stringText[slot] = text;
            return text;
        }
    }

    /** 解码列式块:返回 (用户键, 投影帧) 列表。 */
    public static List<KeyValue> decodeBlock(byte[] block) {
        if (block == null || block.length < 4) {
            return List.of();
        }
        int rows = readLe4(block, 0);
        if (rows < 0 || block.length < 4 + 4 * (rows + 1)) {
            throw new StoreException("列式块头被截断");
        }
        int base = 4 + 4 * (rows + 1);
        List<KeyValue> out = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            int start = readLe4(block, 4 + 4 * i);
            int end = readLe4(block, 4 + 4 * (i + 1));
            if (start < 0 || end < start || base + end > block.length) {
                throw new StoreException("列式块偏移非法");
            }
            int pos = base + start;
            int limit = base + end;
            if (pos + 4 > limit) {
                throw new StoreException("列式块行被截断(key 长度)");
            }
            int keyLen = readLe4(block, pos);
            pos += 4;
            if (keyLen < 0 || pos + keyLen > limit) {
                throw new StoreException("列式块行被截断(key)");
            }
            byte[] key = new byte[keyLen];
            System.arraycopy(block, pos, key, 0, keyLen);
            pos += keyLen;
            byte[] frame = new byte[limit - pos];
            System.arraycopy(block, pos, frame, 0, frame.length);
            out.add(new KeyValue(key, frame));
        }
        return out;
    }

    // ---------------------------------------------------------------- 字面量(比较态)

    /**
     * 按"保序编码"把字面量编成字节,与 BE 侧 {@code CompareBytes} 的输出同一套字节序。
     *
     * @return 类型不支持(JSON)或值不可比较时返回 null(该谓词不下推)
     */
    public static byte[] encodeLiteral(DataType type, Object value) {
        if (value == null) {
            return null;
        }
        try {
            switch (type) {
                case INT: {
                    int v = ((Number) value).intValue();
                    byte[] out = new byte[4];
                    putBe4(out, 0, v ^ 0x80000000);
                    return out;
                }
                case BIGINT: {
                    long v = ((Number) value).longValue();
                    byte[] out = new byte[8];
                    putBe8(out, 0, v ^ Long.MIN_VALUE);
                    return out;
                }
                case DATE: {
                    long epochDay = value instanceof LocalDate date
                            ? date.toEpochDay() : ((Number) value).longValue();
                    int v = (int) epochDay;
                    byte[] out = new byte[4];
                    putBe4(out, 0, v ^ 0x80000000);
                    return out;
                }
                case DOUBLE: {
                    long bits = Double.doubleToLongBits(((Number) value).doubleValue());
                    bits = bits < 0 ? ~bits : bits ^ Long.MIN_VALUE;
                    byte[] out = new byte[8];
                    putBe8(out, 0, bits);
                    return out;
                }
                case BOOLEAN:
                    return new byte[]{(byte) ((Boolean) value ? 1 : 0)};
                case VARCHAR: {
                    byte[] raw = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                    // 与 BE 的 SLOT_VARLEN_SIGNED 对齐:翻转最高位后,无符号字节比较
                    // 等价于 Java Arrays.compare 的有符号字节比较。
                    for (int i = 0; i < raw.length; i++) {
                        raw[i] ^= (byte) 0x80;
                    }
                    return raw;
                }
                default:
                    return null; // JSON 不支持下推(FR-704 / 边界 #114)
            }
        } catch (ClassCastException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 基础读写

    private static void require(byte[] data, int pos, int width, int len) {
        if (len != width || pos + len > data.length) {
            throw new StoreException("投影帧列长度非法(期望 " + width + ",实际 " + len + ")");
        }
    }

    private static void putLe2(byte[] buf, int pos, int v) {
        buf[pos] = (byte) v;
        buf[pos + 1] = (byte) (v >>> 8);
    }

    private static void putLe4(byte[] buf, int pos, int v) {
        buf[pos] = (byte) v;
        buf[pos + 1] = (byte) (v >>> 8);
        buf[pos + 2] = (byte) (v >>> 16);
        buf[pos + 3] = (byte) (v >>> 24);
    }

    private static void putBe4(byte[] buf, int pos, int v) {
        buf[pos] = (byte) (v >>> 24);
        buf[pos + 1] = (byte) (v >>> 16);
        buf[pos + 2] = (byte) (v >>> 8);
        buf[pos + 3] = (byte) v;
    }

    private static void putBe8(byte[] buf, int pos, long v) {
        for (int i = 0; i < 8; i++) {
            buf[pos + i] = (byte) (v >>> (56 - i * 8));
        }
    }

    public static int readLe4(byte[] buf, int pos) {
        return (buf[pos] & 0xff)
                | ((buf[pos + 1] & 0xff) << 8)
                | ((buf[pos + 2] & 0xff) << 16)
                | ((buf[pos + 3] & 0xff) << 24);
    }

    private static int readLe4Int(byte[] buf, int pos) {
        return readLe4(buf, pos);
    }

    private static long readLe8(byte[] buf, int pos) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (buf[pos + i] & 0xffL);
        }
        return v;
    }
}
