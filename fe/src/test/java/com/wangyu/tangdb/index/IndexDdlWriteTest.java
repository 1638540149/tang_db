package com.wangyu.tangdb.index;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.catalog.CatalogException;
import com.wangyu.tangdb.execute.Executor;
import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.parser.Parser;
import com.wangyu.tangdb.storage.IndexCodec;
import com.wangyu.tangdb.storage.IndexMeta;
import com.wangyu.tangdb.storage.KeyValue;
import com.wangyu.tangdb.storage.StoreException;
import com.wangyu.tangdb.storage.TableMeta;
import com.wangyu.tangdb.testing.FakeKvStore;
import com.wangyu.tangdb.testing.FakeTxnStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** SPEC-P4 A/B/C/D 组:索引 DDL、写入维护、唯一约束、读路径、EXPLAIN。 */
class IndexDdlWriteTest {

    private FakeKvStore kv;
    private Catalog catalog;
    private Executor executor;

    @BeforeEach
    void setUp() {
        kv = new FakeKvStore();
        catalog = Catalog.open(kv);
        executor = new Executor(kv, catalog);
        executor.execute(Parser.parse("CREATE TABLE t (id INT PRIMARY KEY, code VARCHAR(32), "
                + "name VARCHAR(32), amount BIGINT)"));
    }

    private Result run(String sql) {
        return executor.execute(Parser.parse(sql));
    }

    private Result.Table table(String sql) {
        return (Result.Table) run(sql);
    }

    private TableMeta meta() {
        return catalog.find("default", "t");
    }

    private IndexMeta index(String name) {
        return catalog.findIndexByName(meta().id(), name);
    }

    private int liveIndexEntries(IndexMeta index) {
        List<KeyValue> rows = kv.scan(IndexCodec.indexStart(meta().id(), index.indexId()),
                IndexCodec.indexEnd(meta().id(), index.indexId()), 0);
        int live = 0;
        for (KeyValue row : rows) {
            if (!IndexCodec.isTombstone(meta(), row.value())) {
                live++;
            }
        }
        return live;
    }

    private Set<Integer> ids(Result.Table result) {
        Set<Integer> ids = new HashSet<>();
        for (Object[] row : result.rows()) {
            ids.add(((Number) row[0]).intValue());
        }
        return ids;
    }

    private static String explain(Result.Table result) {
        StringBuilder sb = new StringBuilder();
        for (Object[] row : result.rows()) {
            sb.append(row[0]).append('\n');
        }
        return sb.toString();
    }

    @Test
    void createIndexOnEmptyTableIsActive() {
        Result.Message message = (Result.Message) run("CREATE INDEX idx_code ON t (code)");
        assertTrue(message.text().contains("ACTIVE"), message.text());
        IndexMeta index = index("idx_code");
        assertNotNull(index);
        assertEquals(IndexMeta.STATE_ACTIVE, index.state());
        assertEquals(1, index.indexId());
        assertFalse(index.unique());
    }

    @Test
    void insertUpdateDeleteMaintainSecondaryIndex() {
        run("CREATE INDEX idx_code ON t (code)");
        run("INSERT INTO t (id, code, name) VALUES (1, 'A', 'one'), (2, 'B', 'two')");
        assertEquals(2, liveIndexEntries(index("idx_code")));
        assertEquals(Set.of(1), ids(table("SELECT id FROM t WHERE code = 'A'")));

        run("UPDATE t SET code = 'C' WHERE id = 1");
        assertEquals(0, ids(table("SELECT id FROM t WHERE code = 'A'")).size());
        assertEquals(Set.of(1), ids(table("SELECT id FROM t WHERE code = 'C'")));
        // 旧索引项留墓碑,新索引项写入:活项仍是 2 条。
        assertEquals(2, liveIndexEntries(index("idx_code")));

        run("DELETE FROM t WHERE id = 2");
        assertEquals(0, ids(table("SELECT id FROM t WHERE code = 'B'")).size());
        assertEquals(1, liveIndexEntries(index("idx_code")));
        assertEquals(Set.of(1), ids(table("SELECT id FROM t")));
    }

