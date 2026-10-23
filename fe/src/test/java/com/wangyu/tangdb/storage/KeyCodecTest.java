package com.wangyu.tangdb.storage;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** 主键保序编码与范围边界的测试(规约 §5.2)。 */
class KeyCodecTest {

    private static byte[] enc(DataType type, Object value) {
        return KeyCodec.encodePrimaryKey(type, value);
    }

    @Test
    void intOrdering() {
        int[] values = {Integer.MIN_VALUE, -65536, -129, -128, -1, 0, 1, 127, 128, 255, 256, Integer.MAX_VALUE};
        for (int i = 1; i < values.length; i++) {
            assertTrue(Arrays.compareUnsigned(enc(DataType.INT, values[i - 1]), enc(DataType.INT, values[i])) < 0,
                    values[i - 1] + " 应排在 " + values[i] + " 之前");
        }
        assertEquals(4, enc(DataType.INT, 0).length);
    }

    @Test
    void bigintOrdering() {
        long[] values = {Long.MIN_VALUE, -65536, -129, -128, -1, 0, 1, 127, 128, 255, 256,
                1L << 32, Long.MAX_VALUE};
        for (int i = 1; i < values.length; i++) {
            assertTrue(Arrays.compareUnsigned(enc(DataType.BIGINT, values[i - 1]), enc(DataType.BIGINT, values[i])) < 0,
                    values[i - 1] + " 应排在 " + values[i] + " 之前");
        }
        assertEquals(8, enc(DataType.BIGINT, 0).length);
    }

    @Test
    void varcharOrderingWithTerminator() {
        String[] values = {"", "a", "aa", "ab", "b", "一", "上海", "二", "北京"};
        for (int i = 1; i < values.length; i++) {
            assertTrue(Arrays.compareUnsigned(enc(DataType.VARCHAR, values[i - 1]), enc(DataType.VARCHAR, values[i])) < 0,
                    "'" + values[i - 1] + "' 应排在 '" + values[i] + "' 之前");
        }
        // 空串也要能编码,且以 0x00 终止
        byte[] empty = enc(DataType.VARCHAR, "");
        assertArrayEquals(new byte[]{0}, empty);
        byte[] a = enc(DataType.VARCHAR, "a");
        assertArrayEquals(new byte[]{'a', 0}, a);
    }

    @Test
    void dateOrdering() {
        assertTrue(Arrays.compareUnsigned(enc(DataType.DATE, LocalDate.of(1970, 1, 1)),
                enc(DataType.DATE, LocalDate.of(1970, 1, 2))) < 0);
        assertTrue(Arrays.compareUnsigned(enc(DataType.DATE, LocalDate.of(2023, 12, 31)),
                enc(DataType.DATE, LocalDate.of(2024, 1, 1))) < 0);
        assertEquals(4, enc(DataType.DATE, LocalDate.of(2024, 1, 1)).length);
    }

    @Test
    void doubleAndBooleanCannotBePrimaryKeys() {
        assertThrows(StoreException.class, () -> enc(DataType.DOUBLE, 1.0));
        assertThrows(StoreException.class, () -> enc(DataType.BOOLEAN, true));
    }

    @Test
    void rowKeyHasNoVersionSegment() {
        // P3:FE 只产出用户键(表 ID + 索引 ID + 主键),版本段由 BE 追加。
        byte[] key = KeyCodec.rowKey(16, DataType.BIGINT, 1L);
        byte[] prefix = KeyCodec.prefix(16);
        assertEquals(8 + 8, key.length);
        assertArrayEquals(new byte[]{0, 0, 0, 16, 0, 0, 0, 0}, prefix);
        assertArrayEquals(prefix, Arrays.copyOfRange(key, 0, 8));
        // 后 8 字节是主键编码本身(不是版本段)。
        assertArrayEquals(KeyCodec.encodePrimaryKey(DataType.BIGINT, 1L),
                Arrays.copyOfRange(key, 8, key.length));
    }

    @Test
    void userKeyStripsVersionSegment() {
        byte[] user = KeyCodec.rowKey(16, DataType.BIGINT, 7L);
        byte[] full = new byte[user.length + KeyCodec.VERSION_BYTES];
        System.arraycopy(user, 0, full, 0, user.length);
        Arrays.fill(full, user.length, full.length, (byte) 0xA5);
        assertArrayEquals(user, KeyCodec.userKey(full));
        assertEquals(16, KeyCodec.tableId(user));
    }

