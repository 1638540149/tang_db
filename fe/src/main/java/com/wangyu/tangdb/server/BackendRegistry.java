package com.wangyu.tangdb.server;

import com.wangyu.tangdb.proto.HeartbeatRequest;
import com.wangyu.tangdb.proto.RegisterBeRequest;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * BE 存活列表(规约 FR-154 / FR-155)。
 *
 * <p>可用性是"算出来的":每次读取时比较当前时间与该 BE 的最后心跳时间,
 * 超过 {@code offlineCycles × interval} 即不可用;再收到心跳自动恢复。
 * 这样不需要后台扫描线程,也不怕 FE 卡顿。</p>
 */
public final class BackendRegistry {

    private static final Logger LOG = Logger.getLogger(BackendRegistry.class.getName());

    private static final int DEFAULT_INTERVAL_SECONDS = 3;
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** 一个 BE 的存活信息。 */
    public static final class Backend {
        private final String beId;
        private final String endpoint;
        private final String version;
        private final long startTimeMillis;
        private final int intervalSeconds;
        private volatile long lastSeenMillis;
        private volatile HeartbeatRequest lastHeartbeat;
        private volatile String dataDir = "";

        private Backend(String beId, String endpoint, String version, long startTimeMillis,
                        int intervalSeconds, long lastSeenMillis) {
            this.beId = beId;
            this.endpoint = endpoint;
            this.version = version;
            this.startTimeMillis = startTimeMillis;
            this.intervalSeconds = intervalSeconds <= 0 ? DEFAULT_INTERVAL_SECONDS : intervalSeconds;
            this.lastSeenMillis = lastSeenMillis;
        }

        public String beId() {
            return beId;
        }

        public String endpoint() {
            return endpoint;
        }

        public String version() {
            return version;
        }

        public long startTimeMillis() {
            return startTimeMillis;
        }

        public int intervalSeconds() {
            return intervalSeconds;
        }

        public long lastSeenMillis() {
            return lastSeenMillis;
        }

        public HeartbeatRequest lastHeartbeat() {
            return lastHeartbeat;
        }

        public String dataDir() {
            return dataDir;
        }

        public String lastSeenText() {
            return TIME_FMT.format(Instant.ofEpochMilli(lastSeenMillis));
        }

        public boolean available(long nowMillis, int offlineCycles) {
            long window = (long) intervalSeconds * offlineCycles * 1000L;
            return nowMillis - lastSeenMillis <= window;
        }

        public String statusText(long nowMillis, int offlineCycles) {
            return available(nowMillis, offlineCycles) ? "可用" : "不可用";
        }
    }

    private final Map<String, Backend> byId = new ConcurrentHashMap<>();
    private final int offlineCycles;
    private final LongSupplier clock;

    public BackendRegistry(int offlineCycles) {
        this(offlineCycles, System::currentTimeMillis);
    }

    /** 供测试注入时钟。 */
    public BackendRegistry(int offlineCycles, LongSupplier clock) {
        this.offlineCycles = Math.max(1, offlineCycles);
        this.clock = clock;
    }

    public int offlineCycles() {
        return offlineCycles;
    }

    /** 处理注册。重复注册视为同一个 BE 的重新上线。 */
    public synchronized Backend register(RegisterBeRequest request) {
        long now = clock.getAsLong();
        int interval = request.getHeartbeatIntervalSeconds();
        Backend backend = new Backend(
                nullToEmpty(request.getBeId()),
                nullToEmpty(request.getEndpoint()),
                nullToEmpty(request.getVersion()),
                request.getStartTimeUnixMillis(),
                interval,
                now);
        byId.put(backend.beId(), backend);
        return backend;
    }

    /** 处理心跳。缺失字段按 0/空处理,不拒收(FR-108)。 */
    public synchronized Backend heartbeat(HeartbeatRequest request) {
        long now = clock.getAsLong();
        int interval = request.getHeartbeatIntervalSeconds();
        String beId = nullToEmpty(request.getBeId());
        Backend existing = byId.get(beId);
        if (existing == null) {
            // FR-368 / K58:收到未登记过的 be_id 心跳时隐式注册。
            // 这样 FE/CLI 重启后不必重启 BE 也能重新看到 BE 状态(P2 §11 G2)。
            existing = new Backend(beId,
                    nullToEmpty(request.getEndpoint()),
                    nullToEmpty(request.getVersion()),
                    request.getStartTimeUnixMillis(),
                    interval,
                    now);
            byId.put(beId, existing);
            LOG.info("收到未知 be_id 的心跳,已隐式注册: " + beId + " @ " + existing.endpoint());
        }
        existing.lastSeenMillis = now;
        existing.lastHeartbeat = request;
        existing.dataDir = nullToEmpty(request.getDataDir());
        return existing;
    }

    public List<Backend> all() {
        List<Backend> list = new ArrayList<>(byId.values());
        list.sort(Comparator.comparing(Backend::beId));
        return list;
    }

    public boolean anyAvailable() {
        long now = clock.getAsLong();
        for (Backend backend : byId.values()) {
            if (backend.available(now, offlineCycles)) {
                return true;
            }
        }
        return false;
    }

    public Optional<Backend> firstAvailable() {
        long now = clock.getAsLong();
        return all().stream().filter(b -> b.available(now, offlineCycles)).findFirst();
    }

    /** 给"BE 不可用"错误用的说明:地址 + 最后心跳时间。 */
    public String unavailableHint() {
        List<Backend> list = all();
        if (list.isEmpty()) {
            return "尚未收到任何 BE 的心跳";
        }
        Backend latest = list.stream()
                .max(Comparator.comparingLong(Backend::lastSeenMillis))
                .orElse(list.get(0));
        return latest.endpoint() + ",最后心跳 " + latest.lastSeenText();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
