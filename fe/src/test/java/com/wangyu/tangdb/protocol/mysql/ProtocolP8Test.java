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
import java.net.Socket;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC-P8 A 组(FR-813 ~ FR-822)协议收口验收:
 *
 * <ul>
 *   <li>命令白名单与 {@code COM_STMT_*} 明确报错不断连(边界 166 / SC-459);</li>
 *   <li>{@code COM_FIELD_LIST} 返回列定义;</li>
 *   <li>图形客户端元数据探测语句逐条 CODE=0(FR-815 / FR-817 / SC-447);</li>
 *   <li>连接上限 1040(边界 167)。</li>
 * </ul>
 */
class ProtocolP8Test {

    /** FR-815 / FR-866:探测语句清单(逐条要求 CODE=0)。 */
    static final List<String> PROBES = List.of(
            "SELECT 1",
            "SELECT DATABASE()",
            "SELECT USER()",
            "SELECT CONNECTION_ID()",
            "SELECT VERSION()",
            "SELECT @@version_comment LIMIT 1",
            "SELECT @@lower_case_table_names",
            "SELECT @@sql_mode",
            "SELECT @@max_allowed_packet",
            "SELECT @@wait_timeout",
            "SELECT @@character_set_client, @@character_set_connection, @@collation_connection",
            "SELECT @@tx_isolation",
            "SELECT @@autocommit",
            "SHOW TABLE STATUS",
            "SHOW COLLATION",
            "SHOW CHARSET",
            "SHOW CHARACTER SET",
            "SHOW ENGINES",
            "SHOW GRANTS",
            "SHOW WARNINGS",
            "SHOW ERRORS",
            "SHOW VARIABLES LIKE 'version%'",
            "SHOW VARIABLES LIKE 'max_connections'",
            "SHOW VARIABLES LIKE 'character_set%'",
            "SHOW STATUS LIKE 'Uptime'",
            "SHOW FULL TABLES",
            "SHOW FULL COLUMNS FROM t",
            "SHOW INDEX FROM t",
            "SHOW CREATE TABLE t",
            "SHOW CREATE DATABASE default",
            "SHOW TRIGGERS",
            "SHOW EVENTS",
            "SHOW ROUTINES",
            "SELECT COUNT(*) FROM information_schema.COLLATIONS",
            "SELECT * FROM information_schema.CHARACTER_SETS",
            "SELECT * FROM information_schema.ENGINES",
            "SELECT * FROM information_schema.ROUTINES",
            "SELECT * FROM information_schema.SCHEMATA",
            "SELECT * FROM information_schema.TABLES",
            "SET NAMES utf8mb4",
            "SET SESSION sql_mode = ''"
    );

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
                NativePassword.deriveStoredHash("tangdb"), 4, 0);
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
    void preparedStatementReturnsErrorAndKeepsConnection() throws Exception {
        try (MysqlClientConnection conn = connect()) {
            byte[] reply = conn.rawCommand(Protocol.COM_STMT_PREPARE,
                    "SELECT 1".getBytes(StandardCharsets.UTF_8));
            assertEquals(0xFF, reply[0] & 0xFF, "COM_STMT_PREPARE 必须回 ERR 包");
            int code = (reply[1] & 0xFF) | ((reply[2] & 0xFF) << 8);
            assertEquals(SqlError.ER_UNSUPPORTED_PS, code);

            // 边界 166 / SC-459:连接保持,后续语句照常执行。
            MysqlClientConnection.Response sel = conn.query("SELECT 1");
            assertTrue(sel.isResultSet());
            assertEquals(1, sel.rows().size());

            byte[] execute = conn.rawCommand(Protocol.COM_STMT_EXECUTE, new byte[]{1});
            assertEquals(0xFF, execute[0] & 0xFF);
            assertTrue(conn.query("SELECT 1").isResultSet());
        }
    }

    @Test
    void unsupportedCommandReturnsErrorNotDisconnect() throws Exception {
        try (MysqlClientConnection conn = connect()) {
            byte[] reply = conn.rawCommand(Protocol.COM_CHANGE_USER, new byte[]{0});
            assertEquals(0xFF, reply[0] & 0xFF);
            assertTrue(conn.query("SELECT 1").isResultSet());
        }
    }

    @Test
    void fieldListReturnsColumnDefinitions() throws Exception {
        try (MysqlClientConnection conn = connect()) {
            conn.query("CREATE TABLE fl (id INT PRIMARY KEY, name VARCHAR(16))");
            conn.writeCommand(Protocol.COM_FIELD_LIST, "fl\0".getBytes(StandardCharsets.UTF_8));
            int columns = 0;
            while (true) {
                byte[] packet = conn.readRawPacket();
                if ((packet[0] & 0xFF) == 0xFE) {
                    break; // EOF
                }
                assertNotEquals(0xFF, packet[0] & 0xFF, "COM_FIELD_LIST 不应报错");
                columns++;
            }
            assertEquals(2, columns);

            // 未知表 → 1146,连接保持
            byte[] reply = conn.rawCommand(Protocol.COM_FIELD_LIST,
                    "no_such_table\0".getBytes(StandardCharsets.UTF_8));
            assertEquals(0xFF, reply[0] & 0xFF);
            int code = (reply[1] & 0xFF) | ((reply[2] & 0xFF) << 8);
            assertEquals(SqlError.ER_NO_SUCH_TABLE, code);
            assertTrue(conn.query("SELECT 1").isResultSet());
        }
    }

    @Test
    void guiIntrospectionProbesAllSucceed() throws Exception {
        try (MysqlClientConnection conn = connect()) {
            conn.query("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(32))");
            conn.query("INSERT INTO t VALUES (1, 'a')");
            for (String probe : PROBES) {
                assertDoesNotThrow(() -> conn.query(probe), "探测语句应 CODE=0: " + probe);
            }
        }
    }

    @Test
    void showIndexReturnsRowsForIndexedTable() throws Exception {
        try (MysqlClientConnection conn = connect()) {
            conn.query("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(32))");
            conn.query("CREATE INDEX idx_name ON t (name)");
            MysqlClientConnection.Response resp = conn.query("SHOW INDEX FROM t");
            assertTrue(resp.isResultSet());
            assertTrue(resp.rows().size() >= 1, "SHOW INDEX 至少 1 行(SC-447)");
        }
    }

    @Test
    void connectionLimitReturns1040() throws Exception {
        // 上限 4;开满 4 条后第 5 条被拒。
        MysqlClientConnection[] conns = new MysqlClientConnection[4];
        try {
            for (int i = 0; i < conns.length; i++) {
                conns[i] = connect();
            }
            try (Socket socket = new Socket("127.0.0.1", port)) {
                PacketIO io = new PacketIO(socket.getInputStream(), socket.getOutputStream());
                byte[] reply = io.readPacket();
                assertEquals(0xFF, reply[0] & 0xFF);
                int code = (reply[1] & 0xFF) | ((reply[2] & 0xFF) << 8);
                assertEquals(MysqlServer.ER_CON_COUNT_ERROR, code);
            }
            // 已有连接不受影响(边界 167)
            assertTrue(conns[0].query("SELECT 1").isResultSet());
        } finally {
            for (MysqlClientConnection conn : conns) {
                if (conn != null) {
                    conn.close();
                }
            }
        }
    }

    @Test
    void sessionVariablesAcceptedWithoutBehaviorChange() throws Exception {
        try (MysqlClientConnection conn = connect()) {
            assertFalse(conn.query("SET autocommit = 1").isResultSet());
            assertFalse(conn.query("SET NAMES utf8mb4").isResultSet());
            assertFalse(conn.query("SET SESSION sql_mode = 'STRICT_TRANS_TABLES'").isResultSet());
            assertTrue(conn.query("SELECT 1").isResultSet());
        }
    }

    @Test
    void statementTimingRecordedInMetrics() throws Exception {
        com.wangyu.tangdb.metrics.Metrics metrics =
                new com.wangyu.tangdb.metrics.Metrics(true);
        try (MysqlClientConnection conn = connect()) {
            // 换一张干净的服务端:直接用当前服务端即可,ENGINE 内部已挂 DISABLED 指标,
            // 这里只验证 Metrics 自己的记账语义(FR-822)。
            metrics.recordStatement(5, 0);
            metrics.recordStatement(7, SqlError.ER_NO_SUCH_TABLE);
            long errors = metrics.snapshot().stream()
                    .filter(p -> p.name().equals("fe_query_error_count"))
                    .findFirst().orElseThrow().value() > 0 ? 1 : 0;
            assertEquals(1, errors);
            assertTrue(conn.query("SELECT 1").isResultSet());
        }
    }
}
