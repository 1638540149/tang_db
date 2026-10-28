package com.wangyu.tangdb.stats;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * 自研 HyperLogLog(SPEC-P6 FR-619 / K123):精度 p,寄存器数 m = 2^p。
 *
 * <p>不引入任何第三方统计库;哈希用本类内的 64 位混合哈希(对"规范化键"计算),
 * 估计用标准 HLL + 小基数线性计数修正。</p>
 *
 * <p>默认 p = 14(m = 16384),相对误差 ≈ 1.04/√m ≈ 0.81%,与规约一致。</p>
 */
public final class HyperLogLog {

    private final int p;
    private final int m;
    private final byte[] registers;
    private final double alpha;

    public HyperLogLog(int precision) {
        int p0 = (precision < 4 || precision > 18) ? 14 : precision;
        this.p = p0;
        this.m = 1 << p0;
        this.registers = new byte[m];
        this.alpha = switch (m) {
            case 16 -> 0.673;
            case 32 -> 0.697;
            case 64 -> 0.709;
            default -> 0.7213 / (1.0 + 1.079 / m);
        };
    }

    /** 理论相对误差界(1.04/√m)。 */
    public double relativeError() {
        return 1.04 / Math.sqrt(m);
    }

    public int precision() {
        return p;
    }

    /** 喂入一个值(使用与 DISTINCT / GROUP BY 相同的规范化口径)。 */
    public void add(Object value) {
        long hash = hash64(normalize(value));
        int idx = (int) (hash >>> (64 - p));
        long rest = hash << p;
        // rest 的低 p 位是移位补的 0,高位是剩下的 64-p 个比特;rank = 前导零个数 + 1。
        int rank = Long.numberOfLeadingZeros(rest) + 1;
        int maxRank = 64 - p + 1;
        if (rank > maxRank) {
            rank = maxRank;
        }
        if ((registers[idx] & 0xFF) < rank) {
            registers[idx] = (byte) rank;
        }
    }

    /** 基数估计(小基数走线性计数,避免 HLL 在小范围的高估)。 */
    public long estimate() {
        double sum = 0;
        int zeros = 0;
        for (byte register : registers) {
            int r = register & 0xFF;
            sum += Math.pow(2.0, -r);
            if (r == 0) {
                zeros++;
            }
        }
        double raw = alpha * m * (double) m / sum;
        if (raw <= 2.5 * m && zeros > 0) {
            return Math.max(0, Math.round(m * Math.log((double) m / zeros)));
        }
        return Math.max(0, Math.round(raw));
    }

    // ------------------------------------------------------------ 规范化与哈希

    /** 规范化:Integer/Long 统一成 Long,保证 1 与 1L 视为同一值;其它类型保持值语义。 */
    static Object normalize(Object value) {
        if (value == null) {
            return NULL_KEY;
        }
        if (value instanceof Integer i) {
            return (long) i;
        }
        if (value instanceof Float f) {
            return (double) f;
        }
        if (value instanceof byte[] b) {
            return java.util.HexFormat.of().formatHex(b);
        }
        return value;
    }

    private static final String NULL_KEY = "\u0000NULL";

    /** 64 位混合哈希(MurmurHash3 风格的 finalizer + 逐字节累积)。 */
    static long hash64(Object key) {
        byte[] bytes;
        if (key instanceof Long l) {
            bytes = new byte[8];
            for (int i = 0; i < 8; i++) {
                bytes[i] = (byte) (l >>> (i * 8));
            }
        } else if (key instanceof Double d) {
            long bits = Double.doubleToLongBits(d);
            bytes = new byte[8];
            for (int i = 0; i < 8; i++) {
                bytes[i] = (byte) (bits >>> (i * 8));
            }
        } else if (key instanceof LocalDate date) {
            return hash64(date.toEpochDay());
        } else if (key instanceof Boolean b) {
            return hash64(b ? 1L : 0L);
        } else {
            bytes = String.valueOf(key).getBytes(StandardCharsets.UTF_8);
        }
        long h = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            h ^= (b & 0xFFL);
            h *= 0x100000001b3L;
        }
        return fmix64(h ^ bytes.length);
    }

    private static long fmix64(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }
}
