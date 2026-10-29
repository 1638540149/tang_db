package com.wangyu.tangdb.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code config/fe.conf} 的加载与取值优先级测试。
 */
class FeConfigTest {

    @Test
    void defaults() {
        FeConfig cfg = FeConfig.defaults();
        assertEquals(TangDbClient.DEFAULT_HOST, cfg.beHost());
        assertEquals(TangDbClient.DEFAULT_PORT, cfg.bePort());
        assertEquals(9091, cfg.heartbeatListenPort());
        assertEquals(3, cfg.beOfflineCycles());
    }

    @Test
    void missingDefaultPathFallsBackToDefaults() {
        FeConfig cfg = FeConfig.load(FeConfig.DEFAULT_PATH);
        assertEquals(TangDbClient.DEFAULT_HOST, cfg.beHost());
        assertEquals(TangDbClient.DEFAULT_PORT, cfg.bePort());
    }

    @Test
    void missingExplicitPathIsAnError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> FeConfig.load(tempDir().resolve("nope.conf").toString()));
        assertTrue(e.getMessage().startsWith("配置文件不存在"), "actual: " + e.getMessage());
    }

    @Test
    void readsConfigFile() throws IOException {
        Path file = write("""
                # tang_db FE 配置
                be_host = 10.0.0.8
                be_port = 9200
                heartbeat_listen_port = 9191
                be_offline_cycles = 5
                """);
        FeConfig cfg = FeConfig.load(file.toString());
        assertEquals("10.0.0.8", cfg.beHost());
        assertEquals(9200, cfg.bePort());
        assertEquals(9191, cfg.heartbeatListenPort());
        assertEquals(5, cfg.beOfflineCycles());
    }

    @Test
    void heartbeatEnabledDefaultsTrueAndCanBeDisabled() throws IOException {
        assertTrue(FeConfig.defaults().heartbeatEnabled());
        Path on = write("heartbeat_enabled = true\n");
        assertTrue(FeConfig.load(on.toString()).heartbeatEnabled());
        Path off = write("heartbeat_enabled = false\n");
        assertFalse(FeConfig.load(off.toString()).heartbeatEnabled());
        assertMessage("heartbeat_enabled = maybe", "heartbeat_enabled 无效");
    }

    @Test
    void missingKeysKeepDefaults() throws IOException {
        Path file = write("be_port = 9300\n");
        FeConfig cfg = FeConfig.load(file.toString());
        assertEquals(TangDbClient.DEFAULT_HOST, cfg.beHost());
        assertEquals(9300, cfg.bePort());
    }

    @Test
    void rejectsBadContent() throws IOException {
        assertMessage("be_port", "格式错误");       // 缺少 =
        assertMessage("unknown_key = 1", "未知配置项");
        assertMessage("be_port = ", "的取值为空");
        assertMessage("be_port = 70000", "be_port 无效");
        assertMessage("be_port = abc", "be_port 无效");
        assertMessage("be_host =   ", "的取值为空");
        assertMessage("heartbeat_listen_port = 70000", "heartbeat_listen_port 无效");
        assertMessage("heartbeat_listen_port = abc", "heartbeat_listen_port 无效");
        assertMessage("be_offline_cycles = 0", "be_offline_cycles 无效");
        assertMessage("be_offline_cycles = x", "be_offline_cycles 无效");
    }

    /** SPEC-P6.1 §5.2:9 项里的 FE 6 项默认值与解析(FR-722 / FR-723)。 */
    @Test
    void p61Fields() throws IOException {
        FeConfig d = FeConfig.defaults();
        assertEquals(Math.min(8, Runtime.getRuntime().availableProcessors()), d.queryParallelism());
        assertTrue(d.pushdownEnabled());
        assertEquals(4096, d.scanBatchRows());
        assertEquals(5, d.rpcDeadlineSeconds());
        assertEquals(60, d.rpcBulkDeadlineSeconds());
        assertEquals(60, d.rpcScanDeadlineSeconds());

        Path file = write("""
                query_parallelism = 3
                pushdown_enabled = false
                scan_batch_rows = 1024
                rpc_deadline_seconds = 7
                rpc_bulk_deadline_seconds = 90
                rpc_scan_deadline_seconds = 120
                """);
        FeConfig cfg = FeConfig.load(file.toString());
        assertEquals(3, cfg.queryParallelism());
        assertFalse(cfg.pushdownEnabled());
        assertEquals(1024, cfg.scanBatchRows());
        assertEquals(7, cfg.rpcDeadlineSeconds());
        assertEquals(90, cfg.rpcBulkDeadlineSeconds());
        assertEquals(120, cfg.rpcScanDeadlineSeconds());
    }

    /** 非法值启动失败并指出行号(§8 #115/#116/#122)。 */
    @Test
    void p61RejectsBadValues() throws IOException {
        assertMessage("query_parallelism = 0", "query_parallelism 无效");
        assertMessage("query_parallelism = abc", "query_parallelism 无效");
        int over = Math.max(1, Runtime.getRuntime().availableProcessors() * 4) + 1;
        assertMessage("query_parallelism = " + over, "建议不超过");
        assertMessage("pushdown_enabled = maybe", "pushdown_enabled 无效");
        assertMessage("scan_batch_rows = 0", "scan_batch_rows 无效");
        assertMessage("scan_batch_rows = 8193", "scan_batch_rows 无效");
        assertMessage("rpc_deadline_seconds = 0", "rpc_deadline_seconds 无效");
        assertMessage("rpc_deadline_seconds = 601", "rpc_deadline_seconds 无效");
        assertMessage("rpc_scan_deadline_seconds = x", "rpc_scan_deadline_seconds 无效");
    }

    /** 命令行参数覆盖配置文件。 */
    @Test
    void commandLineOverridesConfigFile() throws IOException {
        Path file = write("user = alice\npassword = pw\nmysql_port = 3456\n");
        Cli.Config config = Cli.parseArgs(
                new String[]{"--config=" + file, "--port=9300", "--user=bob"},
                FeConfig.load(file.toString()));
        assertEquals("127.0.0.1", config.host());
        assertEquals(9300, config.port());
        assertEquals("bob", config.user());
        assertEquals("pw", config.password());
    }

    private void assertMessage(String content, String expected) throws IOException {
        Path file = write(content);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> FeConfig.load(file.toString()));
        assertNotNull(e.getMessage());
        assertTrue(e.getMessage().contains(expected),
                "期望包含 " + expected + ",实际: " + e.getMessage());
    }

    private Path tempDir() {
        try {
            return Files.createTempDirectory("tangdb-feconfig");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Path write(String content) throws IOException {
        Path file = tempDir().resolve("fe.conf");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
