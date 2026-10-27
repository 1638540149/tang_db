package com.wangyu.tangdb.index;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.execute.Executor;
import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.parser.Parser;
import com.wangyu.tangdb.storage.IndexCodec;
import com.wangyu.tangdb.storage.IndexMeta;
import com.wangyu.tangdb.storage.KeyCodec;
import com.wangyu.tangdb.storage.KeyValue;
import com.wangyu.tangdb.storage.StoreException;
import com.wangyu.tangdb.storage.TableMeta;
import com.wangyu.tangdb.testing.FakeKvStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** SPEC-P4 A-6 / FR-467 ~ FR-470 / K84 / K85 / K89:分批回填、游标续做、唯一冲突降级。 */
class BackfillTest {

    private static String sqlInsert(String table, int rows) {
        StringBuilder sb = new StringBuilder("INSERT INTO " + table + " (id, code) VALUES ");
        for (int i = 1; i <= rows; i++) {
            if (i > 1) {
                sb.append(", ");
            }
            sb.append("(").append(i).append(", ").append(i % 5).append(")");
        }
        return sb.toString();
    }

    private static int liveEntries(TableMeta table, IndexMeta index, FakeKvStore kv) {
        List<KeyValue> rows = kv.scan(IndexCodec.indexStart(table.id(), index.indexId()),
                IndexCodec.indexEnd(table.id(), index.indexId()), 0);
        int live = 0;
        for (KeyValue row : rows) {
            if (!IndexCodec.isTombstone(table, row.value())) {
                live++;
            }
        }
        return live;
    }

    @Test
    void backfillCompletesInBatchesAndPersistsCursor() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        Executor executor = new Executor(kv, catalog).attachServer(null, "root", 0, 100);
        executor.execute(Parser.parse("CREATE TABLE bi (id INT PRIMARY KEY, code INT)"));
        executor.execute(Parser.parse(sqlInsert("bi", 2500)));

        Result.Message message = (Result.Message) executor.execute(
                Parser.parse("CREATE INDEX idx_code ON bi (code)"));
        assertTrue(message.text().contains("ACTIVE"), message.text());
        TableMeta table = catalog.find("default", "bi");
        IndexMeta index = catalog.findIndexByName(table.id(), "idx_code");
        assertEquals(IndexMeta.STATE_ACTIVE, index.state());
        assertFalse(index.buildCursor().isBlank(), "回填游标必须持久化");
        assertEquals(2500, liveEntries(table, index, kv));

