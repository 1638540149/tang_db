package com.wangyu.tangdb.catalog;

import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.DataType;
import com.wangyu.tangdb.storage.RowCodec;
import com.wangyu.tangdb.storage.TableMeta;
import com.wangyu.tangdb.testing.FakeKvStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 多库命名空间:库内唯一 vs 表 ID 全局唯一、老目录宽容读、大小写(K69)、错误码(FR-416 ~ FR-430)。 */
class MultiDbTest {

    private static List<Column> cols(String... names) {
        List<Column> out = new java.util.ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            out.add(new Column(names[i], i == 0 ? DataType.BIGINT : DataType.VARCHAR,
                    i == 0 ? 0 : 32, i != 0, i == 0, i));
        }
        return out;
    }

    @Test
    void createAndShowDatabases() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        assertTrue(catalog.databases().contains("default"));

        catalog.createDatabase("shop");
        assertTrue(catalog.databases().contains("shop"));
        assertEquals(List.of("default", "shop"), catalog.databases());
    }

    @Test
    void createDatabaseRejectsDuplicateAndReserved() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        catalog.createDatabase("shop");

        CatalogException dup = assertThrows(CatalogException.class,
                () -> catalog.createDatabase("SHOP"));
        assertEquals(SqlError.ER_DB_CREATE_EXISTS, dup.code());
        assertTrue(dup.getMessage().contains("已存在"), dup.getMessage());

        CatalogException reserved = assertThrows(CatalogException.class,
                () -> catalog.createDatabase("sys_x"));
        assertTrue(reserved.getMessage().contains("保留名"), reserved.getMessage());
    }

    @Test
    void databaseNamesCaseInsensitive() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        catalog.createDatabase("Shop");
        assertTrue(catalog.databaseExists("shop"));
        assertTrue(catalog.databaseExists("SHOP"));
        catalog.requireDatabase("sHoP");
    }

    @Test
    void sameTableNameInDifferentDatabasesIsolation() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        catalog.createDatabase("shop");

        TableMeta defaultT = catalog.createTable("default", "t", cols("id", "a"));
        TableMeta shopT = catalog.createTable("shop", "t", cols("id", "b", "c"));

        assertNotEquals(defaultT.id(), shopT.id(), "表 ID 全局唯一(不按库分段)");
        assertEquals(2, catalog.require("default", "t").columnCount());
        assertEquals(3, catalog.require("shop", "t").columnCount());

        // SHOW TABLES 只列当前库
        assertEquals(1, catalog.tables("default").size());
        assertEquals(1, catalog.tables("shop").size());
        assertEquals("t", catalog.tables("shop").get(0).name());
    }

    @Test
    void dropDatabaseRejectsNonEmptyAndDefault() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        catalog.createDatabase("shop");
        catalog.createTable("shop", "t", cols("id", "a"));

        CatalogException nonEmpty = assertThrows(CatalogException.class,
                () -> catalog.dropDatabase("shop"));
        assertEquals(SqlError.ER_ROW_IS_REFERENCED_2, nonEmpty.code());
        assertTrue(nonEmpty.getMessage().contains("非空"), nonEmpty.getMessage());

        CatalogException def = assertThrows(CatalogException.class,
                () -> catalog.dropDatabase("default"));
        assertTrue(def.getMessage().contains("不可删除"), def.getMessage());

        // 删表后空库可删
        catalog.dropTable("shop", "t");
        catalog.dropDatabase("shop");
        assertFalse(catalog.databaseExists("shop"));
    }

    @Test
    void dropMissingDatabaseReports1008() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        CatalogException e = assertThrows(CatalogException.class,
                () -> catalog.dropDatabase("nosuchdb"));
        assertEquals(SqlError.ER_DB_DROP_EXISTS, e.code());
    }

    @Test
    void requireDatabaseReports1049() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        CatalogException e = assertThrows(CatalogException.class,
                () -> catalog.requireDatabase("nosuchdb"));
        assertEquals(SqlError.ER_BAD_DB_ERROR, e.code());
    }

    @Test
    void tableRequireReports1146() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        CatalogException e = assertThrows(CatalogException.class,
                () -> catalog.require("default", "nosuch"));
        assertEquals(SqlError.ER_NO_SUCH_TABLE, e.code());
    }

    @Test
    void legacySysTablesRowWithoutDbColumnToleratedAsDefault() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        TableMeta created = catalog.createTable("default", "legacy_t", cols("id", "a"));

        // 把 sys_tables 行改写成老 4 列形态(去掉 db_name),模拟 P3 老目录(FR-424 / 边界 5)。
        byte[] row = kv.get(SystemTables.tableKey(created.id()));
        Object[] v = RowCodec.decode(SystemTables.TABLES, row).values();
        kv.put(SystemTables.tableKey(created.id()),
                RowCodec.encode(SystemTables.TABLES_LEGACY, new Object[]{v[0], v[1], v[2], v[3]}));

        Catalog reopened = Catalog.open(kv);
        TableMeta loaded = reopened.require("default", "legacy_t");
        assertEquals("default", loaded.dbName());
        assertEquals(created.id(), loaded.id());
    }

    @Test
    void newDatabaseTableIdStartsAt64() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        catalog.createDatabase("shop");
        TableMeta t = catalog.createTable("shop", "t", cols("id"));
        assertEquals(64, t.id());
    }
}