    @Test
    void uniqueIndexBlocksDuplicatesWith1062AndKeepsRows() {
        run("CREATE UNIQUE INDEX uk_code ON t (code)");
        run("INSERT INTO t (id, code) VALUES (1, 'A001')");

        StoreException e = assertThrows(StoreException.class,
                () -> run("INSERT INTO t (id, code) VALUES (2, 'A001')"));
        assertEquals(SqlError.ER_DUP_ENTRY, e.code());
        assertTrue(e.getMessage().contains("uk_code"), e.getMessage());
        assertTrue(e.getMessage().contains("code"), e.getMessage());
        assertTrue(e.getMessage().contains("A001"), e.getMessage());
        assertTrue(e.getMessage().contains("主键 1"), e.getMessage());

        assertEquals(1, table("SELECT * FROM t").rows().size());
    }

    @Test
    void uniqueIndexAllowsMultipleNullsAndDropsRowWhenSetToNull() {
        run("CREATE UNIQUE INDEX uk_code ON t (code)");
        run("INSERT INTO t (id, code) VALUES (1, NULL), (2, NULL), (3, 'X')");
        assertEquals(3, table("SELECT * FROM t").rows().size());

        run("UPDATE t SET code = NULL WHERE id = 3");
        assertEquals(0, ids(table("SELECT id FROM t WHERE code = 'X'")).size());
        assertEquals(0, liveIndexEntries(index("uk_code"))); // 只剩墓碑,没有活项
    }

    @Test
    void uniqueIndexUpdateIntoExistingValueIsRejected() {
        run("CREATE UNIQUE INDEX uk_code ON t (code)");
        run("INSERT INTO t (id, code) VALUES (1, 'A'), (2, 'B')");
        StoreException e = assertThrows(StoreException.class,
                () -> run("UPDATE t SET code = 'A' WHERE id = 2"));
        assertEquals(SqlError.ER_DUP_ENTRY, e.code());
        assertEquals("B", table("SELECT code FROM t WHERE id = 2").rows().get(0)[0]);
    }

    @Test
    void uniqueIndexDetectsDuplicateInsideSameTransactionImmediately() {
        run("CREATE UNIQUE INDEX uk_code ON t (code)");
        run("BEGIN");
        run("INSERT INTO t (id, code) VALUES (1, 'A')");
        StoreException e = assertThrows(StoreException.class,
                () -> run("INSERT INTO t (id, code) VALUES (2, 'A')"));
        assertEquals(SqlError.ER_DUP_ENTRY, e.code());
        run("ROLLBACK");
        assertEquals(0, table("SELECT * FROM t").rows().size());
    }

    @Test
    void updateWithoutTouchingIndexColumnsWritesNoIndexEntries() {
        run("CREATE INDEX idx_code ON t (code)");
        run("INSERT INTO t (id, code, name) VALUES (1, 'A', 'one')");
        int before = kv.size();
        run("UPDATE t SET name = 'changed' WHERE id = 1");
        assertEquals(before, kv.size(), "未改索引列不应产生任何索引写入(FR-486)");
    }

    @Test
    void inlineIndexesAreCreatedWithTableAndAutoNamed() {
        Result.Message message = (Result.Message) run(
                "CREATE TABLE ti (id INT PRIMARY KEY, code VARCHAR(16), tag INT, "
                        + "UNIQUE(code), INDEX(tag))");
        assertTrue(message.text().contains("内联索引"), message.text());
        TableMeta table = catalog.find("default", "ti");
        List<IndexMeta> indexes = catalog.indexes(table.id());
        assertEquals(2, indexes.size());
        assertEquals("ti_code_uk", indexes.get(0).name());
        assertTrue(indexes.get(0).unique());
        assertEquals("ti_tag_idx", indexes.get(1).name());
        assertFalse(indexes.get(1).unique());

        run("INSERT INTO ti (id, code, tag) VALUES (1, 'A', 10)");
        StoreException e = assertThrows(StoreException.class,
                () -> run("INSERT INTO ti (id, code, tag) VALUES (2, 'A', 11)"));
        assertEquals(SqlError.ER_DUP_ENTRY, e.code());
    }

