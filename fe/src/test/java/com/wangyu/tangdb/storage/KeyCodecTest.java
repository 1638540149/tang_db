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
    void rowKeyLayout() {
        byte[] key = KeyCodec.rowKey(16, DataType.BIGINT, 1L);
        byte[] prefix = KeyCodec.prefix(16);
        assertEquals(8 + 8 + 8, key.length);
        assertArrayEquals(new byte[]{0, 0, 0, 16, 0, 0, 0, 0}, prefix);
        assertArrayEquals(Arrays.copyOfRange(key, 0, 8), prefix);
        assertArrayEquals(new byte[8], Arrays.copyOfRange(key, key.length - 8, key.length));
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
    }

    @Test
    void tableRangesDoNotOverlap() {
        byte[] end16 = KeyCodec.tableRangeEnd(16);
        byte[] start17 = KeyCodec.tableRangeStart(17);
        assertArrayEquals(end16, start17);
    }
}
