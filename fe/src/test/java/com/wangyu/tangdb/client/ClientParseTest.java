package com.wangyu.tangdb.client;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CLI 支撑件(SqlBuffer / TableFormatter)与启动参数解析的单元测试。
 *
 * <p>不需要启动 BE;不做网络调用。</p>
 */
class ClientParseTest {

    @Test
    void parseArgs() {
        Cli.Config defaults = Cli.parseArgs(new String[0], FeConfig.defaults());
        assertEquals("127.0.0.1", defaults.host());
        assertEquals(9100, defaults.port());

        Cli.Config fromConfigFile = Cli.parseArgs(new String[0], FeConfig.of("10.0.0.8", 9200));
        assertEquals("10.0.0.8", fromConfigFile.host());
        assertEquals(9200, fromConfigFile.port());

        Cli.Config separate = Cli.parseArgs(
                new String[]{"--be-host", "10.0.0.8", "--be-port", "9200"}, FeConfig.defaults());
        assertEquals("10.0.0.8", separate.host());
        assertEquals(9200, separate.port());

        Cli.Config equals = Cli.parseArgs(
                new String[]{"--be-host=localhost", "--be-port=19100"}, FeConfig.defaults());
        assertEquals("localhost", equals.host());
        assertEquals(19100, equals.port());
    }

    @Test
    void invalidArgs() {
        expectArgsError(new String[]{"--be-port", "abc"}, "端口无效: abc");
        expectArgsError(new String[]{"--be-port", "0"}, "端口无效: 0");
        expectArgsError(new String[]{"--be-port", "70000"}, "端口无效: 70000");
        expectArgsError(new String[]{"--be-host"}, "参数 --be-host 缺少取值");
        expectArgsError(new String[]{"--bad-flag"}, "未知启动参数: --bad-flag");
    }

    @Test
    void badArgsExitBeforeTouchingNetwork() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int code = Cli.run(
                new String[]{"--bad-flag"},
                new java.io.BufferedReader(new java.io.StringReader("")),
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));

        assertEquals(2, code);
        String err = errBytes.toString(StandardCharsets.UTF_8);
        assertTrue(err.contains("未知启动参数: --bad-flag"));
        assertNoStackTrace(err);
    }

    // ------------------------------------------------------------ SqlBuffer

    @Test
    void splitsMultipleStatements() {
        SqlBuffer buffer = new SqlBuffer();
        List<String> statements = buffer.append("SELECT * FROM t; SELECT 1 FROM t;");
        assertEquals(2, statements.size());
        assertEquals("SELECT * FROM t", statements.get(0));
        assertEquals("SELECT 1 FROM t", statements.get(1));
        assertFalse(buffer.hasPending());
    }

    @Test
    void semicolonInsideStringDoesNotSplit() {
        SqlBuffer buffer = new SqlBuffer();
        assertTrue(buffer.append("SELECT * FROM t WHERE name = 'a;b'").isEmpty());
        assertTrue(buffer.hasPending());

        List<String> statements = buffer.append(";");
        assertEquals(1, statements.size());
        assertEquals("SELECT * FROM t WHERE name = 'a;b'", statements.get(0));
    }

    @Test
    void escapedQuoteInsideString() {
        SqlBuffer buffer = new SqlBuffer();
        List<String> statements = buffer.append("SELECT * FROM t WHERE name = 'it''s; ok';");
        assertEquals(1, statements.size());
        assertEquals("SELECT * FROM t WHERE name = 'it''s; ok'", statements.get(0));
    }

    @Test
    void multilineStatementIsJoined() {
        SqlBuffer buffer = new SqlBuffer();
        assertTrue(buffer.append("CREATE TABLE users (").isEmpty());
        assertTrue(buffer.append("  id BIGINT PRIMARY KEY,").isEmpty());
        List<String> statements = buffer.append("  name VARCHAR(32));");
        assertEquals(1, statements.size());
        assertTrue(statements.get(0).startsWith("CREATE TABLE users ("));
        assertTrue(statements.get(0).endsWith("name VARCHAR(32))"));
    }

    @Test
    void lineCommentIsIgnored() {
        SqlBuffer buffer = new SqlBuffer();
        List<String> statements = buffer.append("SELECT * FROM t -- 只看表 t");
        assertTrue(statements.isEmpty());
        statements = buffer.append(";");
        assertEquals(1, statements.size());
        assertEquals("SELECT * FROM t", statements.get(0));
    }

    @Test
    void emptyStatementsAreIgnored() {
        SqlBuffer buffer = new SqlBuffer();
        assertTrue(buffer.append(";").isEmpty());
        assertTrue(buffer.append("  ;  ; ").isEmpty());
        assertTrue(buffer.append("").isEmpty());
    }

    // ------------------------------------------------------------ TableFormatter

    @Test
    void formatsTableWithRowCount() {
        String text = TableFormatter.format(
                List.of("id", "name"),
                List.<Object[]>of(new Object[]{1, "wangyu"}, new Object[]{2, null}));
        assertTrue(text.contains("| id | name   |"), text);
        assertTrue(text.contains("| 1  | wangyu |"), text);
        assertTrue(text.contains("| 2  | NULL   |"), text);
        assertTrue(text.trim().endsWith("(2 rows)"), text);
    }

    @Test
    void emptyResultPrintsZeroRows() {
        String text = TableFormatter.format(List.of("id"), List.of());
        assertTrue(text.trim().endsWith("(0 rows)"), text);
        assertTrue(text.contains("+----+"), text);
    }

    @Test
    void cjkCountsAsDoubleWidth() {
        assertEquals(4, TableFormatter.displayWidth("北京"));
        assertEquals(2, TableFormatter.displayWidth("ab"));
        String text = TableFormatter.format(List.of("城市"), List.<Object[]>of(new Object[]{"北京"}));
        // 表头(2 字 = 4 宽)与值(4 宽)对齐,两侧各留一个空格
        assertTrue(text.contains("| 城市 |"), text);
        assertTrue(text.contains("| 北京 |"), text);
    }

    private void expectArgsError(String[] args, String expectedPrefix) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Cli.parseArgs(args));
        assertNotNull(e.getMessage());
        assertTrue(e.getMessage().startsWith(expectedPrefix), "actual: " + e.getMessage());
    }

    private void assertNoStackTrace(String output) {
        assertFalse(output.contains("\tat "), "output contains stack trace: " + output);
        assertFalse(output.contains("Exception in thread"), "output contains uncaught exception: " + output);
    }
}