    @Test
    void inlineUniqueAndIndexOnSameColumnIsRejected() {
        CatalogException e = assertThrows(CatalogException.class,
                () -> run("CREATE TABLE bad (id INT PRIMARY KEY, code VARCHAR(8), "
                        + "UNIQUE(code), INDEX(code))"));
        assertTrue(e.getMessage().contains("多个索引"), e.getMessage());
        assertNull(catalog.find("default", "bad"));
    }

    @Test
    void ddlValidationErrorsAreReadable() {
        assertTrue(assertThrows(CatalogException.class,
                () -> run("CREATE INDEX idx_nope ON t (no_col)")).getMessage().contains("no_col"));
        assertTrue(assertThrows(CatalogException.class,
                () -> run("CREATE INDEX idx_pk ON t (id)")).getMessage().contains("主键列"));
        run("CREATE INDEX idx_code ON t (code)");
        assertTrue(assertThrows(CatalogException.class,
                () -> run("CREATE INDEX idx_code2 ON t (code)")).getMessage().contains("已有索引"));
        CatalogException dup = assertThrows(CatalogException.class,
                () -> run("CREATE INDEX idx_code ON t (name)"));
        assertEquals(SqlError.ER_DUP_KEYNAME, dup.code());
        assertTrue(assertThrows(CatalogException.class,
                () -> run("CREATE INDEX `bad name` ON t (name)")).getMessage().contains("索引名非法"));

        run("CREATE TABLE dbl (id INT PRIMARY KEY, score DOUBLE)");
        assertTrue(assertThrows(CatalogException.class,
                () -> run("CREATE INDEX idx_score ON dbl (score)")).getMessage().contains("不支持索引"));
    }

    @Test
    void dropIndexHidesItAndAllowsRecreateWithNewId() {
        run("CREATE INDEX idx_code ON t (code)");
        run("INSERT INTO t (id, code) VALUES (1, 'A')");
        int oldId = index("idx_code").indexId();
        run("DROP INDEX idx_code ON t");

        assertTrue(explain(table("EXPLAIN SELECT * FROM t WHERE code = 'A'"))
                .contains("TABLE SCAN"));
        assertEquals(Set.of(1), ids(table("SELECT id FROM t WHERE code = 'A'")));

        run("CREATE INDEX idx_code ON t (code)");
        IndexMeta recreated = index("idx_code");
        assertTrue(recreated.indexId() > oldId, "索引 ID 不复用");
        assertTrue(explain(table("EXPLAIN SELECT * FROM t WHERE code = 'A'"))
                .contains("INDEX POINT"));
    }

    @Test
    void buildingIndexFallsBackToTableScanWithoutError() {
        run("INSERT INTO t (id, code) VALUES (1, 'A'), (2, 'B')");
        // 直接建元数据但不回填:模拟回填中断后的 BUILDING 状态。
        IndexMeta building = catalog.createIndex("default", "t", "idx_code", "code", false);
        assertEquals(IndexMeta.STATE_BUILDING, building.state());

        assertTrue(explain(table("EXPLAIN SELECT * FROM t WHERE code = 'A'"))
                .contains("TABLE SCAN"));
        assertEquals(Set.of(1), ids(table("SELECT id FROM t WHERE code = 'A'")));
        assertEquals("BUILDING", table("SHOW INDEXES FROM t").rows().get(0)[3]);
    }

