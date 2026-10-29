package com.wangyu.tangdb.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 行框架协议 / 列投影帧的 FE 侧编解码(SPEC-P6.1 FR-701 / FR-702)。 */
class ProjectedCodecTest {

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

    /**
     * 按 BE 的算法在测试里把整行投影成帧:
     * [nullmap][len][载荷] × 选中列;VARCHAR/JSON 的 4 字节长度前缀由 BE 剥掉。
     */
    private static byte[] project(TableMeta table, int mask, byte[] storedRow) {
        int n = table.columnCount();
        List<Integer> selected = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if ((mask & (1 << i)) != 0) {
                selected.add(i);
            }
        }
        int nullmapLen = (selected.size() + 7) / 8;
        byte[] nullmap = new byte[nullmapLen];
        List<byte[]> framed = new ArrayList<>();
        int pos = 7 + (n + 7) / 8;
        for (int i = 0; i < n; i++) {
            boolean isNull = (storedRow[7 + i / 8] & (1 << (i % 8))) != 0;
            DataType type = table.columns().get(i).type();
            int payloadStart = pos;
            int payloadLen = 0;
            if (!isNull) {
                if (type == DataType.VARCHAR || type == DataType.JSON) {
                    payloadLen = ProjectedCodec.readLe4(storedRow, pos);
                    payloadStart = pos + 4;
                    pos += 4 + payloadLen;
                } else {
                    payloadLen = ProjectedCodec.slotWidth(type);
                    pos += payloadLen;
                }
            }
            if (!selected.contains(i)) {
                continue;
            }
            int slot = selected.indexOf(i);
            if (isNull) {
                nullmap[slot / 8] |= (byte) (1 << (slot % 8));
                continue;
            }
            byte[] frame = new byte[4 + payloadLen];
            writeLe4(frame, 0, payloadLen);
            System.arraycopy(storedRow, payloadStart, frame, 4, payloadLen);
            framed.add(frame);
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes(nullmap);
        for (byte[] frame : framed) {
            out.writeBytes(frame);
        }
        return out.toByteArray();
    }

    @Test
    void planOnlyKeepsNeededColumnsPlusPrimaryKey() {
        TableMeta t = table(16);
        boolean[] needed = {false, true, false, false, false, false};
        ProjectedCodec.Plan plan = ProjectedCodec.plan(t, needed);
        assertNotNull(plan);
        assertArrayEquals(new int[]{0, 1}, plan.selectedOrdinals());
        assertEquals(0b11, plan.mask());
        assertEquals(1, plan.nullmapBytes());
    }

    @Test
    void frameRoundTrip() {
        TableMeta t = table(16);
        Object[] values = {7L, "北京", 26, 98.5, LocalDate.of(1999, 12, 31), true};
        byte[] stored = RowCodec.encode(t, values);
        boolean[] needed = {false, true, false, true, true, false};
        ProjectedCodec.Plan plan = ProjectedCodec.plan(t, needed);
        byte[] frame = project(t, plan.mask(), stored);

        Object[] decoded = ProjectedCodec.decode(t, plan, frame);
        assertEquals(7L, decoded[0]);
        assertEquals("北京", decoded[1]);
        assertNull(decoded[2], "未选中的列必须是 null");
        assertEquals(98.5, decoded[3]);
        assertEquals(LocalDate.of(1999, 12, 31), decoded[4]);
        assertNull(decoded[5], "未选中的列必须是 null");
    }

    @Test
    void frameRoundTripWithNulls() {
        TableMeta t = table(16);
        Object[] values = {1L, "", null, null, null, false};
        byte[] stored = RowCodec.encode(t, values);
        boolean[] needed = {true, true, true, true, true, true};
        ProjectedCodec.Plan plan = ProjectedCodec.plan(t, needed);
        Object[] decoded = ProjectedCodec.decode(t, plan, project(t, plan.mask(), stored));
        assertArrayEquals(values, decoded);
    }