    @Test
    void rangeBoundsExcludeOrIncludeAllVersions() {
        // FE 不解释版本段内容,但边界必须对"任意 8 字节后缀"都成立。
        long tableId = 16;
        byte[] user = KeyCodec.rowKey(tableId, DataType.BIGINT, 5L);
        byte[][] suffixes = {
                new byte[8],
                {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                        (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
                {1, 2, 3, 4, 5, 6, 7, 8},
        };
        byte[] lowerExclusive = KeyCodec.lowerBound(tableId, DataType.BIGINT, 5L, false);
        byte[] upperInclusive = KeyCodec.upperBound(tableId, DataType.BIGINT, 5L, true);
        assertArrayEquals(KeyCodec.rowKey(tableId, DataType.BIGINT, 5L),
                KeyCodec.lowerBound(tableId, DataType.BIGINT, 5L, true));
        assertArrayEquals(KeyCodec.rowKey(tableId, DataType.BIGINT, 5L),
                KeyCodec.upperBound(tableId, DataType.BIGINT, 5L, false));

        for (byte[] suffix : suffixes) {
            byte[] full = new byte[user.length + suffix.length];
            System.arraycopy(user, 0, full, 0, user.length);
            System.arraycopy(suffix, 0, full, user.length, suffix.length);
            assertTrue(Arrays.compareUnsigned(full, lowerExclusive) < 0,
                    "排他下界必须大于该主键的任何版本");
            assertTrue(Arrays.compareUnsigned(full, upperInclusive) < 0,
                    "包含上界必须覆盖该主键的任何版本");
        }
    }

    @Test
    void lowerAndUpperBounds() {
        long tableId = 16;
        byte[] key5 = KeyCodec.rowKey(tableId, DataType.BIGINT, 5L);
        byte[] key6 = KeyCodec.rowKey(tableId, DataType.BIGINT, 6L);

        byte[] lowerInclusive = KeyCodec.lowerBound(tableId, DataType.BIGINT, 5L, true);
        byte[] lowerExclusive = KeyCodec.lowerBound(tableId, DataType.BIGINT, 5L, false);
        byte[] upperInclusive = KeyCodec.upperBound(tableId, DataType.BIGINT, 5L, true);
        byte[] upperExclusive = KeyCodec.upperBound(tableId, DataType.BIGINT, 5L, false);

        assertTrue(Arrays.compareUnsigned(key5, lowerInclusive) >= 0, ">= 5 应包含 5");
        assertTrue(Arrays.compareUnsigned(key5, lowerExclusive) < 0, "> 5 不应包含 5");
        assertTrue(Arrays.compareUnsigned(key5, upperExclusive) >= 0, "< 5 不应包含 5");
        assertTrue(Arrays.compareUnsigned(key5, upperInclusive) < 0, "<= 5 应包含 5");
        assertTrue(Arrays.compareUnsigned(key6, upperInclusive) > 0, "<= 5 不应包含 6");
        assertTrue(Arrays.compareUnsigned(key6, lowerExclusive) > 0, "> 5 应包含 6");
    }

    @Test
    void boundsForVarchar() {
        long tableId = 16;
        byte[] keyA = KeyCodec.rowKey(tableId, DataType.VARCHAR, "a");
        byte[] keyAB = KeyCodec.rowKey(tableId, DataType.VARCHAR, "ab");
        byte[] keyB = KeyCodec.rowKey(tableId, DataType.VARCHAR, "b");

        byte[] upper = KeyCodec.upperBound(tableId, DataType.VARCHAR, "a", true);
        assertTrue(Arrays.compareUnsigned(keyA, upper) < 0);
        assertTrue(Arrays.compareUnsigned(keyAB, upper) > 0, "<= 'a' 不应包含 'ab'");
        assertTrue(Arrays.compareUnsigned(keyB, upper) > 0);

        byte[] lower = KeyCodec.lowerBound(tableId, DataType.VARCHAR, "a", false);
        assertTrue(Arrays.compareUnsigned(keyA, lower) < 0);
        assertTrue(Arrays.compareUnsigned(keyAB, lower) > 0, "> 'a' 应包含 'ab'");
        // 完整 key(用户键 + 版本)也必须被排他下界排除在外。
        byte[] fullA = Arrays.copyOf(keyA, keyA.length + 8);
        Arrays.fill(fullA, keyA.length, fullA.length, (byte) 0xFF);
        assertTrue(Arrays.compareUnsigned(fullA, lower) < 0);
    }

    @Test
    void tableRangesDoNotOverlap() {
        byte[] end16 = KeyCodec.tableRangeEnd(16);
        byte[] start17 = KeyCodec.tableRangeStart(17);
        assertArrayEquals(end16, start17);
    }
}
