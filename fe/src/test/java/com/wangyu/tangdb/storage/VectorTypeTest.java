package com.wangyu.tangdb.storage;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.catalog.CatalogException;
import com.wangyu.tangdb.parser.Ast;
import com.wangyu.tangdb.parser.Parser;
import com.wangyu.tangdb.testing.FakeKvStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC-P7 A 组:`VECTOR(n)` 类型、行内定长编码、列槽与红线校验。
 *
 * <p>覆盖 SC-419(建表/读写逐 bit 相等、维度不符报错)与 SC-420(主键/二级索引/普通比较 1235)。</p>
 */
class VectorTypeTest {

    private static TableMeta table(long id, int dim) {
        return new TableMeta(id, "docs", TableMeta.STATE_ACTIVE, "", List.of(
                new Column("id", DataType.BIGINT, 0, false, true, 0),
                new Column("title", DataType.VARCHAR, 64, true, false, 1),
                new Column("embedding", DataType.VECTOR, dim, true, false, 2)
        ), false);
    }

    @Test
    void dataTypeIsVectorAndCannotBePrimaryKey() {
        assertEquals(8, DataType.VECTOR.code());
        assertEquals(DataType.VECTOR, DataType.parse("vector"));
        assertFalse(DataType.VECTOR.isNumeric());
        assertFalse(DataType.VECTOR.canBePrimaryKey());
        assertTrue(DataType.VECTOR.isVector());
        assertEquals("VECTOR(12)", new Column("e", DataType.VECTOR, 12, true, false, 0).typeName());
        assertThrows(IllegalArgumentException.class,
                () -> new Column("e", DataType.VECTOR, 0, true, false, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Column("e", DataType.VECTOR, DataType.MAX_VECTOR_DIM + 1, true, false, 0));
    }

    @Test
    void rowCodecRoundTripsVectorsBitExact() {
        TableMeta t = table(70, 3);
        float[] vec = {0.125f, -1.5f, Float.MIN_VALUE};
        byte[] row = RowCodec.encode(t, new Object[]{7L, "hello", vec});
        RowCodec.Decoded decoded = RowCodec.decode(t, row);
        assertFalse(decoded.tombstone());
        float[] back = (float[]) decoded.values()[2];
        assertEquals(3, back.length);
        for (int i = 0; i < vec.length; i++) {
            assertEquals(Float.floatToRawIntBits(vec[i]), Float.floatToRawIntBits(back[i]),
                    "第 " + i + " 个元素必须逐 bit 相等");
        }
        // 列裁剪解码跳过向量列(不分配 float[])
        RowCodec.Decoded trimmed = RowCodec.decode(t, row, new boolean[]{true, true, false});
        assertNull(trimmed.values()[2]);
        assertEquals("hello", trimmed.values()[1]);
    }

    @Test
    void rowCodecRejectsDimMismatch() {
        TableMeta t = table(71, 3);
        StoreException e = assertThrows(StoreException.class,
                () -> RowCodec.encode(t, new Object[]{1L, "x", new float[]{1f, 2f}}));
        assertTrue(e.getMessage().contains("维度不符"), e.getMessage());
    }

    @Test
    void projectedCodecCarriesWideSlotAndDecodesVector() {
        TableMeta t = table(72, 4);
        byte[] slots = ProjectedCodec.encodeSlots(t);
        // 2 + 3*4 的基础槽表 + 2 字节宽槽数 + 6 字节宽槽项
        assertEquals(2 + 3 * 4 + 2 + 6, slots.length);
        assertEquals(ProjectedCodec.SLOT_VECTOR_FIXED, ProjectedCodec.slotKind(t.columns().get(2)));
        assertEquals(16, ProjectedCodec.slotWidth(t.columns().get(2)));
        // 宽槽段必须按小端写:BE 用 binary.LittleEndian 读([u16 数][u16 ordinal][u32 width])
        int widePos = 2 + 3 * 4;
        assertEquals(1, (slots[widePos] & 0xff) | ((slots[widePos + 1] & 0xff) << 8));
        int width = (slots[widePos + 4] & 0xff) | ((slots[widePos + 5] & 0xff) << 8)
                | ((slots[widePos + 6] & 0xff) << 16) | ((slots[widePos + 7] & 0xff) << 24);
        assertEquals(16, width, "宽槽宽度必须按小端编码(与 BE 的 ParseSlots 一致)");

        ProjectedCodec.Plan plan = ProjectedCodec.plan(t, new boolean[]{true, true, true});
        assertNotNull(plan);
        float[] vec = {1f, 2f, 3f, 4f};
        byte[] row = RowCodec.encode(t, new Object[]{9L, "t", vec});
        byte[] frame = project(t, plan.mask(), row);
        Object[] decoded = ProjectedCodec.decode(t, plan, frame);
        float[] back = (float[]) decoded[2];
        for (int i = 0; i < vec.length; i++) {
            assertEquals(Float.floatToRawIntBits(vec[i]), Float.floatToRawIntBits(back[i]));
        }
    }

    /** 按 BE 的 ProjectRow 口径把整行投影成帧(vec 列是定长 4n 载荷、无长度前缀)。 */
    private static byte[] project(TableMeta t, int mask, byte[] storedRow) {
        List<Integer> selected = new ArrayList<>();
        for (int i = 0; i < t.columnCount(); i++) {
            if ((mask & (1 << i)) != 0) {
                selected.add(i);
            }
        }
        int nullmapLen = (selected.size() + 7) / 8;
        byte[] out = new byte[nullmapLen];
        List<byte[]> payloads = new ArrayList<>();
        List<Boolean> nulls = new ArrayList<>();
        int pos = 7 + (t.columnCount() + 7) / 8;
        for (int i = 0; i < t.columnCount(); i++) {
            boolean isNull = (storedRow[7 + i / 8] & (1 << (i % 8))) != 0;
            int start = pos;
            int len = 0;
            if (!isNull) {
                Column column = t.columns().get(i);
                if (column.type() == DataType.VARCHAR || column.type() == DataType.JSON) {
                    len = ProjectedCodec.readLe4(storedRow, pos);
                    start = pos + 4;
                    pos += 4 + len;
                } else {
                    len = ProjectedCodec.slotWidth(column);
                    pos += len;
                }
            }
            if (!selected.contains(i)) {
                continue;
            }
            nulls.add(isNull);
            byte[] payload = new byte[isNull ? 0 : len];
            if (!isNull) {
                System.arraycopy(storedRow, start, payload, 0, len);
            }
            payloads.add(payload);
        }
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < nulls.size(); i++) {
            if (nulls.get(i)) {
                out[i / 8] |= (byte) (1 << (i % 8));
                continue;
            }
            byte[] payload = payloads.get(i);
            body.write(payload.length & 0xff);
            body.write((payload.length >>> 8) & 0xff);
            body.write((payload.length >>> 16) & 0xff);
            body.write((payload.length >>> 24) & 0xff);
            body.writeBytes(payload);
        }
        byte[] bodyBytes = body.toByteArray();
        byte[] frame = new byte[out.length + bodyBytes.length];
        System.arraycopy(out, 0, frame, 0, out.length);
        System.arraycopy(bodyBytes, 0, frame, out.length, bodyBytes.length);
        return frame;
    }

