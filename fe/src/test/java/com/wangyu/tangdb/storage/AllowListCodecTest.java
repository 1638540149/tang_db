package com.wangyu.tangdb.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SPEC-P7.1 K182:路径 A 允许集编码。
 *
 * <p>布隆位图必须与 BE 的 {@code store.fnv1a64} 逐位一致 —— 同一组 key 的期望十六进制
 * 在 BE 侧 {@code TestParseVecAllowBloomMatchesFE} 里也断言同一串。</p>
 */
class AllowListCodecTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void keySetLayoutIsLengthPrefixedLittleEndian() {
        byte[] raw = AllowListCodec.encodeKeys(List.of(bytes("a"), bytes("bb")));
        byte[] expected = new byte[] {
                1, 0, 0, 0, 'a',
                2, 0, 0, 0, 'b', 'b'};
        assertArrayEquals(expected, raw);
    }

    @Test
    void bloomMatchesBeBitForBit() {
        List<byte[]> keys = new ArrayList<>();
        keys.add(bytes("alpha"));
        keys.add(bytes("beta"));
        keys.add(bytes("gamma"));
        byte[] raw = AllowListCodec.encodeBloom(keys);
        // mbits=64、k=7 + 8 字节位图;与 BE store.TestParseVecAllowBloomMatchesFE 同一串
        assertEquals("400000000700000004228c79d24c0001", hex(raw));
    }

    /**
     * m 不是 2 的幂时(10 key → 104 bit),位图必须与 BE 逐位一致。
     *
     * <p>这个用例钉住一个真实 bug:哈希和在 64 位上是回绕的,用 Java 的
     * {@code Math.floorMod} 与 Go 的 {@code uint64 %} 在 m 不整除 2^64 时会算到不同的位,
     * 结果 BE 侧全是假阴性、允许集等于空集(路径 A 直接退化成不安全的无过滤检索)。</p>
     */
    @Test
    void bloomMatchesBeWhenBitCountIsNotPowerOfTwo() {
        // 这些 key 的 FNV-1a 哈希顶位为 1(有符号 long 为负):正是 floorMod 与
        // 无符号取模会分岔的那一半。m=104 又整除不了 2^64,所以能钉住这个 bug。
        List<byte[]> keys = new ArrayList<>();
        for (int i = 400; i < 410; i++) {
            keys.add(bytes("key" + i));
        }
        byte[] raw = AllowListCodec.encodeBloom(keys);
        assertEquals("68000000070000006cf2b4bb0eb433c88389422d57", hex(raw));
    }

    @Test
    void choosesKeySetUpToLimitAndBloomAbove() {
        List<byte[]> keys = new ArrayList<>();
        for (int i = 0; i < AllowListCodec.KEY_LIMIT; i++) {
            keys.add(bytes("k" + i));
        }
        AllowListCodec.Encoded atLimit = AllowListCodec.encode(keys);
        assertFalse(atLimit.bloom());
        assertEquals(AllowListCodec.KEY_LIMIT, atLimit.size());

        keys.add(bytes("k-over-limit"));
        AllowListCodec.Encoded overLimit = AllowListCodec.encode(keys);
        assertTrue(overLimit.bloom());
    }

    @Test
    void keySetRoundTripHasNoFalseNegative() {
        List<byte[]> keys = List.of(bytes("x"), bytes("yy"), bytes("zzz"));
        AllowListCodec.Encoded encoded = AllowListCodec.encode(keys);
        // 与 BE parseVecAllow 同构的最小解析,确认长度前缀可往返
        int pos = 0;
        List<String> out = new ArrayList<>();
        byte[] raw = encoded.bytes();
        while (pos + 4 <= raw.length) {
            int n = (raw[pos] & 0xFF) | (raw[pos + 1] & 0xFF) << 8
                    | (raw[pos + 2] & 0xFF) << 16 | (raw[pos + 3] & 0xFF) << 24;
            pos += 4;
            out.add(new String(raw, pos, n, StandardCharsets.UTF_8));
            pos += n;
        }
        assertEquals(List.of("x", "yy", "zzz"), out);
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
