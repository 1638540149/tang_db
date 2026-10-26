package com.wangyu.tangdb.protocol.mysql;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.execute.SqlEngine;
import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.server.BackendRegistry;
import com.wangyu.tangdb.session.SessionManager;
import com.wangyu.tangdb.testing.FakeKvStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 协议端到端(自连):用内存 KvStore 起一个 MySQL 服务,再用自研客户端连上执行 SQL,
 * 验证握手、认证、COM_QUERY、结果集、错误码、状态位(FR-431 ~ FR-455)。
 */
class MysqlEndToEndTest {

    private FakeKvStore kv;
    private Catalog catalog;
    private SessionManager sessions;
    private MysqlServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        kv = new FakeKvStore();
        catalog = Catalog.open(kv);
        sessions = new SessionManager(4, 0);
        SqlEngine engine = new SqlEngine(kv, catalog, sessions, new BackendRegistry(3), false, "root");
        port = freePort();
        server = new MysqlServer(port, engine, sessions, kv, catalog, "root",
                NativePassword.deriveStoredHash("tangdb"));
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.close();
        server.closeAllClients();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private MysqlClientConnection connect() throws Exception {
        return MysqlClientConnection.connect("127.0.0.1", port, "root", "tangdb", null);
    }

    @Test
    void createInsertSelectRoundTrip() throws Exception {
        try (MysqlClientConnection conn = connect()) {
            assertFalse(conn.query("CREATE TABLE t (id BIGINT PRIMARY KEY, name VARCHAR(32))").isResultSet());
            assertFalse(conn.query("INSERT INTO t (id, name) VALUES (1, 'wangyu')").isResultSet());
            MysqlClientConnection.Response sel = conn.query("SELECT id, name FROM t");
            assertTrue(sel.isResultSet());
            assertEquals(List.of("id", "name"), sel.columns());
            assertEquals(1, sel.rows().size());
            assertArrayEquals(new Object[]{"1", "wangyu"}, sel.rows().get(0));
        }
    }

    @Test
    void nullValueIsRepresented() throws Exception {
        try (MysqlClientConnection conn = connect()) {
            conn.query("CREATE TABLE t (id BIGINT PRIMARY KEY, name VARCHAR(32))");
            conn.query("INSERT INTO t (id) VALUES (1)");
            MysqlClientConnection.Response sel = conn.query("SELECT id, name FROM t");
            assertNull(sel.rows().get(0)[1], "name 未给,应为 NULL");
        }
    }

    @Test
    void errorCodesMatchSpec() throws Exception {
        try (MysqlClientConnection conn = connect()) {
            MysqlClientConnection.MysqlClientException e = assertThrows(
                    MysqlClientConnection.MysqlClientException.class,
                    () -> conn.query("SELECT * FROM no_such_table"));
            assertEquals(SqlError.ER_NO_SUCH_TABLE, e.code());

            MysqlClientConnection.MysqlClientException syntax = assertThrows(
                    MysqlClientConnection.MysqlClientException.class,
                    () -> conn.query("SELEC * FROM t"));
            assertEquals(SqlError.ER_SYNTAX_ERROR, syntax.code());

            conn.query("CREATE TABLE t (id INT PRIMARY KEY)");
            conn.query("INSERT INTO t (id) VALUES (1)");
            MysqlClientConnection.MysqlClientException dup = assertThrows(
                    MysqlClientConnection.MysqlClientException.class,
                    () -> conn.query("INSERT INTO t (id) VALUES (1)"));
            assertEquals(SqlError.ER_DUP_ENTRY, dup.code());
        }
    }

    @Test
    void authFailureReturns1045() {
        MysqlClientConnection.MysqlClientException e = assertThrows(
                MysqlClientConnection.MysqlClientException.class,
                () -> MysqlClientConnection.connect("127.0.0.1", port, "root", "wrong", null));
        assertEquals(SqlError.ER_ACCESS_DENIED_ERROR, e.code());
    }

    @Test
    void transactionStatusFlagToggles() throws Exception {
        try (MysqlClientConnection conn = connect()) {
            conn.query("CREATE TABLE t (id INT PRIMARY KEY)");
            assertFalse(conn.inTransaction());

            conn.query("BEGIN");
            assertTrue(conn.inTransaction(), "BEGIN 后应置 SERVER_STATUS_IN_TRANS");

            conn.query("INSERT INTO t (id) VALUES (1)");
            assertTrue(conn.inTransaction());

            conn.query("COMMIT");
            assertFalse(conn.inTransaction());
        }
    }

    @Test
    void selectVersionAndInformationSchema() throws Exception {
        try (MysqlClientConnection conn = connect()) {
            MysqlClientConnection.Response version = conn.query("SELECT @@version");
            assertEquals("5.7.99-tangdb", version.rows().get(0)[0]);

            conn.query("CREATE DATABASE shop");
            MysqlClientConnection.Response schemata = conn.query(
                    "SELECT * FROM information_schema.schemata");
            int idx = schemata.columns().indexOf("SCHEMA_NAME");
            java.util.Set<String> names = new java.util.HashSet<>();
            for (Object[] row : schemata.rows()) {
                names.add((String) row[idx]);
            }
            assertTrue(names.contains("shop"));
        }
    }

    @Test
    void initDbSwitchesDatabase() throws Exception {
        try (MysqlClientConnection conn = connect()) {
            conn.query("CREATE DATABASE shop");
            conn.changeDatabase("shop");
            conn.query("CREATE TABLE t (id INT PRIMARY KEY)");
            // 当前库是 shop;default 里没有 t
            MysqlClientConnection.Response tables = conn.query("SHOW TABLES");
            assertEquals("t", tables.rows().get(0)[0]);
        }
    }

    @Test
    void sessionLimitRejectsExtraConnections() throws Exception {
        // 会话上限 1:第二个连接握手后应被拒。
        SessionManager one = new SessionManager(1, 0);
        SqlEngine engine = new SqlEngine(kv, catalog, one, new BackendRegistry(3), false, "root");
        int p = freePort();
        MysqlServer s = new MysqlServer(p, engine, one, kv, catalog, "root",
                NativePassword.deriveStoredHash("tangdb"));
        s.start();
        try (MysqlClientConnection first = MysqlClientConnection.connect("127.0.0.1", p, "root", "tangdb", null)) {
            MysqlClientConnection.MysqlClientException e = assertThrows(
                    MysqlClientConnection.MysqlClientException.class,
                    () -> MysqlClientConnection.connect("127.0.0.1", p, "root", "tangdb", null));
            assertEquals(SessionManager.ER_CON_COUNT_ERROR, e.code());
        } finally {
            s.close();
            s.closeAllClients();
        }
    }
}
