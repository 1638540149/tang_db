package com.wangyu.tangdb.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 滑动窗口延迟直方图(SPEC-P8 K189)。
 *
 * <p>窗口按秒切成 N 个槽,每槽一个固定桶位的计数数组(1ms / 2 / 5 / 10 / 20 / 50 / 100 /
 * 200 / 500 / 1s / 2s / 5s / 10s / 30s / 更大)。分位数取"累计计数首次达到目标值的桶的上界",
 * 结果可复现、不依赖任何第三方 HDR 库;窗口内没有请求时返回 0(边界 183)。</p>
 */
public final class LatencyWindow {

    /** 桶上界(毫秒),最后一档是"超过 30s"。 */
    private static final int[] BOUNDS = {1, 2, 3, 4, 5, 7, 10, 15, 20, 30, 40, 50, 70, 100,
            150, 200, 300, 400, 500, 700, 1000, 1500, 2000, 3000, 5000, 7000, 10000, 15000, 30000};
    private static final int BUCKETS = BOUNDS.length;
    private static final int STRIDE = BUCKETS + 1; // 最后一格:计数

    private final int slots;
    private final AtomicLongArray counts;
    private final AtomicLongArray secondTags;
    private final AtomicLong total = new AtomicLong();
    private final AtomicLong sumMillis = new AtomicLong();

    public LatencyWindow(int seconds) {
        this.slots = Math.max(1, seconds);
        this.counts = new AtomicLongArray(slots * STRIDE);
        this.secondTags = new AtomicLongArray(slots);
        for (int i = 0; i < slots; i++) {
            secondTags.set(i, -1L);
        }
    }

    /** 记录一次耗时(毫秒)。 */
    public void record(long millis) {
        long ms = Math.max(0, millis);
        total.incrementAndGet();
        sumMillis.addAndGet(ms);
        long second = System.currentTimeMillis() / 1000L;
        int slot = (int) (second % slots);
        if (secondTags.get(slot) != second) {
            synchronized (secondTags) {
                if (secondTags.get(slot) != second) {
                    secondTags.set(slot, second);
                    int base = slot * STRIDE;
                    for (int i = 0; i < STRIDE; i++) {
                        counts.set(base + i, 0L);
                    }
                }
            }
        }
        counts.incrementAndGet(slot * STRIDE + bucketOf(ms));
    }

    private static int bucketOf(long millis) {
        for (int i = 0; i < BUCKETS; i++) {
            if (millis <= BOUNDS[i]) {
                return i;
            }
        }
        return BUCKETS;
    }

    /**
     * 窗口内分位数(毫秒)。{@code q} 取 0.5 / 0.95 / 0.99。
     * 空窗口返回 0;命中最后一档时返回 30000。
     */
    public long quantile(double q) {
        long[] aggregated = new long[STRIDE];
        for (int slot = 0; slot < slots; slot++) {
            int base = slot * STRIDE;
            for (int i = 0; i < STRIDE; i++) {
                aggregated[i] += counts.get(base + i);
            }
        }
        long windowTotal = 0;
        for (int i = 0; i < STRIDE; i++) {
            windowTotal += aggregated[i];
        }
        if (windowTotal == 0) {
            return 0;
        }
        long target = (long) Math.ceil(q * windowTotal);
        if (target < 1) {
            target = 1;
        }
        long cumulative = 0;
        for (int i = 0; i < STRIDE; i++) {
            cumulative += aggregated[i];
            if (cumulative >= target) {
                return i < BUCKETS ? BOUNDS[i] : BOUNDS[BUCKETS - 1];
            }
        }
        return BOUNDS[BUCKETS - 1];
    }

    /** 窗口内请求数。 */
    public long windowCount() {
        long windowTotal = 0;
        for (int slot = 0; slot < slots; slot++) {
            int base = slot * STRIDE;
            for (int i = 0; i < STRIDE; i++) {
                windowTotal += counts.get(base + i);
            }
        }
        return windowTotal;
    }

    /** 窗口内请求数 / 窗口秒数 = 平均 QPS。 */
    public double qps() {
        return (double) windowCount() / slots;
    }

    /** 进程启动以来的累计请求数。 */
    public long total() {
        return total.get();
    }

    /** 累计耗时(毫秒)。 */
    public long sumMillis() {
        return sumMillis.get();
    }

    public int seconds() {
        return slots;
    }
}
