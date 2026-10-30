package com.wangyu.tangdb.storage;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * SPEC-P7.1 D 组(K182 / FR-805):路径 A 的允许集编码。
 *
 * <p>允许集 ≤ {@link #KEY_LIMIT} 个主键时编码成"键集合"([u32 长度][key] 拼接),
 * 超过则编码成布隆过滤器位图;两种编码都由 BE 解析,BE 只按字节比较,**不认识主键语义**。</p>
 *
 * <p>布隆哈希必须与 BE 的 {@code store.fnv1a64} 逐位一致(同一份 FNV-1a 64 + 双哈希),
 * 否则允许集会全部错过、路径 A 等于空集。</p>
 */
public final class AllowListCodec {

    /** K182:允许集主键数上限;超过改用布隆过滤器。 */
    public static final int KEY_LIMIT = 10_000;

    /** 每个 key 分配的位数(10 bit/key ≈ 1% 假阳性,k=7)。 */
    private static final int BITS_PER_KEY = 10;
    private static final int HASH_COUNT = 7;

    private AllowListCodec() {
    }

    /** 编码后的允许集:bytes + 是否需要按布隆解释。 */
    public record Encoded(byte[] bytes, boolean bloom, int size) {
    }

    /**
     * 把主键字节编码成路径 A 的允许集。
     *
     * @param keys 主键用户键(与 BE 图节点里的 key 完全一致)
     */
    public static Encoded encode(List<byte[]> keys) {
        if (keys.size() <= KEY_LIMIT) {
            return new Encoded(encodeKeys(keys), false, keys.size());
        }
        return new Encoded(encodeBloom(keys), true, keys.size());
    }

    /** 键集合:[u32 长度][key] …(小端,与 BE 的 parseVecAllow 对齐)。 */
    static byte[] encodeKeys(List<byte[]> keys) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(keys.size() * 12);
        for (byte[] key : keys) {
            int n = key.length;
            out.write(n & 0xFF);
            out.write((n >>> 8) & 0xFF);
            out.write((n >>> 16) & 0xFF);
            out.write((n >>> 24) & 0xFF);
            out.write(key, 0, n);
        }
        return out.toByteArray();
    }

    /** 布隆:[u32 mbits][u32 k][位图]。 */
    static byte[] encodeBloom(List<byte[]> keys) {
        long bits = Math.max(64L, (long) keys.size() * BITS_PER_KEY);
        bits = (bits + 7) & ~7L; // 按字节对齐
        int bytes = (int) ((bits + 7) / 8);
        byte[] bitmap = new byte[bytes];
        for (byte[] key : keys) {
            long h1 = fnv1a64(key);
            long h2 = (h1 >>> 32) | 1L;
            for (int i = 0; i < HASH_COUNT; i++) {
                // 必须用**无符号**取模:哈希和在 64 位上是回绕的,Java 的有符号 floorMod
                // 与 Go 的 uint64 % 不等价(m 不是 2 的幂时会算到不同的位),
                // 那会让 BE 侧出现大量假阴性、允许集等于空集。
                long bit = Long.remainderUnsigned(h1 + (long) i * h2, bits);
                bitmap[(int) (bit >>> 3)] |= (byte) (1 << (bit & 7));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(8 + bytes);
        writeLe32(out, (int) bits);
        writeLe32(out, HASH_COUNT);
        out.write(bitmap, 0, bytes);
        return out.toByteArray();
    }

    /** 与 BE store.fnv1a64 必须逐位一致。 */
    static long fnv1a64(byte[] data) {
        long h = 0xcbf29ce484222325L;
        for (byte b : data) {
            h ^= (b & 0xFFL);
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static void writeLe32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }
}
