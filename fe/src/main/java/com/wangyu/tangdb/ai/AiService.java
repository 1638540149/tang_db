package com.wangyu.tangdb.ai;

import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.query.JsonCodec;
import com.wangyu.tangdb.storage.StoreException;
import com.wangyu.tangdb.storage.VectorValues;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SPEC-P7 E 组:推理下推的 FE 侧算子(AiService = EmbedOp 的执行体,K169)。
 *
 * <p>职责:把一批文本按 {@code ai_batch_rows} 分批、走幂等键 + LRU 缓存、调外部端点、
 * 校验维度 / NaN / Inf / 模型版本,失败按"指数退避 + 重试上限 + 熔断"处理,
 * 任何一批失败都抛错(<b>该批不进入写路径</b>,由调用方回滚整条语句,FR-775 / K171)。</p>
 *
 * <p>端点协议(库只认"一个推理端点",权重不进库):</p>
 * <pre>
 * POST &lt;ai_endpoint&gt;
 * {"model":"&lt;ai_model&gt;","texts":["...","..."]}
 * ← 200 {"model_version":"v3","dim":128,"vectors":[[0.1,...],[...]]}
 * </pre>
 *
 * <p><b>边界</b>:这是 FE 侧的执行编排,BE 完全不知道"模型 / 文本 / 推理"这些概念(K169)。</p>
 */
public final class AiService {

    /** 端点配置(§5.4)。 */
    public record Config(String endpoint, String model, int dim, int timeoutMs, int batchRows,
                         int cacheEntries, int maxRetries, int breakerBatches) {

        public Config {
            if (endpoint == null) {
                endpoint = "";
            }
            if (model == null) {
                model = "";
            }
            if (dim < 0) {
                dim = 0;
            }
            if (timeoutMs <= 0) {
                timeoutMs = 5_000;
            }
            if (batchRows <= 0) {
                batchRows = 1_024;
            }
            if (cacheEntries < 0) {
                cacheEntries = 10_000;
            }
            if (maxRetries <= 0) {
                maxRetries = 3;
            }
            if (breakerBatches <= 0) {
                breakerBatches = 5;
            }
        }

        public static Config disabled() {
            return new Config("", "", 0, 5_000, 1_024, 10_000, 3, 5);
        }

        public boolean configured() {
            return !endpoint.isBlank() && dim > 0;
        }

        /** 缺哪个配置项(FR-779):按 checkpoint 顺序指出第一个缺失项。 */
        public String missingItem() {
            if (endpoint.isBlank()) {
                return "ai_endpoint";
            }
            if (dim <= 0) {
                return "ai_dim";
            }
            if (model.isBlank()) {
                return "ai_model";
            }
            return "";
        }
    }

    /** 可观测统计(FR-780:调用次数 / 缓存命中率 / 失败次数 / 平均批量 / 平均延迟)。 */
    public record Stats(long calls, long cacheHits, long cacheMisses, long failures,
                        long retries, long texts, long totalMillis, int cachedEntries,
                        boolean breakerOpen) {

        public double cacheHitRate() {
            long lookups = cacheHits + cacheMisses;
            return lookups == 0 ? 0 : (double) cacheHits / lookups;
        }

        public double averageBatch() {
            return calls == 0 ? 0 : (double) texts / calls;
        }

        public double averageLatencyMillis() {
            return calls == 0 ? 0 : (double) totalMillis / calls;
        }
    }

    private static final int MAX_TEXT_BYTES = 64 * 1024;

    private final Config config;
    private final HttpClient http;
    private final Map<String, float[]> cache;
    private final Object lock = new Object();

    private long calls;
    private long cacheHits;
    private long cacheMisses;
    private long failures;
    private long retries;
    private long texts;
    private long totalMillis;
    private int consecutiveFailedBatches;
    private boolean breakerOpen;

    /** 算子实例号(每个 AiService 一个)+ 批次序号 → 批次 ID(FR-781 日志用)。 */
    private static final java.util.concurrent.atomic.AtomicLong OPERATOR_SEQ =
            new java.util.concurrent.atomic.AtomicLong();
    private final long operatorId = OPERATOR_SEQ.incrementAndGet();
    private long batchSeq;

