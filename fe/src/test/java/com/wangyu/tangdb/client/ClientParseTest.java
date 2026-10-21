package com.wangyu.tangdb.client;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 命令行解析与 CLI 启动参数的单元测试。
 *
 * <p>不需要启动 BE,也不会发起除本机 channel 构建之外的网络调用。</p>
 */
class ClientParseTest {

    @Test
    void putParsing() {
        Cli.Command command = Cli.parseCommand("put name wangyu");
        assertEquals(Cli.Type.PUT, command.type());
        assertEquals("name", command.key());
        assertEquals("wangyu", command.value());

        Cli.Command spaced = Cli.parseCommand("  put  name   hello world  ");
        assertEquals(Cli.Type.PUT, spaced.type());
        assertEquals("name", spaced.key());
        assertEquals("hello world", spaced.value());

        Cli.Command upper = Cli.parseCommand("PUT k v");
        assertEquals(Cli.Type.PUT, upper.type());
    }

    @Test
    void getParsing() {
        Cli.Command command = Cli.parseCommand("get name");
        assertEquals(Cli.Type.GET, command.type());
        assertEquals("name", command.key());
        assertNull(command.value());
    }

    @Test
    void simpleCommands() {
        assertEquals(Cli.Type.PING, Cli.parseCommand("ping").type());
        assertEquals(Cli.Type.EXIT, Cli.parseCommand("exit").type());
        assertEquals(Cli.Type.EMPTY, Cli.parseCommand("").type());
        assertEquals(Cli.Type.EMPTY, Cli.parseCommand("   ").type());
        assertEquals(Cli.Type.EMPTY, Cli.parseCommand(null).type());
    }

    @Test
    void invalidCommands() {
        expectCommandError("put name", "用法: put <key> <value>");
        expectCommandError("get", "用法: get <key>");
        expectCommandError("get a b", "用法: get <key>");
        expectCommandError("ping now", "ping 命令不接受参数");
        expectCommandError("exit now", "exit 命令不接受参数");
        expectCommandError("foo", "未知命令: foo");
    }

    @Test
    void parseArgs() {
        Cli.Config defaults = Cli.parseArgs(new String[0]);
        assertEquals("127.0.0.1", defaults.host());
        assertEquals(9100, defaults.port());

        Cli.Config separate = Cli.parseArgs(new String[]{"--be-host", "10.0.0.8", "--be-port", "9200"});
        assertEquals("10.0.0.8", separate.host());
        assertEquals(9200, separate.port());

        Cli.Config equals = Cli.parseArgs(new String[]{"--be-host=localhost", "--be-port=19100"});
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

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int code = Cli.run(
                new String[]{"--bad-flag"},
                readerFor(""),
                printStream(outBytes),
                printStream(errBytes));

        assertEquals(2, code);
        String err = errBytes.toString(StandardCharsets.UTF_8);
        assertTrue(err.contains("未知启动参数: --bad-flag"));
        assertNoStackTrace(err);
    }

    @Test
    void cliLoopWithoutNetwork() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        int code = Cli.run(
                new String[0],
                readerFor("put name\nexit\n"),
                printStream(outBytes),
                printStream(errBytes));

        String out = outBytes.toString(StandardCharsets.UTF_8);
        String err = errBytes.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertTrue(err.contains("用法: put <key> <value>"));
        assertNoStackTrace(out);
        assertNoStackTrace(err);
    }

    @Test
    void cliExit() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        int code = Cli.run(
                new String[0],
                readerFor("exit\n"),
                printStream(outBytes),
                printStream(errBytes));

        String out = outBytes.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertTrue(out.contains("再见"));
        assertNoStackTrace(errBytes.toString(StandardCharsets.UTF_8));
    }

    private void expectCommandError(String line, String expectedPrefix) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Cli.parseCommand(line));
        assertNotNull(e.getMessage());
        assertTrue(e.getMessage().startsWith(expectedPrefix), "actual: " + e.getMessage());
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

    private BufferedReader readerFor(String text) {
        return new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8));
    }

    private PrintStream printStream(ByteArrayOutputStream bytes) {
        return new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }
}
