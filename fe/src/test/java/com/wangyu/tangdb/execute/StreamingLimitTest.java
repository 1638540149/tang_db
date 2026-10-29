package com.wangyu.tangdb.execute;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.parser.Parser;
import com.wangyu.tangdb.protocol.mysql.Protocol;
import com.wangyu.tangdb.protocol.mysql.ResultSetEncoder;
import com.wangyu.tangdb.storage.KeyValue;
import com.wangyu.tangdb.storage.KvStore;
import com.wangyu.tangdb.storage.ScanCursor;
import com.wangyu.tangdb.storage.ScanOptions;
import com.wangyu.tangdb.testing.FakeKvStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC-P6.1 FR-715:结果集流式 + LIMIT 命中即停止向上游取数。
 *
 * <p>用一个"能数批次数"的游标存储证明两件事:</p>
 * <ol>
 *   <li>{@code LIMIT n} 只拉够 n 行就 close 游标(不再继续取),且结果与物化路径逐行相同;</li>
 *   <li>协议报文的字节序列与物化结果集完全一致(CLI / MySQL 客户端回显不变)。</li>
 * </ol>
 */
class StreamingLimitTest {

    private static final int ROWS = 5000;
    private static final int BATCH = 100;

    private ProbeStore kv;
    private Catalog catalog;
    private Executor streaming;
    private Executor materialized;

    @BeforeEach
    void setUp() {
        kv = new ProbeStore(BATCH);
        catalog = Catalog.open(kv);
        streaming = new Executor(kv, catalog).withExecution(1, false, BATCH)
                .withStreamingResults(true);
        materialized = new Executor(kv, catalog).withExecution(1, false, BATCH);
        streaming.execute(Parser.parse("CREATE TABLE s (id INT PRIMARY KEY, name VARCHAR(16), "
                + "amount INT)"));
        StringBuilder head = new StringBuilder("INSERT INTO s VALUES ");
        StringBuilder sql = new StringBuilder(head);
        int count = 0;
        for (int i = 0; i < ROWS; i++) {
            if (count > 0) {
                sql.append(',');
            }
            sql.append('(').append(i).append(",'n").append(i % 13).append("',").append(i % 97)
                    .append(')');
            if (++count == 250) {
                streaming.execute(Parser.parse(sql.toString()));
                sql = new StringBuilder(head);
                count = 0;
            }
        }
        if (count > 0) {
            streaming.execute(Parser.parse(sql.toString()));
        }
    }

    private Result run(Executor executor, String sql) {
        return executor.execute(Parser.parse(sql));
    }

    private static List<Object[]> drain(Result.StreamTable table) {
        List<Object[]> out = new ArrayList<>();
        try {
            while (true) {
                List<Object[]> batch = table.stream().nextBatch();
                if (batch == null) {
                    break;
                }
                out.addAll(batch);
            }
        } finally {
            table.stream().close();
        }
        return out;
    }