    @Test
    void orConditionDegradesToTableScanButStaysCorrect() {
        run("CREATE INDEX idx_code ON t (code)");
        run("INSERT INTO t (id, code) VALUES (1, 'A'), (2, 'B'), (3, 'C')");
        assertTrue(explain(table("EXPLAIN SELECT * FROM t WHERE code = 'A' OR code = 'B'"))
                .contains("TABLE SCAN"));
        assertEquals(Set.of(1, 2), ids(table("SELECT id FROM t WHERE code = 'A' OR code = 'B'")));
    }

    @Test
    void explainFivePaths() {
        run("CREATE INDEX idx_code ON t (code)");
        run("INSERT INTO t (id, code, name) VALUES (1, 'A', 'n1'), (2, 'B', 'n2')");
        assertTrue(explain(table("EXPLAIN SELECT * FROM t WHERE id = 1")).contains("PRIMARY POINT"));
        assertTrue(explain(table("EXPLAIN SELECT * FROM t WHERE id > 1")).contains("PRIMARY RANGE"));
        assertTrue(explain(table("EXPLAIN SELECT * FROM t WHERE code = 'A'")).contains("INDEX POINT"));
        assertTrue(explain(table("EXPLAIN SELECT * FROM t WHERE code > 'A'")).contains("INDEX RANGE"));
        assertTrue(explain(table("EXPLAIN SELECT * FROM t WHERE name = 'n1'")).contains("TABLE SCAN"));

        String plan = explain(table("EXPLAIN SELECT * FROM t WHERE code = 'A' AND name = 'n1'"));
        assertTrue(plan.contains("idx_code"), plan);
        assertTrue(plan.contains("key 范围"), plan);
        assertTrue(plan.contains("残余过滤"), plan);

        SqlError e = assertThrows(SqlError.class,
                () -> run("EXPLAIN INSERT INTO t (id) VALUES (9)"));
        assertEquals(SqlError.ER_NOT_SUPPORTED_YET, e.code());
    }