    public AiService(Config config) {
        this.config = config == null ? Config.disabled() : config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.config.timeoutMs()))
                .build();
        int capacity = Math.max(0, this.config.cacheEntries());
        this.cache = new LinkedHashMap<>(Math.min(1024, Math.max(16, capacity)), 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                return size() > capacity;
            }
        };
    }

    public Config config() {
        return config;
    }

    /**
     * 把一批文本转成向量:先查缓存(命中绝不重复调用),未命中的按 {@code ai_batch_rows}
     * 分批请求端点。**任何一批失败都抛错**,调用方必须让整条语句失败、一个字都不写。
     */
    public List<float[]> embedAll(List<String> rawTexts) {
        if (!config.configured()) {
            String missing = config.missingItem();
            throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                    "ai_embed() 需要推理端点配置,缺少 " + missing
                            + "(在 config/fe.conf 里配置 ai_endpoint / ai_model / ai_dim,FR-779)");
        }
        List<String> normalized = new ArrayList<>(rawTexts.size());
        for (int i = 0; i < rawTexts.size(); i++) {
            normalized.add(normalize(rawTexts.get(i), i + 1));
        }
        float[][] out = new float[normalized.size()][];
        // 未命中的按幂等键去重:同一批里的重复文本只送一次(FR-774)
        java.util.LinkedHashMap<String, Integer> misses = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, String> missText = new java.util.LinkedHashMap<>();
        for (int i = 0; i < normalized.size(); i++) {
            String text = normalized.get(i);
            String key = idempotencyKey(text);
            float[] hit;
            synchronized (lock) {
                hit = cache.get(key);
                if (hit == null) {
                    cacheMisses++;
                } else {
                    cacheHits++;
                }
            }
            if (hit != null) {
                out[i] = hit;
            } else if (!misses.containsKey(key)) {
                misses.put(key, i);
                missText.put(key, text);
            }
        }
        List<String> uniqueKeys = new ArrayList<>(misses.keySet());
        int batchRows = config.batchRows();
        for (int start = 0; start < uniqueKeys.size(); start += batchRows) {
            int end = Math.min(uniqueKeys.size(), start + batchRows);
            List<String> batchTexts = new ArrayList<>(end - start);
            for (int i = start; i < end; i++) {
                batchTexts.add(missText.get(uniqueKeys.get(i)));
            }
            long batchId = operatorId * 1_000_000L + (++batchSeq);
            if (System.getenv("TANGDB_AI_QUIET") == null) {
                // FR-781:批次 ID = 算子实例 + 批次序号;只打批次大小与首个幂等键前缀,
                // 不打印文本内容(避免把业务数据落进日志)。
                System.err.println("信息: ai_embed 批次 batch_id=" + batchId
                        + " texts=" + batchTexts.size()
                        + " first_key=" + prefix(idempotencyKey(batchTexts.get(0))));
            }
            List<float[]> vectors = embedBatch(batchTexts);
            synchronized (lock) {
                for (int i = 0; i < vectors.size(); i++) {
                    cache.put(uniqueKeys.get(start + i), vectors.get(i));
                }
            }
        }
        // 未命中行的结果按幂等键回填
        for (int i = 0; i < normalized.size(); i++) {
            if (out[i] != null) {
                continue;
            }
            String key = idempotencyKey(normalized.get(i));
            synchronized (lock) {
                out[i] = cache.get(key);
            }
            if (out[i] == null) {
                throw new StoreException("推理缓存回填失败(键 " + key + ")");
            }
        }
        List<float[]> result = new ArrayList<>(out.length);
        for (float[] vec : out) {
            result.add(vec);
        }
        return result;
    }

    /** 单批:超时 + 指数退避重试 + 熔断 + 返回校验(FR-773 / FR-775 / FR-776)。 */
    private List<float[]> embedBatch(List<String> batch) {
        synchronized (lock) {
            if (breakerOpen) {
                throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                        "推理端点连续失败已达熔断阈值(" + config.breakerBatches()
                                + " 批),后续批次直接失败(FR-775 / 边界 145)");
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.model());
        payload.put("texts", batch);
        byte[] body = JsonCodec.toText(payload).getBytes(StandardCharsets.UTF_8);
        RuntimeException last = null;
        int attempts = Math.max(1, config.maxRetries() + 1);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            long started = System.nanoTime();
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(config.endpoint()))
                        .timeout(Duration.ofMillis(config.timeoutMs()))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
                HttpResponse<byte[]> response = http.send(request,
                        HttpResponse.BodyHandlers.ofByteArray());
                long millis = (System.nanoTime() - started) / 1_000_000L;
                if (response.statusCode() / 100 != 2) {
                    throw new StoreException("推理端点返回 HTTP " + response.statusCode()
                            + "(批次 " + batch.size() + " 行)");
                }
                List<float[]> vectors = parseResponse(response.body(), batch.size());
                synchronized (lock) {
                    calls++;
                    texts += batch.size();
                    totalMillis += millis;
                    consecutiveFailedBatches = 0;
                }
                return vectors;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StoreException("推理调用被中断", e);
            } catch (RuntimeException e) {
                last = e;
                synchronized (lock) {
                    failures++;
                }
                if (attempt < attempts) {
                    synchronized (lock) {
                        retries++;
                    }
                    sleepBackoff(attempt);
                }
            } catch (java.io.IOException e) {
                last = new StoreException("推理端点不可用: " + e.getMessage(), e);
                synchronized (lock) {
                    failures++;
                }
                if (attempt < attempts) {
                    synchronized (lock) {
                        retries++;
                    }
                    sleepBackoff(attempt);
                }
            }
        }
        synchronized (lock) {
            consecutiveFailedBatches++;
            if (consecutiveFailedBatches >= config.breakerBatches()) {
                breakerOpen = true;
            }
        }
        throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                "推理调用失败(批次 " + batch.size() + " 行,已重试 " + config.maxRetries()
                        + " 次):" + (last == null ? "未知错误" : last.getMessage()));
    }

    private void sleepBackoff(int attempt) {
        long millis = Math.min(2_000L, 50L * (1L << Math.min(6, attempt - 1)));
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreException("推理重试等待被中断", e);
        }
    }

    /** 解析并校验端点响应:条数 / 维度 / NaN / Inf / 模型版本(FR-776)。 */
    private List<float[]> parseResponse(byte[] body, int expectedRows) {
        Object doc = JsonCodec.parseText(new String(body, StandardCharsets.UTF_8));
        if (!(doc instanceof Map<?, ?> map)) {
            throw new StoreException("推理端点响应不是 JSON 对象");
        }
        Object version = map.get("model_version");
        if (version != null && !config.model().isBlank()
                && !config.model().equals(String.valueOf(version))) {
            throw new StoreException("模型版本与配置不一致(端点 " + version + ",配置 "
                    + config.model() + ",FR-776)");
        }
        Object dim = map.get("dim");
        if (dim instanceof Number number && number.intValue() != config.dim()) {
            throw new StoreException("端点返回维度 " + number.intValue() + " 与 ai_dim="
                    + config.dim() + " 不一致(FR-776)");
        }
        Object raw = map.get("vectors");
        if (!(raw instanceof List<?> rows)) {
            throw new StoreException("推理端点响应缺少 vectors 数组");
        }
        if (rows.size() != expectedRows) {
            throw new StoreException("推理端点返回 " + rows.size() + " 条向量,期望 " + expectedRows);
        }
        List<float[]> out = new ArrayList<>(rows.size());
        for (int r = 0; r < rows.size(); r++) {
            if (!(rows.get(r) instanceof List<?> values)) {
                throw new StoreException("推理端点第 " + (r + 1) + " 条向量不是数组");
            }
            if (values.size() != config.dim()) {
                throw new StoreException("推理端点第 " + (r + 1) + " 条向量维度 "
                        + values.size() + " 与 ai_dim=" + config.dim() + " 不一致(FR-776)");
            }
            float[] vec = new float[values.size()];
            for (int i = 0; i < vec.length; i++) {
                Object v = values.get(i);
                if (!(v instanceof Number number)) {
                    throw new StoreException("推理端点第 " + (r + 1) + " 条向量第 " + (i + 1)
                            + " 个元素不是数值");
                }
                vec[i] = number.floatValue();
            }
            VectorValues.checkFinite(vec, "推理端点第 " + (r + 1) + " 条向量");
            out.add(vec);
        }
        return out;
    }

    /** 文本规范化(FR-782):去首尾空白、连续空白折叠为单空格、UTF-8;空文本直接报错。 */
    static String normalize(String text, int rowNo) {
        if (text == null) {
            throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                    "ai_embed() 第 " + rowNo + " 行文本为 NULL(空文本不调模型,FR-782)");
        }
        String normalized = text.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                    "ai_embed() 第 " + rowNo + " 行文本为空(空文本不调模型,FR-782 / 边界 146)");
        }
        int bytes = normalized.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_TEXT_BYTES) {
            throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                    "ai_embed() 第 " + rowNo + " 行文本 " + bytes + " 字节,超过上限 "
                            + MAX_TEXT_BYTES + "(边界 147)");
        }
        return normalized;
    }

    private static String prefix(String key) {
        return key.length() <= 12 ? key : key.substring(0, 12) + "…";
    }

    /** 幂等键 = sha256(模型版本 + 维度 + 规范化文本)(FR-774 / K170)。 */
    String idempotencyKey(String normalizedText) {
        String material = config.model() + "|" + config.dim() + "|" + normalizedText;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new StoreException("JDK 缺少 SHA-256", e);
        }
    }

    public Stats stats() {
        synchronized (lock) {
            return new Stats(calls, cacheHits, cacheMisses, failures, retries, texts, totalMillis,
                    cache.size(), breakerOpen);
        }
    }

    /** 测试 / 恢复用:清空熔断标记。 */
    public void resetBreaker() {
        synchronized (lock) {
            breakerOpen = false;
            consecutiveFailedBatches = 0;
        }
    }
}