    private void assertSameRows(List<Object[]> expected, List<Object[]> actual, String sql) {
        assertEquals(expected.size(), actual.size(), "行数不一致: " + sql);
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(String.valueOf(java.util.Arrays.toString(expected.get(i))),
                    String.valueOf(java.util.Arrays.toString(actual.get(i))),
                    sql + " 第 " + (i + 1) + " 行");
        }
    }

    @Test
    void simpleSelectIsStreamedAndLimitStopsUpstream() {
        String sql = "SELECT id, name FROM s LIMIT 10";
        Result result = run(streaming, sql);
        assertInstanceOf(Result.StreamTable.class, result, "简单单表查询必须走流式结果");
        Result.StreamTable streamed = (Result.StreamTable) result;

        kv.reset();
        List<Object[]> rows = drain(streamed);

        Result.Table expected = (Result.Table) run(materialized, sql);
        assertSameRows(expected.rows(), rows, sql);
        assertEquals(10, rows.size());
        assertEquals(1, kv.batchesFetched, "LIMIT 10 只应取第一批(" + BATCH + " 行),实测 "
                + kv.batchesFetched + " 批");
        assertTrue(kv.rowsFetched <= BATCH + 1, "读取行数必须远小于全表,实测 " + kv.rowsFetched);
        assertTrue(kv.cursorClosed, "取够行数后必须 close 游标(终止上游扫描)");
    }

    @Test
    void limitWithOffsetAndFilterMatchesMaterialized() {
        String sql = "SELECT id, amount FROM s WHERE amount < 20 LIMIT 5 OFFSET 2";
        Result.Table expected = (Result.Table) run(materialized, sql);
        Result result = run(streaming, sql);
        assertInstanceOf(Result.StreamTable.class, result);
        kv.reset();
        List<Object[]> rows = drain((Result.StreamTable) result);
        assertSameRows(expected.rows(), rows, sql);
        assertEquals(5, rows.size());
        assertTrue(kv.rowsFetched < ROWS, "带谓词的 LIMIT 也必须提前收手,实测 " + kv.rowsFetched);
        assertTrue(kv.cursorClosed, "游标必须被关闭");
    }

    @Test
    void fullScanWithoutLimitStreamsEverything() {
        String sql = "SELECT id FROM s";
        Result.Table expected = (Result.Table) run(materialized, sql);
        Result result = run(streaming, sql);
        assertInstanceOf(Result.StreamTable.class, result);
        kv.reset();
        List<Object[]> rows = drain((Result.StreamTable) result);
        assertSameRows(expected.rows(), rows, sql);
        assertEquals(ROWS, rows.size());
        assertEquals(ROWS, kv.rowsFetched, "无 LIMIT 时必须把表取完");
        assertTrue(kv.cursorClosed, "读完后也要关闭游标");
    }

    @Test
    void queriesNeedingMaterializationFallBack() {
        for (String sql : List.of(
                "SELECT id FROM s ORDER BY id DESC",
                "SELECT amount, COUNT(*) FROM s GROUP BY amount",
                "SELECT DISTINCT name FROM s",
                "SELECT COUNT(*) FROM s")) {
            Result result = run(streaming, sql);
            assertInstanceOf(Result.Table.class, result,
                    "需要排序/聚合/去重的查询必须退回物化路径(结果一致): " + sql);
        }
    }

    @Test
    void packetBytesAreIdenticalToMaterializedResult() {
        String sql = "SELECT id, name FROM s LIMIT 25";
        Result.Table expected = (Result.Table) run(materialized, sql);
        Result.StreamTable streamed = (Result.StreamTable) run(streaming, sql);

        List<byte[]> expectedPackets = ResultSetEncoder.forResult(expected,
                Protocol.SERVER_STATUS_AUTOCOMMIT, false);
        List<byte[]> actualPackets = ResultSetEncoder.forResult(streamed,
                Protocol.SERVER_STATUS_AUTOCOMMIT, false);
        assertEquals(expectedPackets.size(), actualPackets.size(), "包数必须一致");
        for (int i = 0; i < expectedPackets.size(); i++) {
            assertArrayEquals(expectedPackets.get(i), actualPackets.get(i),
                    "第 " + i + " 个协议包必须逐字节一致");
        }
    }

    /** 能数游标批次的内存存储(只用于本期单测)。 */
    private static final class ProbeStore implements KvStore,
            com.wangyu.tangdb.storage.CursoredStore {

        private final FakeKvStore delegate = new FakeKvStore();
        private final int batchRows;
        int batchesFetched;
        int rowsFetched;
        boolean cursorClosed;

        ProbeStore(int batchRows) {
            this.batchRows = batchRows;
        }

        void reset() {
            batchesFetched = 0;
            rowsFetched = 0;
            cursorClosed = false;
        }

        @Override
        public byte[] get(byte[] key) {
            return delegate.get(key);
        }

        @Override
        public void put(byte[] key, byte[] value) {
            delegate.put(key, value);
        }

        @Override
        public List<KeyValue> scan(byte[] start, byte[] end, int limit) {
            return delegate.scan(start, end, limit);
        }

        @Override
        public ScanCursor openScan(byte[] start, byte[] end, long snapshot, int limit,
                                   ScanOptions options) {
            List<KeyValue> all = delegate.scan(start, end, 0);
            if (limit > 0 && all.size() > limit) {
                all = all.subList(0, limit);
            }
            List<KeyValue> rows = all;
            return new ScanCursor() {
                private int pos;
                private boolean closed;

                @Override
                public List<KeyValue> nextBatch() {
                    if (closed || pos >= rows.size()) {
                        if (pos >= rows.size()) {
                            cursorClosed = true;
                        }
                        return null;
                    }
                    int to = Math.min(rows.size(), pos + batchRows);
                    List<KeyValue> batch = new ArrayList<>(rows.subList(pos, to));
                    batchesFetched++;
                    rowsFetched += batch.size();
                    pos = to;
                    return batch;
                }

                @Override
                public void close() {
                    closed = true;
                    cursorClosed = true;
                }
            };
        }
    }
}
