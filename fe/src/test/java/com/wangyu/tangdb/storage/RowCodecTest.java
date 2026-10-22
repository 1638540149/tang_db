package com.wangyu.tangdb.storage;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 行值/墓碑编码测试(规约 §5.3)。 */
class RowCodecTest {

    private static TableMeta table(long id) {
        return new TableMeta(id, "t", TableMeta.STATE_ACTIVE, "", List.of(
                new Column("id", DataType.BIGINT, 0, false, true, 0),
                new Column("name", DataType.VARCHAR, 100, true, false, 1),
                new Column("age", DataType.INT, 0, true, false, 2),
                new Column("score", DataType.DOUBLE, 0, true, false, 3),
                new Column("birthday", DataType.DATE, 0, true, false, 4),
                new Column("active", DataType.BOOLEAN, 0, true, false, 5)
        ), false);
    }

    @Test
    void roundTripAllTypes() {
        TableMeta t = table(16);
        Object[] values = {1L, "wangyu", 26, 98.5, LocalDate.of(1999, 12, 31), true};
        byte[] encoded = RowCodec.encode(t, values);
        RowCodec.Decoded decoded = RowCodec.decode(t, encoded);
        assertFalse(decoded.tombstone());
        assertArrayEquals(values, decoded.values());
    }

    @Test
    void nullsAndEmptyStrings() {
        TableMeta t = table(16);
        Object[] values = {2L, "", null, null, null, false};
        RowCodec.Decoded decoded = RowCodec.decode(t, RowCodec.encode(t, values));
        assertEquals(2L, decoded.values()[0]);
        assertEquals("", decoded.values()[1]);
        assertNull(decoded.values()[2]);
        assertNull(decoded.values()[3]);
        assertNull(decoded.values()[4]);
        assertEquals(false, decoded.values()[5]);
    }

    @Test
    void chineseText() {
        TableMeta t = table(16);
        Object[] values = {3L, "北京·上海", null, null, null, null};
        RowCodec.Decoded decoded = RowCodec.decode(t, RowCodec.encode(t, values));
        assertEquals("北京·上海", decoded.values()[1]);
    }

    @Test
    void tombstoneRoundTrip() {
        TableMeta t = table(16);
        byte[] tomb = RowCodec.encodeTombstone(t.id());
        assertEquals(5, tomb.length);
        RowCodec.Decoded decoded = RowCodec.decode(t, tomb);
        assertTrue(decoded.tombstone());
        assertNull(decoded.values());
    }

    @Test
    void unknownVersionIsRejected() {
        TableMeta t = table(16);
        byte[] encoded = RowCodec.encode(t, new Object[]{1L, "a", 1, 1.0, LocalDate.of(2024, 1, 1), true});
        encoded[0] = 0x09;
        StoreException e = assertThrows(StoreException.class, () -> RowCodec.decode(t, encoded));
        assertTrue(e.getMessage().contains("未知的行格式版本"), e.getMessage());
    }

    @Test
    void tableIdMismatchIsRejected() {
        TableMeta t16 = table(16);
        TableMeta t17 = table(17);
        byte[] encoded = RowCodec.encode(t16, new Object[]{1L, "a", 1, 1.0, LocalDate.of(2024, 1, 1), true});
        StoreException e = assertThrows(StoreException.class, () -> RowCodec.decode(t17, encoded));
        assertTrue(e.getMessage().contains("表 ID 不匹配"), e.getMessage());
    }

    @Test
    void columnCountMismatchIsRejected() {
        TableMeta two = new TableMeta(16, "t", TableMeta.STATE_ACTIVE, "", List.of(
                new Column("id", DataType.BIGINT, 0, false, true, 0),
                new Column("name", DataType.VARCHAR, 10, true, false, 1)
        ), false);
        byte[] encoded = RowCodec.encode(table(16),
                new Object[]{1L, "a", 1, 1.0, LocalDate.of(2024, 1, 1), true});
        StoreException e = assertThrows(StoreException.class, () -> RowCodec.decode(two, encoded));
        assertTrue(e.getMessage().contains("列数不匹配"), e.getMessage());
    }

    @Test
    void truncatedDataIsRejected() {
        TableMeta t = table(16);
        byte[] encoded = RowCodec.encode(t, new Object[]{1L, "abcdef", 1, 1.0, LocalDate.of(2024, 1, 1), true});
        byte[] truncated = java.util.Arrays.copyOf(encoded, encoded.length - 3);
        assertThrows(StoreException.class, () -> RowCodec.decode(t, truncated));
    }
}
