package com.wangyu.tangdb.index;

import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.DataType;
import com.wangyu.tangdb.storage.IndexCodec;
import com.wangyu.tangdb.storage.IndexMeta;
import com.wangyu.tangdb.storage.KeyCodec;
import com.wangyu.tangdb.storage.TableMeta;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** SPEC-P4 §5.1 ~ §5.3 / K76 / K77:索引 key 编码、边界包含性、0x00 转义。 */
class IndexCodecTest {

    private static final TableMeta TABLE = new TableMeta(64, "t", "default", TableMeta.STATE_ACTIVE,
            "", List.of(
            new Column("id", DataType.INT, 0, false, true, 0),
            new Column("code", DataType.VARCHAR, 32, true, false, 1),
            new Column("amount", DataType.BIGINT, 0, true, false, 2),
            new Column("day", DataType.DATE, 0, true, false, 3),
            new Column("flag", DataType.BOOLEAN, 0, true, false, 4)), false);

    private static final IndexMeta SECONDARY = new IndexMeta(64, 2, "idx_code", "code",
            false, IndexMeta.STATE_ACTIVE, "", "");
    private static final IndexMeta UNIQUE = new IndexMeta(64, 1, "uk_amount", "amount",
            true, IndexMeta.STATE_ACTIVE, "", "");

    private static String hex(byte[] bytes) {
        return IndexCodec.hex(bytes, Integer.MAX_VALUE);
    }

    @Test
    void secondaryKeyRoundTripContainsColumnValueAndPrimaryKey() {
        byte[] key = IndexCodec.entryKey(TABLE, SECONDARY, "abc", 7);
        IndexCodec.DecodedEntry decoded = IndexCodec.decodeEntry(TABLE, SECONDARY, key, new byte[0]);
        assertEquals("abc", decoded.columnValue());
        assertEquals(7, decoded.primaryKey());
        assertFalse(decoded.tombstone());
        assertEquals(64, KeyCodec.tableId(key));
        assertEquals(2, KeyCodec.indexId(key));
    }

    @Test
    void uniqueKeyDoesNotContainPrimaryKeyInKeyAndStoresItInValue() {
        byte[] a = IndexCodec.entryKey(TABLE, UNIQUE, 100L, 1);
        byte[] b = IndexCodec.entryKey(TABLE, UNIQUE, 100L, 2);
        assertArrayEquals(a, b, "唯一索引的用户键不含主键(K76)");
        byte[] value = IndexCodec.entryValue(TABLE, UNIQUE, 2);
        IndexCodec.DecodedEntry decoded = IndexCodec.decodeEntry(TABLE, UNIQUE, a, value);
        assertEquals(100L, decoded.columnValue());
        assertEquals(2, decoded.primaryKey());
    }

    @Test
    void equalityRangeHitsExactlyOneColumnValueAndDoesNotBleedIntoPrefixNeighbours() {
        byte[] start = IndexCodec.equalityStart(TABLE, SECONDARY, "abc");
        byte[] end = IndexCodec.equalityEnd(TABLE, SECONDARY, "abc");
        byte[] exact = IndexCodec.entryKey(TABLE, SECONDARY, "abc", 1);
        byte[] longer = IndexCodec.entryKey(TABLE, SECONDARY, "abcd", 1);
        byte[] shorter = IndexCodec.entryKey(TABLE, SECONDARY, "ab", 1);
        assertTrue(Arrays.compareUnsigned(start, exact) <= 0);
        assertTrue(Arrays.compareUnsigned(exact, end) < 0);
        assertTrue(Arrays.compareUnsigned(longer, end) >= 0, "'abcd' 不得落进 'abc' 的等值区间");
        assertTrue(Arrays.compareUnsigned(shorter, start) < 0);
    }

