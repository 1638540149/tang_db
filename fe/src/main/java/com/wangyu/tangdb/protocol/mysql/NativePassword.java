package com.wangyu.tangdb.protocol.mysql;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * {@code mysql_native_password} 挑战-响应认证(SPEC-P3.5 FR-433 / K63)。
 *
 * <p>口令不明文过网:客户端回 {@code SHA1(pw) XOR SHA1(seed + SHA1(SHA1(pw)))}。
 * 服务端只保存 {@code SHA1(SHA1(pw))}(启动时由配置明文派生,不落盘、不进日志)。</p>
 */
public final class NativePassword {

    private NativePassword() {
    }

    /** 服务端启动时派生口令的存储形态(K63):SHA1(SHA1(pw))。 */
    public static byte[] deriveStoredHash(String password) {
        return sha1(sha1(bytes(password)));
    }

    /**
     * 生成 20 字节随机挑战盐。
     *
     * <p>只用可打印 ASCII 字母数字,原因有三:① 握手 part-2 按 NUL 结尾解析,不能含 0x00;
     * ② 官方客户端(如 Connector/J)会把 seed 当 String 存下再转回字节,非 ASCII 字节在
     * 字符集往返里会被替换/截断,导致挑战-响应校验失败;③ ASCII 在任何 ASCII 兼容字符集里
     * 往返都是字节不变的。</p>
     */
    public static byte[] randomSeed() {
        byte[] seed = new byte[20];
        SecureRandom random = new SecureRandom();
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int i = 0; i < seed.length; i++) {
            seed[i] = (byte) alphabet.charAt(random.nextInt(alphabet.length()));
        }
        return seed;
    }

    /** 客户端计算认证 token。 */
    public static byte[] scramble(byte[] password, byte[] seed) {
        byte[] stage1 = sha1(password);
        byte[] stage2 = sha1(stage1);
        byte[] inner = sha1(concat(seed, stage2));
        byte[] token = new byte[stage1.length];
        for (int i = 0; i < token.length; i++) {
            token[i] = (byte) (stage1[i] ^ inner[i]);
        }
        return token;
    }

    /** 服务端校验 token。 */
    public static boolean verify(byte[] storedHash, byte[] seed, byte[] token) {
        if (storedHash == null || token == null) {
            return false;
        }
        byte[] inner = sha1(concat(seed, storedHash));
        byte[] stage1 = new byte[token.length];
        for (int i = 0; i < token.length; i++) {
            stage1[i] = (byte) (token[i] ^ inner[i]);
        }
        byte[] recomputed = sha1(stage1);
        return constantTimeEquals(recomputed, storedHash);
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 不可用", e);
        }
    }

    private static byte[] bytes(String s) {
        return s == null ? new byte[0] : s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
