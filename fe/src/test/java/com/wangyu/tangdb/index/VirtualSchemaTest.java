package com.wangyu.tangdb.index;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.execute.Executor;
import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.execute.SqlEngine;
import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.parser.Parser;
import com.wangyu.tangdb.server.BackendRegistry;
import com.wangyu.tangdb.session.Session;
import com.wangyu.tangdb.session.SessionManager;
import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.TableMeta;
import com.wangyu.tangdb.testing.FakeKvStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** SPEC-P4 F 组 / FR-527 ~ FR-548 / §5.7:SHOW 家族 + information_schema 虚拟表。 */
class VirtualSchemaTest {

    private static final List<String> VIRTUAL_TABLES = List.of(
            "SCHEMATA", "TABLES", "COLUMNS", "STATISTICS", "KEY_COLUMN_USAGE",
            "TABLE_CONSTRAINTS", "REFERENTIAL_CONSTRAINTS", "VIEWS", "ROUTINES", "TRIGGERS",
            "EVENTS", "PROCESSLIST", "CHARACTER_SETS", "COLLATIONS", "ENGINES", "PARTITIONS",
            "USER_PRIVILEGES");

    private FakeKvStore kv;
    private Catalog catalog;
    private SessionManager sessions;
    private SqlEngine engine;
    private Session session;

    @BeforeEach
    void setUp() {
        kv = new FakeKvStore();
        catalog = Catalog.open(kv);
        sessions = new SessionManager(16, 0);
        engine = new SqlEngine(kv, catalog, sessions, new BackendRegistry(3), false, "root");
        session = sessions.create("127.0.0.1:1", new Executor(kv, catalog));
        engine.execute(session, "CREATE TABLE t (id INT PRIMARY KEY, code VARCHAR(16), name VARCHAR(16))");
        engine.execute(session, "CREATE UNIQUE INDEX uk_code ON t (code)");
        engine.execute(session, "CREATE INDEX idx_name ON t (name)");
        engine.execute(session, "INSERT INTO t (id, code, name) VALUES (1, 'A', 'n1'), (2, 'B', 'n2')");
    }

    private Result.Table table(String sql) {
        return (Result.Table) engine.execute(session, sql);
    }

    private int column(Result.Table table, String name) {
        return table.columns().indexOf(name);
    }

    @Test
    void allSeventeenVirtualTablesAreQueryable() {
        for (String name : VIRTUAL_TABLES) {
            Result.Table result = table("SELECT * FROM information_schema." + name);
            assertFalse(result.columns().isEmpty(), name + " 列定义不能为空");
        }
    }

    @Test
    void projectionIsTrimmedAndWhereLimitWork() {
        Result.Table schemata = table(
                "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA");
        assertEquals(List.of("SCHEMA_NAME"), schemata.columns());
        assertTrue(schemata.rows().stream().anyMatch(r -> "default".equals(r[0])));

        Result.Table filtered = table(
                "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = 'default'");
        assertEquals(1, filtered.rows().size());

        Result.Table limited = table("SELECT TABLE_NAME FROM information_schema.TABLES LIMIT 1");
        assertEquals(1, limited.rows().size());

        // 分页(FR-549 ~ FR-552):虚拟表同样先过滤、再跳 offset、再截断。
        assertEquals(1, table("SELECT TABLE_NAME FROM information_schema.TABLES LIMIT 0, 1")
                .rows().size());
        assertEquals(0, table("SELECT TABLE_NAME FROM information_schema.TABLES LIMIT 1, 5")
                .rows().size());
        assertEquals(0, table("SELECT TABLE_NAME FROM information_schema.TABLES LIMIT 0, 0")
                .rows().size());

        Result.Table columns = table("SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_NAME = 't'");
        assertEquals(3, columns.rows().size());
        assertEquals(List.of("COLUMN_NAME"), columns.columns());
    }

