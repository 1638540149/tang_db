package com.wangyu.tangdb.catalog;

import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.DataType;
import com.wangyu.tangdb.storage.KeyValue;
import com.wangyu.tangdb.storage.KvStore;
import com.wangyu.tangdb.storage.TableMeta;
import com.wangyu.tangdb.testing.FakeKvStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** catalog 的加载、校验、DDL 持久化测试(FR-110 ~ FR-117)。 */
class CatalogTest {

    private static List<Column> userColumns() {
        return List.of(
                new Column("id", DataType.BIGINT, 0, false, true, 0),
                new Column("name", DataType.VARCHAR, 32, true, false, 1),
                new Column("age", DataType.INT, 0, true, false, 2));
    }

    @Test
    void bootstrapIsIdempotentAndHidesSystemTables() {
        FakeKvStore kv = new FakeKvStore();
        Catalog first = Catalog.open(kv);
        assertTrue(first.tables().isEmpty(), "系统表不应出现在 SHOW TABLES 里");
        int sizeAfterFirst = kv.size();
        assertTrue(sizeAfterFirst > 0);

        Catalog second = Catalog.open(kv);
        assertEquals(sizeAfterFirst, kv.size(), "再次启动不应重复写系统表");
        assertTrue(second.tables().isEmpty());
        assertEquals(64, second.nextTableId());
    }

    @Test
    void createTablePersistsAndReloads() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        TableMeta users = catalog.createTable("Users", userColumns());
        assertEquals(64, users.id());
        assertEquals("users", users.name(), "表名应统一转小写");
        assertEquals(65, catalog.nextTableId());

