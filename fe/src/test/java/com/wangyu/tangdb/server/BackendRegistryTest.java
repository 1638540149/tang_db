package com.wangyu.tangdb.server;

import com.wangyu.tangdb.proto.HeartbeatRequest;
import com.wangyu.tangdb.proto.RegisterBeRequest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** BE 存活判定测试(FR-154 / K23)。 */
class BackendRegistryTest {

    @Test
    void marksUnavailableAfterOfflineCyclesAndRecovers() {
        AtomicLong now = new AtomicLong(1_000_000);
        BackendRegistry registry = new BackendRegistry(3, now::get);

        registry.register(RegisterBeRequest.newBuilder()
                .setBeId("be-1")
                .setEndpoint("127.0.0.1:9100")
                .setVersion("P2-0.1.0")
                .setHeartbeatIntervalSeconds(3)
                .build());
        assertTrue(registry.anyAvailable());
        assertEquals("可用", registry.all().get(0).statusText(now.get(), 3));

        // 恰好 3 个周期(9 秒)仍算可用
        now.addAndGet(9_000);
        assertTrue(registry.anyAvailable());

        // 超过窗口即判死
        now.addAndGet(1);
        assertFalse(registry.anyAvailable());
        assertEquals("不可用", registry.all().get(0).statusText(now.get(), 3));
        assertTrue(registry.unavailableHint().contains("127.0.0.1:9100"));

        // 再收到心跳自动恢复
        registry.heartbeat(HeartbeatRequest.newBuilder()
                .setBeId("be-1")
                .setEndpoint("127.0.0.1:9100")
                .setHeartbeatIntervalSeconds(3)
                .setPutCount(42)
                .setSegmentCount(2)
                .build());
        assertTrue(registry.anyAvailable());
        assertEquals(42, registry.all().get(0).lastHeartbeat().getPutCount());
        assertEquals(2, registry.all().get(0).lastHeartbeat().getSegmentCount());
    }

    @Test
    void heartbeatFromUnknownBackendRegistersIt() {
        AtomicLong now = new AtomicLong(0);
        BackendRegistry registry = new BackendRegistry(3, now::get);
        registry.heartbeat(HeartbeatRequest.newBuilder()
                .setBeId("be-9")
                .setEndpoint("127.0.0.1:9200")
                .setHeartbeatIntervalSeconds(3)
                .build());
        assertEquals(1, registry.all().size());
        assertTrue(registry.anyAvailable());
    }

    @Test
    void missingFieldsAreTolerated() {
        AtomicLong now = new AtomicLong(0);
        BackendRegistry registry = new BackendRegistry(3, now::get);
        // 什么都没填:不得拒收
        registry.heartbeat(HeartbeatRequest.getDefaultInstance());
        assertEquals(1, registry.all().size());
        assertTrue(registry.anyAvailable(), "interval 缺失时按默认 3 秒处理");
        assertEquals(3, registry.all().get(0).intervalSeconds());
    }

    @Test
    void unavailableHintWhenEmpty() {
        BackendRegistry registry = new BackendRegistry(3, () -> 0);
        assertTrue(registry.unavailableHint().contains("尚未收到"));
        assertFalse(registry.anyAvailable());
    }
}
