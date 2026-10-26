package com.wangyu.tangdb.protocol.mysql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** mysql_native_password 挑战-响应认证(FR-433 / K63)。 */
class NativePasswordTest {

    @Test
    void deriveAndVerifyRoundTrip() {
        byte[] stored = NativePassword.deriveStoredHash("tangdb");
        assertEquals(20, stored.length, "SHA1(SHA1(pw)) 是 20 字节");

        byte[] seed = NativePassword.randomSeed();
        assertEquals(20, seed.length);

        byte[] token = NativePassword.scramble("tangdb".getBytes(java.nio.charset.StandardCharsets.UTF_8), seed);
        assertTrue(NativePassword.verify(stored, seed, token), "正确口令应通过");
    }

    @Test
    void wrongPasswordFails() {
        byte[] stored = NativePassword.deriveStoredHash("tangdb");
        byte[] seed = NativePassword.randomSeed();
        byte[] token = NativePassword.scramble("wrong".getBytes(java.nio.charset.StandardCharsets.UTF_8), seed);
        assertFalse(NativePassword.verify(stored, seed, token), "错误口令应被拒");
    }

    @Test
    void nullTokenAndHashAreRejected() {
        byte[] stored = NativePassword.deriveStoredHash("tangdb");
        byte[] seed = NativePassword.randomSeed();
        assertFalse(NativePassword.verify(stored, seed, null));
        assertFalse(NativePassword.verify(null, seed, new byte[20]));
    }

    @Test
    void seedIsPrintableAsciiAndNulFree() {
        // 回归:seed 必须是非 0x00 的可打印 ASCII —— 官方客户端把 seed 当 String 存下再转回字节,
        // 非 ASCII 会在字符集往返里被破坏;0x00 会让 NUL 结尾的 part-2 解析截断。
        for (int round = 0; round < 50; round++) {
            byte[] seed = NativePassword.randomSeed();
            assertEquals(20, seed.length);
            for (byte b : seed) {
                assertTrue(b >= 0x21 && b <= 0x7E, "seed 字节必须是可打印 ASCII: " + (b & 0xFF));
            }
        }
    }
}
