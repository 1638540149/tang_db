package com.wangyu.tangdb.execute;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.server.BackendRegistry;
import com.wangyu.tangdb.session.Session;
import com.wangyu.tangdb.session.SessionManager;
import com.wangyu.tangdb.testing.FakeKvStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 共享执行入口:连接期兼容白名单、information_schema 三视图、SHOW SESSIONS(FR-447 / §5.5 / I9)。 */
class SqlEngineTest {

    private FakeKvStore kv;
    private Catalog catalog;
    private SessionManager sessions;
    private SqlEngine engine;
    private Session session;

    @BeforeEach
    void setUp() {
        kv = new FakeKvStore();
        catalog = Catalog.open(kv);
        sessions = new SessionManager(4, 0);
        engine = new SqlEngine(kv, catalog, sessions, new BackendRegistry(3), false, "root");
        session = sessions.create("test", new Executor(kv, catalog));
    }

    @Test
    void selectVersionReturnsK64() {
        Result.Table table = (Result.Table) engine.execute(session, "SELECT @@version");
        assertEquals("5.7.99-tangdb", table.rows().get(0)[0]);
    }

    @Test
    void leadingBlockCommentIsTolerated() {
        // 官方 Connector/J 连接时会先发 /* 版本注释 */ 再接 SELECT,必须容错。
        Result.Table table = (Result.Table) engine.execute(session,
                "/* mysql-connector-java-8.0.33 */ SELECT @@version");
        assertEquals("5.7.99-tangdb", table.rows().get(0)[0]);

        assertInstanceOf(Result.Message.class, engine.execute(session, "/* only comment */"));
    }

    @Test
    void setStatementsAreAccepted() {
        assertInstanceOf(Result.Message.class, engine.execute(session, "SET NAMES utf8mb4"));
        assertInstanceOf(Result.Message.class, engine.execute(session, "SET autocommit = 0"));
        assertInstanceOf(Result.Message.class,
                engine.execute(session, "SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ"));
    }

    @Test
    void selectDatabaseFunctionReturnsCurrentDb() {
        Result.Table table = (Result.Table) engine.execute(session, "SELECT DATABASE()");
        assertEquals("default", table.rows().get(0)[0]);
    }

    @Test
    void showVariablesReturnsEmptyResultSet() {
        Result.Table table = (Result.Table) engine.execute(session, "SHOW VARIABLES LIKE 'x'");
        assertEquals(List.of("Variable_name", "Value"), table.columns());
        assertTrue(table.rows().isEmpty());
    }

    @Test
    void showSessionsListsRegisteredSessions() {
        sessions.create("other", new Executor(kv, catalog));
        Result.Table table = (Result.Table) engine.execute(session, "SHOW SESSIONS");
        assertEquals(2, table.rows().size());
        assertEquals(List.of("session_id", "source", "database", "in_txn", "started_at"), table.columns());
    }

    @Test
    void informationSchemaSchemataView() {
        catalog.createDatabase("shop");
        Result.Table table = (Result.Table) engine.execute(session,
                "SELECT * FROM information_schema.schemata");
        List<String> columns = table.columns();
        assertTrue(columns.contains("SCHEMA_NAME"));
        int idx = columns.indexOf("SCHEMA_NAME");
        java.util.Set<String> schemas = new java.util.HashSet<>();
        for (Object[] row : table.rows()) {
            schemas.add((String) row[idx]);
        }
        assertTrue(schemas.contains("default"));
        assertTrue(schemas.contains("shop"));
    }

    @Test
    void informationSchemaTablesAndColumnsViews() {
        catalog.createTable("default", "t", List.of(
                new com.wangyu.tangdb.storage.Column("id", com.wangyu.tangdb.storage.DataType.BIGINT,
                        0, false, true, 0)));

        Result.Table tables = (Result.Table) engine.execute(session,
                "SELECT * FROM information_schema.tables");
        int nameIdx = tables.columns().indexOf("TABLE_NAME");
        boolean sawT = tables.rows().stream().anyMatch(r -> "t".equals(r[nameIdx]));
        assertTrue(sawT);

        Result.Table columns = (Result.Table) engine.execute(session,
                "SELECT * FROM information_schema.columns");
        int colIdx = columns.columns().indexOf("COLUMN_NAME");
        boolean sawId = columns.rows().stream().anyMatch(r -> "id".equals(r[colIdx]));
        assertTrue(sawId);
    }

    @Test
    void informationSchemaUnknownTableRejectedWith1235() {
        SqlError e = assertThrows(SqlError.class,
                () -> engine.execute(session, "SELECT * FROM information_schema.partitions"));
        assertEquals(SqlError.ER_NOT_SUPPORTED_YET, e.code());
    }

    @Test
    void multiDatabaseThroughEngine() {
        engine.execute(session, "CREATE DATABASE shop");
        engine.execute(session, "USE shop");
        engine.execute(session, "CREATE TABLE orders (id BIGINT PRIMARY KEY)");

        Result.Table tables = (Result.Table) engine.execute(session, "SHOW TABLES");
        assertEquals(1, tables.rows().size());
        assertEquals("orders", tables.rows().get(0)[0]);

        // 切回 default 看不到 shop 的表
        engine.execute(session, "USE default");
        Result.Table defaultTables = (Result.Table) engine.execute(session, "SHOW TABLES");
        assertTrue(defaultTables.rows().isEmpty());

        // 限定名跨库访问
        engine.execute(session, "INSERT INTO shop.orders (id) VALUES (1)");
        Result.Table result = (Result.Table) engine.execute(session, "SELECT * FROM shop.orders");
        assertEquals(1, result.rows().size());
    }

    @Test
    void useUnknownDatabaseReports1049() {
        com.wangyu.tangdb.catalog.CatalogException e = assertThrows(
                com.wangyu.tangdb.catalog.CatalogException.class,
                () -> engine.execute(session, "USE nosuchdb"));
        assertEquals(SqlError.ER_BAD_DB_ERROR, e.code());
    }

    @Test
    void droppedCurrentDatabaseReports1049OnNextStatement() {
        // 边界 4:会话 A 正在用库 x,会话 B 删掉它;A 的下一条带库语义语句报 1049。
        Session a = sessions.create("a", new Executor(kv, catalog));
        Session b = sessions.create("b", new Executor(kv, catalog));
        engine.execute(a, "CREATE DATABASE x");
        engine.execute(a, "USE x");
        engine.execute(b, "DROP DATABASE x");

        com.wangyu.tangdb.catalog.CatalogException e = assertThrows(
                com.wangyu.tangdb.catalog.CatalogException.class,
                () -> engine.execute(a, "SHOW TABLES"));
        assertEquals(SqlError.ER_BAD_DB_ERROR, e.code());
        // 当前库没有被静默切到 default
        assertEquals("x", a.currentDatabase());
    }
}