        Catalog reloaded = Catalog.open(kv);
        TableMeta loaded = reloaded.require("USERS");
        assertEquals(64, loaded.id());
        assertEquals(3, loaded.columnCount());
        assertEquals("id", loaded.primaryKey().name());
        assertEquals(DataType.BIGINT, loaded.primaryKey().type());
    }

    @Test
    void dropKeepsIdAndNameCanBeReused() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        TableMeta first = catalog.createTable("t", userColumns());
        catalog.dropTable("t");

        assertNull(catalog.find("t"));
        assertThrows(CatalogException.class, () -> catalog.require("t"));
        assertEquals(TableMeta.STATE_DROPPED, catalog.findById(first.id()).state());

        TableMeta second = catalog.createTable("t", userColumns());
        assertEquals(first.id() + 1, second.id(), "表 ID 不复用");
        assertEquals(second.id(), Catalog.open(kv).require("t").id());
    }

    @Test
    void truncateSwitchesToNewTableId() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        TableMeta old = catalog.createTable("t", userColumns());
        TableMeta fresh = catalog.truncateTable("t");

        assertNotEquals(old.id(), fresh.id());
        assertEquals(fresh.id(), catalog.find("t").id());
        assertEquals(TableMeta.STATE_DROPPED, catalog.findById(old.id()).state());
        assertEquals(fresh.id(), Catalog.open(kv).require("t").id());
    }

    @Test
    void createTableWritesAllMetadataInOneBatch() {
        FakeKvStore delegate = new FakeKvStore();
        int[] batches = {0};
        int[] rowsInBatch = {0};
        KvStore kv = new KvStore() {
            @Override
            public byte[] get(byte[] key) {
                return delegate.get(key);
            }

            @Override
            public void put(byte[] key, byte[] value) {
                delegate.put(key, value);
            }

            @Override
            public java.util.List<KeyValue> scan(byte[] start, byte[] end, int limit) {
                return delegate.scan(start, end, limit);
            }

            @Override
            public void writeBatch(java.util.List<KeyValue> writes) {
                batches[0]++;
                rowsInBatch[0] = writes.size();
                delegate.writeBatch(writes);
            }
        };

        Catalog catalog = Catalog.open(kv);
        catalog.createTable("t", userColumns());

        assertEquals(1, batches[0], "建表的元数据必须只走一条批记录");
        // 表记录 1 条 + 列记录 3 条 + 计数器 1 条
        assertEquals(5, rowsInBatch[0]);
    }

    @Test
    void truncateWritesAllMetadataInOneBatch() {
        FakeKvStore delegate = new FakeKvStore();
        int[] batches = {0};
        KvStore kv = new KvStore() {
            @Override
            public byte[] get(byte[] key) {
                return delegate.get(key);
            }

            @Override
            public void put(byte[] key, byte[] value) {
                delegate.put(key, value);
            }

            @Override
            public java.util.List<KeyValue> scan(byte[] start, byte[] end, int limit) {
                return delegate.scan(start, end, limit);
            }

            @Override
            public void writeBatch(java.util.List<KeyValue> writes) {
                batches[0]++;
                delegate.writeBatch(writes);
            }
        };
        Catalog catalog = Catalog.open(kv);
        catalog.createTable("t", userColumns());
        int before = batches[0];
        catalog.truncateTable("t");
        assertEquals(before + 1, batches[0], "TRUNCATE 的元数据必须只走一条批记录");
    }

    @Test
    void rejectsDuplicateTableAndInvalidColumns() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        catalog.createTable("t", userColumns());

        assertTrue(assertThrows(CatalogException.class,
                () -> catalog.createTable("T", userColumns())).getMessage().contains("已存在"));

        assertTrue(assertThrows(CatalogException.class,
                () -> catalog.createTable("x", List.of(
                        new Column("a", DataType.INT, 0, false, false, 0))))
                .getMessage().contains("必须有一个主键"));

        assertTrue(assertThrows(CatalogException.class,
                () -> catalog.createTable("y", List.of(
                        new Column("a", DataType.DOUBLE, 0, false, true, 0))))
                .getMessage().contains("主键类型不支持"));

        assertTrue(assertThrows(CatalogException.class,
                () -> catalog.createTable("z", List.of(
                        new Column("a", DataType.INT, 0, false, true, 0),
                        new Column("A", DataType.INT, 0, true, false, 1))))
                .getMessage().contains("列名重复"));
    }

    @Test
    void missingColumnDefinitionFailsStartupWithTableName() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        TableMeta t = catalog.createTable("broken", List.of(
                new Column("id", DataType.BIGINT, 0, false, true, 0)));

        kv.remove(SystemTables.columnKey(t.id(), 0));

        CatalogException e = assertThrows(CatalogException.class, () -> Catalog.open(kv));
        assertTrue(e.getMessage().contains("broken"), e.getMessage());
        assertTrue(e.getMessage().contains("缺少列定义"), e.getMessage());
    }

    @Test
    void missingPrimaryKeyFailsStartup() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        TableMeta t = catalog.createTable("nopk", userColumns());

        Column notPk = new Column("id", DataType.BIGINT, 0, true, false, 0);
        kv.put(SystemTables.columnKey(t.id(), 0), SystemTables.encodeColumnRow(notPk, t.id()));

        CatalogException e = assertThrows(CatalogException.class, () -> Catalog.open(kv));
        assertTrue(e.getMessage().contains("nopk"), e.getMessage());
        assertTrue(e.getMessage().contains("没有主键"), e.getMessage());
    }

    @Test
    void badCounterFailsStartup() {
        FakeKvStore kv = new FakeKvStore();
        Catalog.open(kv);
        kv.put(SystemTables.counterKey(SystemTables.COUNTER_NEXT_TABLE_ID),
                SystemTables.encodeCounterRow(SystemTables.COUNTER_NEXT_TABLE_ID, 3));

        CatalogException e = assertThrows(CatalogException.class, () -> Catalog.open(kv));
        assertTrue(e.getMessage().contains("next_table_id"), e.getMessage());
        assertTrue(e.getMessage().contains("5"), e.getMessage());
    }

    @Test
    void counterBehindMaxTableIdFailsStartup() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        catalog.createTable("t", userColumns());
        TableMeta u = catalog.createTable("u", userColumns());

        // 把计数器改小到已分配的表 ID,继续用会复用 ID
        kv.put(SystemTables.counterKey(SystemTables.COUNTER_NEXT_TABLE_ID),
                SystemTables.encodeCounterRow(SystemTables.COUNTER_NEXT_TABLE_ID, u.id()));

        CatalogException e = assertThrows(CatalogException.class, () -> Catalog.open(kv));
        assertTrue(e.getMessage().contains("表 ID 复用"), e.getMessage());
    }
}