    @Test
    void singleColumnOrderByAndCountStarWorkOnVirtualTables() {
        Result.Table ordered = table(
                "SELECT TABLE_NAME FROM information_schema.TABLES ORDER BY TABLE_NAME DESC");
        List<Object> names = new ArrayList<>();
        for (Object[] row : ordered.rows()) {
            names.add(row[0]);
        }
        List<Object> sorted = new ArrayList<>(names);
        sorted.sort(java.util.Comparator.comparing(Object::toString).reversed());
        assertEquals(sorted, names);

        Result.Table count = table("SELECT COUNT(*) FROM information_schema.TABLES");
        assertEquals(List.of("COUNT(*)"), count.columns());
        assertEquals(1, count.rows().size());
        assertEquals(1L, count.rows().get(0)[0]);

        Result.Table countWhere = table(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_NAME = 't' AND NON_UNIQUE = 0");
        assertEquals(2L, countWhere.rows().get(0)[0]);
    }

    @Test
    void multiColumnOrderByOnVirtualTables() {
        engine.execute(session, "CREATE TABLE t2 (id INT PRIMARY KEY, code VARCHAR(8))");

        // 前一列相等才看下一列(K99);两列方向可独立。
        Result.Table mixed = table("SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = 'default' AND TABLE_NAME IN ('t', 't2') "
                + "ORDER BY TABLE_NAME DESC, ORDINAL_POSITION DESC");
        assertEquals(List.of(
                List.of("t2", "code"), List.of("t2", "id"),
                List.of("t", "name"), List.of("t", "code"), List.of("t", "id")),
                rows(mixed));

        // 三列 ASC:表内按 ORDINAL_POSITION 升序。
        Result.Table asc = table("SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = 'default' AND TABLE_NAME = 't' "
                + "ORDER BY TABLE_SCHEMA, TABLE_NAME, ORDINAL_POSITION");
        assertEquals(List.of("id", "code", "name"), secondColumn(asc));
    }

    private static List<List<Object>> rows(Result.Table table) {
        List<List<Object>> out = new ArrayList<>(table.rows().size());
        for (Object[] row : table.rows()) {
            out.add(List.of(row));
        }
        return out;
    }

    private static List<Object> secondColumn(Result.Table table) {
        List<Object> out = new ArrayList<>(table.rows().size());
        for (Object[] row : table.rows()) {
            out.add(row[1]);
        }
        return out;
    }

    @Test
    void unsupportedQueryShapesOnInformationSchemaReturn1235() {
        for (String sql : List.of(
                "SELECT * FROM information_schema.TABLES JOIN information_schema.COLUMNS",
                "SELECT DISTINCT TABLE_NAME FROM information_schema.TABLES")) {
            SqlError e = assertThrows(SqlError.class, () -> engine.execute(session, sql), sql);
            assertEquals(SqlError.ER_NOT_SUPPORTED_YET, e.code(), sql);
        }
    }

    @Test
    void orderByAndCountOnUserTablesWorkSinceP5() {
        // SPEC-P5 §2.2 / §10 规约回填:用户表 ORDER BY 与 COUNT(*) 从本期起真支持。
        Result.Table ordered = (Result.Table) engine.execute(session,
                "SELECT id FROM t ORDER BY id DESC");
        assertEquals(List.of(2, 1), List.of(
                ((Number) ordered.rows().get(0)[0]).intValue(),
                ((Number) ordered.rows().get(1)[0]).intValue()));

        Result.Table count = (Result.Table) engine.execute(session,
                "SELECT COUNT(*) AS c FROM t");
        assertEquals(2L, count.rows().get(0)[0]);
    }

    @Test
    void statisticsAndConstraintsComeFromIndexMetadata() {
        Result.Table stats = table("SELECT INDEX_NAME, NON_UNIQUE, CARDINALITY, INDEX_TYPE "
                + "FROM information_schema.STATISTICS WHERE TABLE_NAME = 't'");
        assertEquals(3, stats.rows().size());
        boolean sawPrimary = false;
        boolean sawUnique = false;
        boolean sawSecondary = false;
        for (Object[] row : stats.rows()) {
            assertNull(row[2], "Cardinality 本期恒 NULL");
            assertEquals("BTREE", row[3]);
            if ("PRIMARY".equals(row[0])) {
                sawPrimary = true;
                assertEquals(0L, row[1]);
            } else if ("uk_code".equals(row[0])) {
                sawUnique = true;
                assertEquals(0L, row[1]);
            } else if ("idx_name".equals(row[0])) {
                sawSecondary = true;
                assertEquals(1L, row[1]);
            }
        }
        assertTrue(sawPrimary && sawUnique && sawSecondary);

        Result.Table constraints = table("SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE "
                + "FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_NAME = 't'");
        assertEquals(2, constraints.rows().size());
        List<String> types = new ArrayList<>();
        for (Object[] row : constraints.rows()) {
            types.add((String) row[1]);
        }
        assertTrue(types.contains("PRIMARY KEY"));
        assertTrue(types.contains("UNIQUE"));
    }

    @Test
    void showIndexSupportsWhereFiltering() {
        // FR-562:SHOW INDEX|KEYS|INDEXES 的 WHERE 走 SHOW 输出通用过滤路径。
        Result.Table primary = table("SHOW INDEX FROM t WHERE Key_name = 'PRIMARY'");
        assertEquals(1, primary.rows().size());
        assertEquals("PRIMARY", primary.rows().get(0)[column(primary, "Key_name")]);

        Result.Table unique = table("SHOW INDEX FROM t WHERE Non_unique = 0");
        assertEquals(2, unique.rows().size()); // PRIMARY + uk_code
        assertEquals("PRIMARY", unique.rows().get(0)[column(unique, "Key_name")]);
        assertEquals("uk_code", unique.rows().get(1)[column(unique, "Key_name")]);

        Result.Table keys = table("SHOW KEYS FROM t WHERE Key_name = 'no_such_index'");
        assertTrue(keys.rows().isEmpty());
        assertEquals(13, keys.columns().size());

        Result.Table indexes = table("SHOW INDEXES FROM t WHERE index_name = 'idx_name'");
        assertEquals(1, indexes.rows().size());
        assertEquals("idx_name", indexes.rows().get(0)[0]);
        assertEquals("ACTIVE", indexes.rows().get(0)[3]);
    }

    @Test
    void showFamilyCoverage() {
        Result.Table fullTables = table("SHOW FULL TABLES");
        assertEquals(2, fullTables.columns().size());
        assertTrue(fullTables.columns().get(1).equalsIgnoreCase("Table_type"));
        assertEquals("BASE TABLE", fullTables.rows().get(0)[1]);

        assertEquals(0, table("SHOW TABLES LIKE 'zzz%'").rows().size());

        Result.Table status = table("SHOW TABLE STATUS");
        assertEquals("InnoDB", status.rows().get(0)[column(status, "Name") + 1]);
        assertNull(status.rows().get(0)[column(status, "Rows")]);

        Result.Table showColumns = table("SHOW COLUMNS FROM t");
        assertEquals(List.of("Field", "Type", "Collation", "Null", "Key", "Default", "Extra",
                "Privileges", "Comment"), showColumns.columns());
        assertEquals("PRI", showColumns.rows().get(0)[column(showColumns, "Key")]);
        assertEquals("UNI", showColumns.rows().get(1)[column(showColumns, "Key")]);
        assertEquals(9, table("SHOW FULL COLUMNS FROM t").columns().size());

        Result.Table showIndex = table("SHOW INDEX FROM t");
        assertEquals(13, showIndex.columns().size());
        assertEquals("PRIMARY", showIndex.rows().get(0)[column(showIndex, "Key_name")]);
        assertEquals("uk_code", showIndex.rows().get(1)[column(showIndex, "Key_name")]);

        Result.Table showIndexes = table("SHOW INDEXES FROM t");
        assertEquals(List.of("index_name", "column_name", "is_unique", "state", "index_id",
                "build_cursor"), showIndexes.columns());
        assertEquals(2, showIndexes.rows().size());

        Result.Table engines = table("SHOW ENGINES");
        assertEquals("InnoDB", engines.rows().get(0)[0]);
        assertEquals("DEFAULT", engines.rows().get(0)[1]);

        assertTrue(table("SHOW COLLATION").rows().size() >= 1);
        assertEquals(1, table("SHOW CHARACTER SET").rows().size());
        assertEquals(1, table("SHOW GRANTS").rows().size());
        assertTrue(table("SHOW WARNINGS").rows().isEmpty());
        assertTrue(table("SHOW ERRORS").rows().isEmpty());
        assertTrue(table("SHOW TRIGGERS").rows().isEmpty());
        assertTrue(table("SHOW EVENTS").rows().isEmpty());
        assertTrue(table("SHOW ROUTINES").rows().isEmpty());

        // FR-559 / H-6 / SC-346:SHOW PROCEDURE|FUNCTION|FUNC STATUS 恒为 11 列空集,
        // LIKE / WHERE 无匹配也只返回空集(边界 38),不报 1235。
        List<String> routineColumns = List.of("Db", "Name", "Type", "Definer", "Modified",
                "Created", "Security_type", "Comment", "character_set_client",
                "collation_connection", "Database Collation");
        for (String sql : List.of("SHOW PROCEDURE STATUS", "SHOW FUNCTION STATUS",
                "SHOW FUNC STATUS", "SHOW PROCEDURE STATUS WHERE Db = 'default'",
                "SHOW PROCEDURE STATUS WHERE Db = 'no_such_db'",
                "SHOW FUNCTION STATUS LIKE 'no_such%'")) {
            Result.Table routineStatus = table(sql);
            assertEquals(routineColumns, routineStatus.columns(), sql);
            assertTrue(routineStatus.rows().isEmpty(), sql);
        }

        Result.Table variables = table("SHOW VARIABLES");
        assertTrue(variables.columns().containsAll(List.of("Variable_name", "Value")));
        assertTrue(table("SHOW VARIABLES LIKE 'version'").rows().size() >= 1);
        assertTrue(table("SHOW STATUS").rows().size() >= 1);
        assertEquals(8, table("SHOW PROCESSLIST").columns().size());
        assertEquals(9, table("SHOW FULL PROCESSLIST").columns().size());
        assertTrue(table("SHOW FULL PROCESSLIST").rows().size() >= 1);
    }

    @Test
    void showCreateTableRoundTripIsExecutable() {
        Result.Table create = table("SHOW CREATE TABLE t");
        assertEquals(List.of("Table", "Create Table"), create.columns());
        String ddl = (String) create.rows().get(0)[1];
        assertTrue(ddl.contains("UNIQUE KEY `uk_code`"), ddl);
        assertTrue(ddl.contains("KEY `idx_name`"), ddl);

        String ddl2 = ddl.replaceFirst("CREATE TABLE `t`", "CREATE TABLE `t2`");
        engine.execute(session, ddl2);

        TableMeta original = catalog.find("default", "t");
        TableMeta copy = catalog.find("default", "t2");
        assertNotNull(copy, "SHOW CREATE TABLE 的输出必须能被 parser 吃回去");
        assertEquals(original.columnCount(), copy.columnCount());
        assertEquals(original.primaryKey().name(), copy.primaryKey().name());
        assertEquals(2, catalog.indexes(copy.id()).size());
        assertEquals(original.columns().get(0).type(), copy.columns().get(0).type());

        Result.Table createDb = table("SHOW CREATE DATABASE default");
        assertEquals(2, createDb.columns().size());
        assertEquals("default", createDb.rows().get(0)[0]);
    }

    @Test
    void showCreateTableMissingTableIs1146() {
        com.wangyu.tangdb.catalog.CatalogException e =
                assertThrows(com.wangyu.tangdb.catalog.CatalogException.class,
                        () -> engine.execute(session, "SHOW CREATE TABLE no_such"));
        assertEquals(SqlError.ER_NO_SUCH_TABLE, e.code());
        assertTrue(e.getMessage().contains("no_such"), e.getMessage());
    }

    @Test
    void killExistingSessionRemovesItAndUnknownIdReportsError() {
        Session victim = sessions.create("127.0.0.1:2", new Executor(kv, catalog));
        Result.Message message = (Result.Message) engine.execute(session, "KILL " + victim.id());
        assertTrue(message.text().contains("已断开"), message.text());
        assertNull(sessions.get(victim.id()));

        SqlError killed = assertThrows(SqlError.class,
                () -> engine.execute(victim, "SHOW DATABASES"));
        assertEquals(SqlError.ER_QUERY_INTERRUPTED, killed.code(),
                "被 KILL 的会话在下一条语句上必须被拒绝");

        SqlError e = assertThrows(SqlError.class,
                () -> engine.execute(session, "KILL 99999"));
        assertEquals(SqlError.ER_NO_SUCH_THREAD, e.code());
        // 自己的连接不受影响。
        assertEquals(2, table("SELECT * FROM t").rows().size());
    }

    @Test
    void unsupportedShowDoesNotBreakConnection() {
        SqlError e = assertThrows(SqlError.class,
                () -> engine.execute(session, "SHOW SLAVE STATUS"));
        assertEquals(SqlError.ER_NOT_SUPPORTED_YET, e.code());
        assertEquals(2, table("SELECT * FROM t").rows().size());
    }

    @Test
    void showTablesFromDatabaseMatchesInformationSchema() {
        engine.execute(session, "CREATE DATABASE shop");
        engine.execute(session, "CREATE TABLE shop.orders (id INT PRIMARY KEY)");

        Result.Table showTables = table("SHOW TABLES FROM shop");
        assertEquals(1, showTables.rows().size());
        assertEquals("orders", showTables.rows().get(0)[0]);

        Result.Table info = table("SELECT TABLE_NAME FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = 'shop'");
        assertEquals(1, info.rows().size());
        assertEquals("orders", info.rows().get(0)[0]);
    }

    @Test
    void columnTypeAndNullabilityAreExposed() {
        Result.Table columns = table("SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, ORDINAL_POSITION "
                + "FROM information_schema.COLUMNS WHERE TABLE_NAME = 't' ORDER BY COLUMN_NAME");
        assertEquals(3, columns.rows().size());
        for (Object[] row : columns.rows()) {
            if ("id".equals(row[0])) {
                assertEquals("int", row[1]);
                assertEquals("NO", row[2]);
                assertEquals(1L, row[3]);
            }
        }
        assertTrue(columns.rows().stream().anyMatch(r -> "code".equals(r[0])));
    }

    @Test
    void emptyDatabaseTableTreeReturnsEmptySetNotError() {
        engine.execute(session, "CREATE DATABASE empty_db");
        Result.Table full = table("SHOW FULL TABLES FROM empty_db");
        assertTrue(full.rows().isEmpty());
        Result.Table info = table("SELECT TABLE_NAME FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = 'empty_db'");
        assertTrue(info.rows().isEmpty());
    }

    @Test
    void showColumnsOnUnknownTableIs1146() {
        com.wangyu.tangdb.catalog.CatalogException e =
                assertThrows(com.wangyu.tangdb.catalog.CatalogException.class,
                        () -> engine.execute(session, "SHOW COLUMNS FROM ghost"));
        assertEquals(SqlError.ER_NO_SUCH_TABLE, e.code());
    }

    @Test
    void informationSchemaDoesNotTouchBackend() {
        com.wangyu.tangdb.storage.KvStore offline = new com.wangyu.tangdb.storage.KvStore() {
            @Override
            public byte[] get(byte[] key) {
                throw new AssertionError("虚拟表不得访问 BE(FR-540)");
            }

            @Override
            public void put(byte[] key, byte[] value) {
                throw new AssertionError("虚拟表不得访问 BE(FR-540)");
            }

            @Override
            public List<com.wangyu.tangdb.storage.KeyValue> scan(byte[] start, byte[] end, int limit) {
                throw new AssertionError("虚拟表不得访问 BE(FR-540)");
            }
        };
        SqlEngine offlineEngine = new SqlEngine(offline, catalog, sessions,
                new BackendRegistry(3), false, "root");
        // session = null → SqlEngine 每次新建 Executor,用的是这个"禁止访问 BE"的 store。
        Result.Table schemata = (Result.Table) offlineEngine.execute(null,
                "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA");
        assertTrue(schemata.rows().size() >= 1);
        Result.Table tables = (Result.Table) offlineEngine.execute(null, "SHOW TABLES");
        assertFalse(tables.rows().isEmpty());
    }
}