        Result.Table all = (Result.Table) executor.execute(
                Parser.parse("SELECT id FROM bi WHERE code = 3"));
        assertEquals(500, all.rows().size());
        Result.Table baseline = (Result.Table) executor.execute(Parser.parse(
                "SELECT id FROM bi WHERE code = 3 AND id >= -2147483648"));
        assertEquals(500, baseline.rows().size());
    }

    @Test
    void backfillResumesFromPersistedCursorAfterInterruption() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        Executor executor = new Executor(kv, catalog).attachServer(null, "root", 0, 10);
        executor.execute(Parser.parse("CREATE TABLE br (id INT PRIMARY KEY, code INT)"));
        executor.execute(Parser.parse(sqlInsert("br", 60)));
        TableMeta table = catalog.find("default", "br");

        // 模拟"回填到第 20 行就被强杀":元数据 BUILDING + 前 20 行的索引项 + 游标。
        IndexMeta building = catalog.createIndex("default", "br", "idx_code", "code", false);
        assertEquals(IndexMeta.STATE_BUILDING, building.state());
        TreeMap<byte[], byte[]> staged = new TreeMap<>(java.util.Arrays::compareUnsigned);
        byte[] lastPk = null;
        for (int id = 1; id <= 20; id++) {
            int code = id % 5;
            byte[] key = IndexCodec.entryKey(table, building, code, id);
            staged.put(key, new byte[0]);
            lastPk = KeyCodec.encodePrimaryKey(com.wangyu.tangdb.storage.DataType.INT, id);
        }
        List<KeyValue> writes = new ArrayList<>();
        for (Map.Entry<byte[], byte[]> entry : staged.entrySet()) {
            writes.add(new KeyValue(entry.getKey(), entry.getValue()));
        }
        catalog.advanceBackfill(building, hex(lastPk), writes);

        // 重跑同一条 CREATE INDEX:从游标续做,不重扫前 20 行也能完成。
        Result.Message message = (Result.Message) executor.execute(
                Parser.parse("CREATE INDEX idx_code ON br (code)"));
        assertTrue(message.text().contains("ACTIVE"), message.text());
        IndexMeta done = catalog.findIndexByName(table.id(), "idx_code");
        assertEquals(IndexMeta.STATE_ACTIVE, done.state());
        assertEquals(60, liveEntries(table, done, kv));

        Result.Table all = (Result.Table) executor.execute(
                Parser.parse("SELECT id FROM br WHERE code = 0"));
        assertEquals(12, all.rows().size());
    }

    @Test
    void writesDuringBuildingAreIndexedSoNoIncrementalRescanIsNeeded() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        Executor executor = new Executor(kv, catalog).attachServer(null, "root", 0, 1000);
        executor.execute(Parser.parse("CREATE TABLE bw (id INT PRIMARY KEY, code INT)"));
        executor.execute(Parser.parse(sqlInsert("bw", 100)));
        TableMeta table = catalog.find("default", "bw");

        // BUILDING 期间的新写入由写路径直接进索引(I16 / K89)。
        IndexMeta building = catalog.createIndex("default", "bw", "idx_code", "code", false);
        executor.execute(Parser.parse("INSERT INTO bw (id, code) VALUES (101, 1)"));
        IndexMeta afterInsert = catalog.findIndexByName(table.id(), "idx_code");
        assertEquals(1, liveEntries(table, afterInsert, kv), "BUILDING 索引也要维护写入");

        executor.execute(Parser.parse("CREATE INDEX idx_code ON bw (code)"));
        IndexMeta done = catalog.findIndexByName(table.id(), "idx_code");
        assertEquals(IndexMeta.STATE_ACTIVE, done.state());
        assertEquals(101, liveEntries(table, done, kv));
        assertEquals(21, ((Result.Table) executor.execute(
                Parser.parse("SELECT id FROM bw WHERE code = 1"))).rows().size());
    }

    @Test
    void uniqueBackfillDuplicateDropsIndexAndReportsBothPrimaryKeys() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        Executor executor = new Executor(kv, catalog).attachServer(null, "root", 0, 100);
        executor.execute(Parser.parse("CREATE TABLE bu (id INT PRIMARY KEY, code VARCHAR(8))"));
        executor.execute(Parser.parse("INSERT INTO bu (id, code) VALUES (1, 'A'), (2, 'A'), (3, 'B')"));
        TableMeta table = catalog.find("default", "bu");

        StoreException e = assertThrows(StoreException.class,
                () -> executor.execute(Parser.parse("CREATE UNIQUE INDEX uk_code ON bu (code)")));
        assertEquals(SqlError.ER_DUP_ENTRY, e.code());
        assertTrue(e.getMessage().contains("A"), e.getMessage());
        assertTrue(e.getMessage().contains("主键 1"), e.getMessage());
        assertTrue(e.getMessage().contains("与 2"), e.getMessage());

        IndexMeta dropped = catalog.findIndex(table.id(), 1);
        assertNotNull(dropped);
        assertEquals(IndexMeta.STATE_DROPPED, dropped.state());
        assertTrue(catalog.indexes(table.id()).isEmpty(), "DROPPED 索引元数据不可见");
        // 不留半成品:元数据可见列表里没有 ACTIVE 的 uk_code。
        assertNull(catalog.findIndexByName(table.id(), "uk_code"));
    }

    @Test
    void uniqueBackfillFailureLeavesNoActiveIndexAndNameCanBeReused() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        Executor executor = new Executor(kv, catalog).attachServer(null, "root", 0, 100);
        executor.execute(Parser.parse("CREATE TABLE bd (id INT PRIMARY KEY, code INT)"));
        executor.execute(Parser.parse("INSERT INTO bd (id, code) VALUES (1, 7), (2, 7)"));
        assertThrows(StoreException.class,
                () -> executor.execute(Parser.parse("CREATE UNIQUE INDEX uk ON bd (code)")));

        executor.execute(Parser.parse("DELETE FROM bd WHERE id = 2"));
        Result.Message message = (Result.Message) executor.execute(
                Parser.parse("CREATE UNIQUE INDEX uk ON bd (code)"));
        assertTrue(message.text().contains("ACTIVE"), message.text());
        TableMeta table = catalog.find("default", "bd");
        IndexMeta recreated = catalog.findIndexByName(table.id(), "uk");
        assertTrue(recreated.indexId() > 1, "索引 ID 不复用");
    }

    @Test
    void indexMetadataReloadsFromSysIndexesAfterRestart() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        Executor executor = new Executor(kv, catalog);
        executor.execute(Parser.parse("CREATE TABLE rl (id INT PRIMARY KEY, code INT, note INT)"));
        executor.execute(Parser.parse("INSERT INTO rl (id, code, note) VALUES (1,7,10),(2,7,20)"));
        executor.execute(Parser.parse("CREATE INDEX idx_code ON rl (code)"));
        executor.execute(Parser.parse("CREATE UNIQUE INDEX uk_note ON rl (note)"));

        // 模拟 fed 重启:从 sys_indexes 重新加载(§FR-475 / FR-482)。
        Catalog reopened = Catalog.open(kv);
        TableMeta table = reopened.find("default", "rl");
        assertNotNull(table);
        assertEquals(2, reopened.indexes(table.id()).size());
        IndexMeta secondary = reopened.findIndexByName(table.id(), "idx_code");
        assertNotNull(secondary);
        assertEquals("code", secondary.columnName());
        assertEquals(IndexMeta.STATE_ACTIVE, secondary.state());
        assertTrue(reopened.findIndexByName(table.id(), "uk_note").unique());

        Executor after = new Executor(kv, reopened);
        Result.Table viaIndex = (Result.Table) after.execute(Parser.parse(
                "SELECT id FROM rl WHERE code = 7"));
        assertEquals(2, viaIndex.rows().size());
    }

    @Test
    void interruptionLeavesIndexBuildingForResume() {
        FailingStore kv = new FailingStore();
        Catalog catalog = Catalog.open(kv);
        Executor executor = new Executor(kv, catalog).attachServer(null, "root", 0, 10);
        executor.execute(Parser.parse("CREATE TABLE bri (id INT PRIMARY KEY, code INT)"));
        executor.execute(Parser.parse(sqlInsert("bri", 50)));
        IndexMeta building = catalog.createIndex("default", "bri", "idx_code", "code", false);
        assertEquals(IndexMeta.STATE_BUILDING, building.state());

        kv.armed = true;
        assertThrows(StoreException.class,
                () -> executor.execute(Parser.parse("CREATE INDEX idx_code ON bri (code)")));
        IndexMeta still = catalog.findIndexByName(catalog.find("default", "bri").id(), "idx_code");
        assertEquals(IndexMeta.STATE_BUILDING, still.state(),
                "连接中断/进程崩溃属于回填中断,必须保持 BUILDING 以便续做(不能标 DROPPED)");

        kv.armed = false;
        Result.Message message = (Result.Message) executor.execute(
                Parser.parse("CREATE INDEX idx_code ON bri (code)"));
        assertTrue(message.text().contains("ACTIVE"), message.text());
        TableMeta table = catalog.find("default", "bri");
        IndexMeta done = catalog.findIndexByName(table.id(), "idx_code");
        assertEquals(50, liveEntries(table, done, kv.delegate));
    }

    /** 可注入"下一次写入失败"的内存 store,模拟 BE 崩溃 / 连接中断。 */
    private static final class FailingStore implements com.wangyu.tangdb.storage.KvStore {
        private final FakeKvStore delegate = new FakeKvStore();
        private boolean armed;

        @Override
        public byte[] get(byte[] key) {
            return delegate.get(key);
        }

        @Override
        public void put(byte[] key, byte[] value) {
            if (armed) {
                armed = false;
                throw new StoreException("模拟 BE 连接中断");
            }
            delegate.put(key, value);
        }

        @Override
        public List<KeyValue> scan(byte[] start, byte[] end, int limit) {
            return delegate.scan(start, end, limit);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }
}
