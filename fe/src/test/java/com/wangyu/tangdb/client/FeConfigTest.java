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

    /** 命令行参数覆盖配置文件。 */
    @Test
    void commandLineOverridesConfigFile() throws IOException {
        Path file = write("be_host = 10.0.0.8\nbe_port = 9200\n");
        Cli.Config config = Cli.parseArgs(
                new String[]{"--config=" + file, "--be-port=9300"},
                FeConfig.load(file.toString()));
        assertEquals("10.0.0.8", config.host());
        assertEquals(9300, config.port());
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