    @Test
    void parserAcceptsVectorDimensionAndRejectsOutOfRange() {
        Ast.Statement st = Parser.parse("CREATE TABLE docs (id INT PRIMARY KEY, embedding VECTOR(128))");
        Ast.CreateTable create = (Ast.CreateTable) st;
        assertEquals(DataType.VECTOR, create.columns().get(1).type());
        assertEquals(128, create.columns().get(1).length());

        assertThrows(RuntimeException.class,
                () -> Parser.parse("CREATE TABLE t (id INT PRIMARY KEY, e VECTOR(0))"));
        assertThrows(RuntimeException.class,
                () -> Parser.parse("CREATE TABLE t (id INT PRIMARY KEY, e VECTOR(-1))"));
        assertThrows(RuntimeException.class,
                () -> Parser.parse("CREATE TABLE t (id INT PRIMARY KEY, e VECTOR(4097))"));
        assertThrows(RuntimeException.class,
                () -> Parser.parse("CREATE TABLE t (id INT PRIMARY KEY, e VECTOR)"));
    }

    @Test
    void catalogRejectsVectorPrimaryKeyAndPlainIndexWith1235() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        CatalogException pk = assertThrows(CatalogException.class, () -> catalog.createTable(
                "default", "bad", List.of(
                        new Column("e", DataType.VECTOR, 4, false, true, 0))));
        assertEquals(1235, pk.code());

        TableMeta ok = catalog.createTable("default", "docs", List.of(
                new Column("id", DataType.INT, 0, false, true, 0),
                new Column("e", DataType.VECTOR, 4, true, false, 1)));
        CatalogException idx = assertThrows(CatalogException.class,
                () -> catalog.createIndex("default", "docs", "idx_e", "e", false));
        assertEquals(1235, idx.code());
        assertTrue(catalog.indexes(ok.id()).isEmpty());
    }

    @Test
    void valuesCoerceAndDisplayVectors() {
        Column column = new Column("e", DataType.VECTOR, 3, true, false, 1);
        float[] fromLiteral = (float[]) Values.coerce("[1,2.5,-3]", column);
        assertArrayEquals(new float[]{1f, 2.5f, -3f}, fromLiteral);
        assertArrayEquals(new float[]{1f, 2.5f, -3f},
                (float[]) Values.coerce(fromLiteral, column));
        StoreException dim = assertThrows(StoreException.class,
                () -> Values.coerce("[1,2]", column));
        assertTrue(dim.getMessage().contains("期望 3 个,实际 2 个"), dim.getMessage());
        StoreException nan = assertThrows(StoreException.class,
                () -> Values.coerce("[1,NaN,3]", column));
        assertTrue(nan.getMessage().contains("第 2 个元素"), nan.getMessage());
        StoreException inf = assertThrows(StoreException.class,
                () -> Values.coerce("[1,2,Infinity]", column));
        assertTrue(inf.getMessage().contains("第 3 个元素"), inf.getMessage());
        assertEquals("[1.0,2.5,-3.0]", Values.display(fromLiteral));
    }
}
