package com.wangyu.tangdb.storage;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.execute.Executor;
import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.parser.Parser;
import com.wangyu.tangdb.testing.FakeKvStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/** SPEC-P5 A 组:FE 侧与 BE 同口径的列式编码选择 + SHOW SEGMENTS / DATA_LENGTH(SC-349)。 */
class ColumnarCodecTest {

    private FakeKvStore kv;
    private Catalog catalog;
    private Executor executor;

    @BeforeEach
    void setUp() {
        kv = new FakeKvStore();
        catalog = Catalog.open(kv);
        executor = new Executor(kv, catalog);
    }

    private static List<byte[]> values(String... values) {
        List<byte[]> out = new ArrayList<>(values.length);
        for (String v : values) {
            out.add(v.getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test
    void rawBytesMatchBeUvarintConvention() {
        // 5 个 1 字节值:1 + 1 字节 × 5 = 10。
        assertEquals(10, ColumnarCodec.rawBytes(values("a", "b", "c", "d", "e")));
        // 128 字节值:uvarint(128) = 2 字节 + 128。
        byte[] big = new byte[128];
        assertEquals(130, ColumnarCodec.rawBytes(List.of(big)));
    }

    @Test
    void repeatedValuesChunkPicksRleOrDict() {
        List<byte[]> repeated = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            repeated.add("same".getBytes(StandardCharsets.UTF_8));
        }
        ColumnarCodec.Encoded encoded = ColumnarCodec.chooseBest(repeated);
        assertNotEquals(ColumnarCodec.Encoding.RAW, encoded.encoding(),
                "重复值不应选 RAW");
        assertTrue(encoded.compressedBytes() * 10 < encoded.rawBytes(),
                "重复值的压缩率应显著 > 1:raw=" + encoded.rawBytes()
                        + " comp=" + encoded.compressedBytes());
    }

    @Test
    void sharedPrefixChunkPicksPrefixOrDict() {
        List<byte[]> values = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            values.add(String.format(Locale.ROOT, "tangdb/internal/store/table/%08d", i)
                    .getBytes(StandardCharsets.UTF_8));
        }
        ColumnarCodec.Encoded encoded = ColumnarCodec.chooseBest(values);
        assertTrue(encoded.encoding() == ColumnarCodec.Encoding.PREFIX
                        || encoded.encoding() == ColumnarCodec.Encoding.DICT,
                "共享前缀应选 PREFIX/DICT,实际 " + encoded.encoding());
        assertTrue(encoded.compressedBytes() < encoded.rawBytes());
    }

    @Test
    void fixedWidthIntegersCanUseBitpack() {
        List<byte[]> values = new ArrayList<>();
        for (long i = 0; i < 200; i++) {
            long v = 1000 + i * 3;
            values.add(new byte[]{
                    (byte) (v >>> 56), (byte) (v >>> 48), (byte) (v >>> 40), (byte) (v >>> 32),
                    (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v});
        }
        ColumnarCodec.Encoded encoded = ColumnarCodec.chooseBest(values);
        assertEquals(9 * 200, ColumnarCodec.rawBytes(values));
        assertTrue(encoded.compressedBytes() < encoded.rawBytes());
    }

    @Test
    void showSegmentsAndDataLengthShareTheSameNumbers() {
        executor.execute(Parser.parse(
                "CREATE TABLE seg_demo (id INT PRIMARY KEY, city VARCHAR(16), amount INT)"));
        for (int i = 0; i < 2000; i++) {
            executor.execute(Parser.parse("INSERT INTO seg_demo VALUES (" + i
                    + ", 'city-" + (i % 8) + "', " + (i % 100) + ")"));
        }

        Result.Table segments = (Result.Table) executor.execute(Parser.parse("SHOW SEGMENTS"));
        assertEquals(List.of("segment", "column", "rows", "chunks", "raw_bytes",
                "compressed_bytes", "ratio", "encodings"), segments.columns());
        assertEquals(2, segments.rows().size(), "键列 + 值列各一行");
        long compressed = 0;
        for (Object[] row : segments.rows()) {
            assertEquals(2000L, ((Number) row[2]).longValue());
            compressed += ((Number) row[5]).longValue();
            assertNotNull(row[7], "编码分布必须有值");
        }
        assertTrue(compressed > 0);

        Result.Table status = (Result.Table) executor.execute(Parser.parse("SHOW TABLE STATUS"));
        long dataLength = -1;
        for (Object[] row : status.rows()) {
            if ("seg_demo".equals(row[0])) {
                dataLength = ((Number) row[6]).longValue();
            }
        }
        assertEquals(compressed, dataLength, "SHOW SEGMENTS 与 DATA_LENGTH 必须同口径");

        com.wangyu.tangdb.session.SessionManager sessions =
                new com.wangyu.tangdb.session.SessionManager(4, 0);
        com.wangyu.tangdb.execute.SqlEngine engine = new com.wangyu.tangdb.execute.SqlEngine(
                kv, catalog, sessions,
                new com.wangyu.tangdb.server.BackendRegistry(3), false, "root");
        com.wangyu.tangdb.session.Session session =
                sessions.create("127.0.0.1:1", executor);
        Result.Table info = (Result.Table) engine.execute(session,
                "SELECT DATA_LENGTH FROM information_schema.TABLES WHERE TABLE_NAME = 'seg_demo'");
        assertEquals(1, info.rows().size());
        assertEquals(dataLength, ((Number) info.rows().get(0)[0]).longValue());
    }

    @Test
    void showSegmentsForSingleTableAndEmptyTable() {
        executor.execute(Parser.parse("CREATE TABLE empty_seg (id INT PRIMARY KEY, v VARCHAR(8))"));
        Result.Table empty = (Result.Table) executor.execute(Parser.parse("SHOW SEGMENTS FROM empty_seg"));
        assertEquals(2, empty.rows().size());
        for (Object[] row : empty.rows()) {
            assertEquals(0L, ((Number) row[2]).longValue());
            assertEquals(0L, ((Number) row[5]).longValue());
        }
    }
}
