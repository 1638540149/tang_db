package com.wangyu.tangdb.execute;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.parser.ParseException;
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

    // ------------------------------------------------------------ P4 H-7(FR-560 ~ FR-562)

    @Test
    void selectLiteralProjectionWithoutFromReturnsOneRow() {
        // FR-560:列名为字面量文本,一行。
        Result.Table one = (Result.Table) engine.execute(session, "SELECT 1");
        assertEquals(List.of("1"), one.columns());
        assertEquals(1, one.rows().size());
        assertEquals(1, one.rows().get(0)[0]);

        Result.Table two = (Result.Table) engine.execute(session, "SELECT 1, 'x'");
        assertEquals(List.of("1", "x"), two.columns());
        assertEquals(1, two.rows().size());
        assertEquals(1, two.rows().get(0)[0]);
        assertEquals("x", two.rows().get(0)[1]);
    }

    @Test
    void showCountStarWarningsAndErrorsReturnZero() {
        // FR-561:单列 @@session.warning_count / @@session.error_count,值 0。
        Result.Table warnings = (Result.Table) engine.execute(session, "SHOW COUNT(*) WARNINGS");
        assertEquals(List.of("@@session.warning_count"), warnings.columns());
        assertEquals(1, warnings.rows().size());
        assertEquals(0L, warnings.rows().get(0)[0]);

        Result.Table errors = (Result.Table) engine.execute(session, "SHOW COUNT(*) ERRORS");
        assertEquals(List.of("@@session.error_count"), errors.columns());
        assertEquals(1, errors.rows().size());
        assertEquals(0L, errors.rows().get(0)[0]);
    }

    @Test
    void showVariablesAndStatusSupportWhereFiltering() {
        // FR-562 / 边界 40:过滤到无匹配 → 空集,不报错。
        Result.Table variables = (Result.Table) engine.execute(session,
                "SHOW VARIABLES WHERE Variable_name = 'version'");
        assertEquals(1, variables.rows().size());
        assertEquals("version", variables.rows().get(0)[0]);
        assertEquals(SqlEngine.SERVER_VERSION, variables.rows().get(0)[1]);

        Result.Table empty = (Result.Table) engine.execute(session,
                "SHOW VARIABLES WHERE Variable_name = 'no_such_variable'");
        assertTrue(empty.rows().isEmpty());
        assertEquals(List.of("Variable_name", "Value"), empty.columns());

        Result.Table status = (Result.Table) engine.execute(session,
                "SHOW STATUS WHERE Variable_name = 'Uptime'");
        assertEquals(1, status.rows().size());
        assertEquals("Uptime", status.rows().get(0)[0]);
        assertEquals(0L, status.rows().get(0)[1]);

        Result.Table statusEmpty = (Result.Table) engine.execute(session,
                "SHOW STATUS WHERE Variable_name = 'No_Such_Status'");
        assertTrue(statusEmpty.rows().isEmpty());
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
    void informationSchemaUnknownTableRejectedWith1146() {
        // P4:PARTITIONS 已是受支持的虚拟表(列正确的空集);
        // 未在 17 张清单里的表名报 1146(FR-542),不是 1235。
        Result.Table partitions = (Result.Table) engine.execute(session,
                "SELECT * FROM information_schema.partitions");
        assertTrue(partitions.rows().isEmpty());
        SqlError e = assertThrows(SqlError.class,
                () -> engine.execute(session, "SELECT * FROM information_schema.no_such_table"));
        assertEquals(SqlError.ER_NO_SUCH_TABLE, e.code());
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

    // ------------------------------------------------------------ P4 H 组:COUNT(*) 别名(FR-555 / K102)

    @Test
    void countStarAliasBecomesResultColumnName() {
        Result.Table aliased = (Result.Table) engine.execute(session,
                "SELECT COUNT(*) AS support_ndb FROM information_schema.ENGINES "
                        + "WHERE Engine = 'ndbcluster'");
        assertEquals(List.of("support_ndb"), aliased.columns());
        assertEquals(1, aliased.rows().size());
        assertEquals(0L, aliased.rows().get(0)[0]);

        // AS 可省(K102):与普通列别名同一套解析。
        Result.Table withoutAs = (Result.Table) engine.execute(session,
                "SELECT COUNT(*) support_ndb FROM information_schema.ENGINES "
                        + "WHERE Engine = 'ndbcluster'");
        assertEquals(List.of("support_ndb"), withoutAs.columns());

        // 没有别名时列名仍是 COUNT(*)。
        Result.Table unnamed = (Result.Table) engine.execute(session,
                "SELECT COUNT(*) FROM information_schema.ENGINES");
        assertEquals(List.of("COUNT(*)"), unnamed.columns());
        assertEquals(1L, unnamed.rows().get(0)[0]);
    }

    @Test
    void countStarOnUserTableStillRejectedWith1235() {
        engine.execute(session, "CREATE TABLE cnt (id INT PRIMARY KEY)");
        engine.execute(session, "INSERT INTO cnt (id) VALUES (1)");

        SqlError withAlias = assertThrows(SqlError.class, () -> engine.execute(session,
                "SELECT COUNT(*) AS c FROM cnt"));
        assertEquals(SqlError.ER_NOT_SUPPORTED_YET, withAlias.code());

        SqlError withoutAlias = assertThrows(SqlError.class, () -> engine.execute(session,
                "SELECT COUNT(*) FROM cnt"));
        assertEquals(SqlError.ER_NOT_SUPPORTED_YET, withoutAlias.code());
    }

    // ------------------------------------------------------------ P4 H 组:单包多语句(FR-556 ~ FR-558)

    @Test
    void splitterKeepsSemisInsideQuotesIdentifiersAndComments() {
        assertEquals(List.of("SELECT 'a;b' FROM t"),
                SqlEngine.splitStatements("SELECT 'a;b' FROM t"));
        assertEquals(List.of("SELECT `a;b` FROM t"),
                SqlEngine.splitStatements("SELECT `a;b` FROM t"));
        assertEquals(List.of("SELECT 'it''s;ok' FROM t"),
                SqlEngine.splitStatements("SELECT 'it''s;ok' FROM t"));
        assertEquals(List.of("SELECT 1 /* ; */"), SqlEngine.splitStatements("SELECT 1 /* ; */"));
        assertEquals(List.of("SELECT 1 -- ;\n", " SELECT 2"),
                SqlEngine.splitStatements("SELECT 1 -- ;\n; SELECT 2"));
        assertEquals(List.of("SELECT 1 # ;\n", " SELECT 2"),
                SqlEngine.splitStatements("SELECT 1 # ;\n; SELECT 2"));
        assertEquals(List.of("A", " B", " C"), SqlEngine.splitStatements("A; B; C"));
        assertEquals(List.of("SHOW TABLES", "", ""), SqlEngine.splitStatements("SHOW TABLES;;"));
    }

    @Test
    void emptyStatementsAndTrailingSemicolonsAreIgnored() {
        assertEquals(List.of("  SHOW DATABASES  "),
                SqlEngine.splitStatements("  SHOW DATABASES  ; ").stream()
                        .filter(s -> !s.isBlank()).toList());

        // 连续分号 / 尾随分号 / 空文本都不报 1064:没有非空语句时回 OK。
        Result.Table once = (Result.Table) engine.execute(session, "SHOW DATABASES;;");
        assertEquals(1, once.rows().size());
        assertInstanceOf(Result.Message.class, engine.execute(session, ";;"));
        assertInstanceOf(Result.Message.class, engine.execute(session, "   "));
        assertInstanceOf(Result.Message.class, engine.executeBatch(session, "/* 只有注释 */").get(0));
    }

    @Test
    void batchReturnsEveryResultInOrder() {
        List<Result> results = engine.executeBatch(session,
                "SHOW DATABASES; SELECT COUNT(*) AS c FROM information_schema.ENGINES; SHOW TABLES");
        assertEquals(3, results.size());
        Result.Table databases = (Result.Table) results.get(0);
        assertEquals("Database", databases.columns().get(0));
        Result.Table count = (Result.Table) results.get(1);
        assertEquals(List.of("c"), count.columns());
        assertEquals(1L, count.rows().get(0)[0]);
        Result.Table tables = (Result.Table) results.get(2);
        assertEquals("table_name", tables.columns().get(0));

        // execute 保持"一条语句一个结果"的旧口径:取最后一条。
        Result last = engine.execute(session,
                "SHOW DATABASES; SELECT COUNT(*) AS c FROM information_schema.ENGINES");
        assertEquals(List.of("c"), ((Result.Table) last).columns());
    }

    @Test
    void batchStopsAtFirstFailureAndSkipsLaterStatements() {
        engine.execute(session, "CREATE TABLE stop (id INT PRIMARY KEY)");

        ParseException e = assertThrows(ParseException.class, () -> engine.executeBatch(session,
                "INSERT INTO stop (id) VALUES (1); SELEC * FROM stop; INSERT INTO stop (id) VALUES (2)"));
        assertTrue(e.getMessage().contains("语法错误"), e.getMessage());

        // 第 1 条已执行;第 3 条没有执行(FR-558)。
        Result.Table rows = (Result.Table) engine.execute(session, "SELECT id FROM stop");
        assertEquals(1, rows.rows().size());
        assertEquals(1, ((Number) rows.rows().get(0)[0]).intValue());
    }

    @Test
    void statementErrorsInBatchKeepTheirSingleStatementCode() {
        // 批量里的错误码/文案与单条执行一致(FR-558):未知表的 1146 不被包装。
        com.wangyu.tangdb.catalog.CatalogException e = assertThrows(
                com.wangyu.tangdb.catalog.CatalogException.class,
                () -> engine.executeBatch(session,
                        "SHOW DATABASES; "
                                + "SELECT COUNT(*) AS c FROM information_schema.ENGINES; "
                                + "SELECT id FROM no_such_table"));
        assertEquals(SqlError.ER_NO_SUCH_TABLE, e.code(), "第 3 条是 1146,与单条一致");
    }
}