    @Test
    void columnarBlockDecodes() {
        TableMeta t = table(16);
        boolean[] needed = {true, true, false, false, false, false};
        ProjectedCodec.Plan plan = ProjectedCodec.plan(t, needed);
        byte[] f1 = project(t, plan.mask(), RowCodec.encode(t, new Object[]{1L, "a", 1, 1.0, null, true}));
        byte[] f2 = project(t, plan.mask(), RowCodec.encode(t, new Object[]{2L, "b", 2, 2.0, null, true}));

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] header = new byte[4 + 4 * 3];
        header[0] = 2;
        // offsets: 0, len1, len1+len2
        java.util.List<byte[]> keys = List.of(new byte[]{9}, new byte[]{8});
        java.util.List<byte[]> frames = List.of(f1, f2);
        int off1 = 4 + keys.get(0).length + f1.length;
        writeLe4(header, 4, 0);
        writeLe4(header, 8, off1);
        writeLe4(header, 12, off1 + 4 + keys.get(1).length + f2.length);
        out.writeBytes(header);
        for (int i = 0; i < 2; i++) {
            writeLe4(out, keys.get(i).length);
            out.writeBytes(keys.get(i));
            out.writeBytes(frames.get(i));
        }
        List<KeyValue> rows = ProjectedCodec.decodeBlock(out.toByteArray());
        assertEquals(2, rows.size());
        assertArrayEquals(new byte[]{9}, rows.get(0).key());
        Object[] row0 = ProjectedCodec.decode(t, plan, rows.get(0).value());
        assertEquals(1L, row0[0]);
        assertEquals("a", row0[1]);
        Object[] row1 = ProjectedCodec.decode(t, plan, rows.get(1).value());
        assertEquals(2L, row1[0]);
        assertEquals("b", row1[1]);
    }

    @Test
    void literalEncodingIsOrderPreserving() {
        byte[] a = ProjectedCodec.encodeLiteral(DataType.INT, -5);
        byte[] b = ProjectedCodec.encodeLiteral(DataType.INT, 0);
        byte[] c = ProjectedCodec.encodeLiteral(DataType.INT, 7);
        assertTrue(compare(a, b) < 0 && compare(b, c) < 0, "INT 字面量必须保序");

        byte[] la = ProjectedCodec.encodeLiteral(DataType.BIGINT, Long.MIN_VALUE);
        byte[] lb = ProjectedCodec.encodeLiteral(DataType.BIGINT, 0L);
        byte[] lc = ProjectedCodec.encodeLiteral(DataType.BIGINT, Long.MAX_VALUE);
        assertTrue(compare(la, lb) < 0 && compare(lb, lc) < 0, "BIGINT 字面量必须保序");

        byte[] da = ProjectedCodec.encodeLiteral(DataType.DOUBLE, -1.5);
        byte[] db = ProjectedCodec.encodeLiteral(DataType.DOUBLE, 0.0);
        byte[] dc = ProjectedCodec.encodeLiteral(DataType.DOUBLE, 1e300);
        assertTrue(compare(da, db) < 0 && compare(db, dc) < 0, "DOUBLE 字面量必须保序");

        byte[] s1 = ProjectedCodec.encodeLiteral(DataType.VARCHAR, "abc");
        byte[] expected = "abc".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < expected.length; i++) {
            expected[i] ^= (byte) 0x80; // 与 BE 的 SLOT_VARLEN_SIGNED 对齐
        }
        assertArrayEquals(expected, s1);
        assertNull(ProjectedCodec.encodeLiteral(DataType.JSON, new byte[]{1, 2}),
                "JSON 不支持下推");
    }

    private static int compare(byte[] a, byte[] b) {
        return java.util.Arrays.compareUnsigned(a, b);
    }

    private static void writeLe4(java.io.ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 24) & 0xff);
    }

    private static void writeLe4(byte[] buf, int pos, int v) {
        buf[pos] = (byte) v;
        buf[pos + 1] = (byte) (v >>> 8);
        buf[pos + 2] = (byte) (v >>> 16);
        buf[pos + 3] = (byte) (v >>> 24);
    }
}