    @Test
    void rangeBoundariesAreInclusiveOrExclusivePerOperator() {
        byte[] value = IndexCodec.entryKey(TABLE, SECONDARY, "m", 1);
        assertTrue(Arrays.compareUnsigned(IndexCodec.lowerBound(TABLE, SECONDARY, "m", true), value) <= 0);
        assertTrue(Arrays.compareUnsigned(IndexCodec.lowerBound(TABLE, SECONDARY, "m", false), value) > 0);
        assertTrue(Arrays.compareUnsigned(value, IndexCodec.upperBound(TABLE, SECONDARY, "m", false)) >= 0);
        assertTrue(Arrays.compareUnsigned(value, IndexCodec.upperBound(TABLE, SECONDARY, "m", true)) < 0);
        // 上界（含）必须覆盖该列值的全部索引项。
        byte[] otherPk = IndexCodec.entryKey(TABLE, SECONDARY, "m", 999);
        assertTrue(Arrays.compareUnsigned(otherPk, IndexCodec.upperBound(TABLE, SECONDARY, "m", true)) < 0);
    }

    @Test
    void varcharWithNulByteRoundTripsAndKeepsOrder() {
        String withNul = "a\u0000b";
        byte[] encoded = IndexCodec.encodeValue(DataType.VARCHAR, withNul);
        IndexCodec.DecodedValue decoded = IndexCodec.decodeValue(DataType.VARCHAR, encoded, 0);
        assertEquals(withNul, decoded.value());
        assertEquals(encoded.length, decoded.consumed());

        // 顺序: "a" < "a\0" < "a\0b" < "ab"
        assertTrue(Arrays.compareUnsigned(IndexCodec.encodeValue(DataType.VARCHAR, "a"),
                IndexCodec.encodeValue(DataType.VARCHAR, "a\u0000")) < 0);
        assertTrue(Arrays.compareUnsigned(IndexCodec.encodeValue(DataType.VARCHAR, "a\u0000"),
                IndexCodec.encodeValue(DataType.VARCHAR, "a\u0000b")) < 0);
        assertTrue(Arrays.compareUnsigned(IndexCodec.encodeValue(DataType.VARCHAR, "a\u0000b"),
                IndexCodec.encodeValue(DataType.VARCHAR, "ab")) < 0);
    }

    @Test
    void fixedWidthTypesRoundTrip() {
        for (Object value : new Object[]{Integer.MIN_VALUE, 0, Integer.MAX_VALUE}) {
            byte[] encoded = IndexCodec.encodeValue(DataType.INT, value);
            assertEquals(value, IndexCodec.decodeValue(DataType.INT, encoded, 0).value());
        }
        for (Object value : new Object[]{Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE}) {
            byte[] encoded = IndexCodec.encodeValue(DataType.BIGINT, value);
            assertEquals(value, IndexCodec.decodeValue(DataType.BIGINT, encoded, 0).value());
        }
        LocalDate day = LocalDate.of(2026, 10, 26);
        byte[] encoded = IndexCodec.encodeValue(DataType.DATE, day);
        assertEquals(day, IndexCodec.decodeValue(DataType.DATE, encoded, 0).value());
        assertTrue(IndexCodec.decodeValue(DataType.BOOLEAN,
                IndexCodec.encodeValue(DataType.BOOLEAN, true), 0).value().equals(true));
    }

    @Test
    void indexOfDifferentIndexIdNeverOverlaps() {
        byte[] secondary = IndexCodec.entryKey(TABLE, SECONDARY, "x", 1);
        byte[] unique = IndexCodec.entryKey(TABLE, UNIQUE, 1L, 1);
        assertFalse(Arrays.equals(Arrays.copyOf(secondary, 8), Arrays.copyOf(unique, 8)));
        assertTrue(Arrays.compareUnsigned(IndexCodec.indexEnd(TABLE.id(), 1),
                IndexCodec.indexStart(TABLE.id(), 2)) == 0);
    }

    @Test
    void tombstoneDetectionUsesTableId() {
        byte[] tombstone = IndexCodec.tombstone(TABLE.id());
        assertTrue(IndexCodec.isTombstone(TABLE, tombstone));
        assertFalse(IndexCodec.isTombstone(TABLE, IndexCodec.entryValue(TABLE, UNIQUE, 1)));
    }
}