    @Test
    void limitStopsIndexScan() {
        run("CREATE INDEX idx_code ON t (code)");
        StringBuilder values = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            if (i > 1) {
                values.append(", ");
            }
            values.append("(").append(i).append(", 'A')");
        }
        run("INSERT INTO t (id, code) VALUES " + values);
        assertEquals(5, table("SELECT id FROM t WHERE code = 'A' LIMIT 5").rows().size());
    }

    @Test
    void indexRangeCoversInclusiveAndExclusiveBounds() {
        run("CREATE INDEX idx_code ON t (code)");
        run("INSERT INTO t (id, code) VALUES (1, 'a'), (2, 'b'), (3, 'c'), (4, 'd')");
        assertEquals(Set.of(2, 3, 4), ids(table("SELECT id FROM t WHERE code >= 'b'")));
        assertEquals(Set.of(3, 4), ids(table("SELECT id FROM t WHERE code > 'b'")));
        assertEquals(Set.of(1, 2), ids(table("SELECT id FROM t WHERE code < 'c'")));
        assertEquals(Set.of(1, 2, 3), ids(table("SELECT id FROM t WHERE code <= 'c'")));
        assertEquals(Set.of(2, 3), ids(table("SELECT id FROM t WHERE code > 'a' AND code < 'd'")));
    }

    @Test
    void indexPathAndTableScanAgreeRowByRow() {
        run("CREATE INDEX idx_code ON t (code)");
        for (int i = 1; i <= 200; i++) {
            run("INSERT INTO t (id, code, name) VALUES (" + i + ", 'v" + (i % 7) + "', 'n" + i + "')");
        }
        for (int v = 0; v < 7; v++) {
            Result.Table viaIndex = table("SELECT id FROM t WHERE code = 'v" + v + "'");
            // 加主键范围条件会把选择规则推回 PRIMARY RANGE(全表有序扫描 + FE 残余过滤)。
            Result.Table viaPrimaryRange = table(
                    "SELECT id FROM t WHERE code = 'v" + v + "' AND id >= -2147483648");
            assertEquals(new ArrayList<>(ids(viaPrimaryRange)).size(), viaIndex.rows().size());
            assertEquals(ids(viaPrimaryRange), ids(viaIndex));
        }
    }

    @Test
    void limitWorksOnIndexPath() {
        run("CREATE INDEX idx_code ON t (code)");
        run("INSERT INTO t (id, code) VALUES (1, 'A'), (2, 'A'), (3, 'A')");
        assertEquals(2, table("SELECT id FROM t WHERE code = 'A' LIMIT 2").rows().size());
    }

    // ------------------------------------------------------------ 事务可见性(场景 10)

    @Test
    void transactionVisibilityAndReadYourOwnWritesThroughIndex() {
        FakeTxnStore store = new FakeTxnStore();
        Catalog shared = Catalog.open(store);
        Executor a = new Executor(store, shared);
        Executor b = new Executor(store, shared);

        b.execute(Parser.parse("CREATE TABLE tx (id INT PRIMARY KEY, code VARCHAR(8))"));
        b.execute(Parser.parse("CREATE INDEX idx_code ON tx (code)"));
        b.execute(Parser.parse("INSERT INTO tx (id, code) VALUES (1, 'A'), (2, 'A')"));

        a.execute(Parser.parse("BEGIN"));
        a.execute(Parser.parse("UPDATE tx SET code = 'B' WHERE id = 1"));
        // 读己之写:事务内按索引能查到刚改但未提交的值。
        Result.Table own = (Result.Table) a.execute(Parser.parse("SELECT id FROM tx WHERE code = 'B'"));
        assertEquals(1, own.rows().size());

        // 其它会话:快照隔离,看到的仍是旧的两行。
        Result.Table otherBefore = (Result.Table) b.execute(Parser.parse("SELECT id FROM tx WHERE code = 'A'"));
        assertEquals(2, otherBefore.rows().size());

        a.execute(Parser.parse("COMMIT"));
        Result.Table afterA = (Result.Table) b.execute(Parser.parse("SELECT id FROM tx WHERE code = 'A'"));
        Result.Table afterB = (Result.Table) b.execute(Parser.parse("SELECT id FROM tx WHERE code = 'B'"));
        assertEquals(1, afterA.rows().size());
        assertEquals(1, afterB.rows().size());
    }

    @Test
    void concurrentUniqueInsertSecondTransactionGets1062() {
        FakeTxnStore store = new FakeTxnStore();
        Catalog shared = Catalog.open(store);
        Executor a = new Executor(store, shared);
        Executor b = new Executor(store, shared);
        Executor c = new Executor(store, shared);

        b.execute(Parser.parse("CREATE TABLE u (id INT PRIMARY KEY, code VARCHAR(8))"));
        b.execute(Parser.parse("CREATE UNIQUE INDEX uk_code ON u (code)"));

        a.execute(Parser.parse("BEGIN"));
        b.execute(Parser.parse("BEGIN"));
        a.execute(Parser.parse("INSERT INTO u (id, code) VALUES (1, 'X')"));
        b.execute(Parser.parse("INSERT INTO u (id, code) VALUES (2, 'X')"));
        a.execute(Parser.parse("COMMIT"));

        StoreException e = assertThrows(StoreException.class,
                () -> b.execute(Parser.parse("COMMIT")));
        assertEquals(SqlError.ER_DUP_ENTRY, e.code());
        assertTrue(e.getMessage().contains("唯一索引"), e.getMessage());
        // 用第三个会话看已提交数据:恰好一行,唯一约束没有被并发击穿。
        assertEquals(1, ((Result.Table) c.execute(Parser.parse("SELECT * FROM u"))).rows().size());
        b.execute(Parser.parse("ROLLBACK"));
    }
}
