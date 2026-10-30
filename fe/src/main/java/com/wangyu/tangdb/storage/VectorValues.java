package com.wangyu.tangdb.storage;

/**
 * 向量值工具(SPEC-P7 A 组):字面量解析、维度校验、距离计算。
 *
 * <p><b>为什么自己在 FE 里算距离</b>:依赖红线禁止任何第三方向量/线性代数库(K157 / SC-438),
 * 距离算法必须自己写。图段内部的距离由 BE 侧另一份等价实现计算(SPEC-P7 K161),
 * 两侧都只处理"float32 数组",不共享代码。</p>
 *
 * <p><b>三个距离函数的口径</b>(全部"越小越相似",这样 {@code ORDER BY fn(...) LIMIT k}
 * 的语义一致):</p>
 * <ul>
 *   <li>{@code l2_distance} —— 欧氏距离 sqrt(Σ(a-b)²);</li>
 *   <li>{@code cosine_distance} —— 1 - cos(a,b);全零向量无定义,返回 1(最不相似);</li>
 *   <li>{@code inner_product} —— 内积的<b>相反数</b> -Σa·b(与 pgvector 的 {@code <#>} 同口径),
 *       避免"越大越相似"与 ORDER BY 升序打架。</li>
 * </ul>
 */
public final class VectorValues {

    /** 度量操作码(与 {@code proto/vector_metric} 及 BE 图段元信息的 metric 字节一致)。 */
    public static final int METRIC_L2 = 0;
    public static final int METRIC_COSINE = 1;
    public static final int METRIC_INNER_PRODUCT = 2;

    private VectorValues() {
    }

    /** 度量名 → 操作码;不认识返回 -1。 */
    public static int metricCode(String rawName) {
        if (rawName == null) {
            return -1;
        }
        return switch (rawName.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "l2", "l2_distance", "euclidean" -> METRIC_L2;
            case "cosine", "cosine_distance" -> METRIC_COSINE;
            case "inner_product", "ip" -> METRIC_INNER_PRODUCT;
            default -> -1;
        };
    }

    public static String metricName(int code) {
        return switch (code) {
            case METRIC_COSINE -> "cosine";
            case METRIC_INNER_PRODUCT -> "inner_product";
            default -> "l2";
        };
    }

    /**
     * 解析向量字面量 {@code '[a,b,c]'}(SPEC-P7 FR-733 / 边界 126~128)。
     *
     * @param expectedDim 期望维度;0 表示不校验(两列比较时由调用方分别校验)
     * @throws StoreException 元素个数不符 / 非数值 / NaN / Inf / 格式错(带元素序号)
     */
    public static float[] parseLiteral(String text, int expectedDim) {
        if (text == null) {
            throw new StoreException("向量字面量不能为 NULL");
        }
        String s = text.trim();
        if (s.length() < 2 || s.charAt(0) != '[' || s.charAt(s.length() - 1) != ']') {
            throw new StoreException("向量字面量格式非法(期望 '[a,b,…]'): " + text);
        }
        String inner = s.substring(1, s.length() - 1).trim();
        float[] out;
        if (inner.isEmpty()) {
            out = new float[0];
            if (expectedDim > 0) {
                throw new StoreException("向量字面量元素个数不符(期望 " + expectedDim + " 个,实际 0 个)");
            }
            return out;
        }
        String[] parts = inner.split(",", -1);
        out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String token = parts[i].trim();
            if (token.isEmpty()) {
                throw new StoreException("向量字面量第 " + (i + 1) + " 个元素为空");
            }
            float v;
            try {
                v = Float.parseFloat(token);
            } catch (NumberFormatException e) {
                throw new StoreException("向量字面量第 " + (i + 1) + " 个元素不是数值: " + token);
            }
            if (Float.isNaN(v)) {
                throw new StoreException("向量字面量第 " + (i + 1) + " 个元素是 NaN");
            }
            if (Float.isInfinite(v)) {
                throw new StoreException("向量字面量第 " + (i + 1) + " 个元素是 Inf");
            }
            out[i] = v;
        }
        if (expectedDim > 0 && out.length != expectedDim) {
            throw new StoreException("向量字面量元素个数不符(期望 " + expectedDim
                    + " 个,实际 " + out.length + " 个)");
        }
        return out;
    }

    /** 把一个值(列值 float[] 或字面量字符串)转成向量,并按维度校验。 */
    public static float[] toVector(Object value, int expectedDim, String what) {
        if (value == null) {
            return null;
        }
        if (value instanceof float[] vec) {
            if (expectedDim > 0 && vec.length != expectedDim) {
                throw new StoreException(what + " 的向量维度不符(期望 " + expectedDim
                        + ",实际 " + vec.length + ")");
            }
            return vec;
        }
        if (value instanceof String s) {
            return parseLiteral(s, expectedDim);
        }
        throw new StoreException(what + " 不是向量值(期望 VECTOR 列或 '[a,b,…]' 字面量)");
    }

    /** 展示成 {@code [0.12,0.34]} 形式(结果表格 / SHOW CREATE 用)。 */
    public static String format(float[] vec) {
        if (vec == null) {
            return "NULL";
        }
        StringBuilder sb = new StringBuilder(vec.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Float.toString(vec[i]));
        }
        return sb.append(']').toString();
    }

    /** 按度量计算距离(越小越相似,SQ 见类注释)。两侧维度必须一致。 */
    public static double distance(int metric, float[] a, float[] b) {
        if (a == null || b == null) {
            throw new StoreException("向量距离计算的入参不能为 NULL");
        }
        if (a.length != b.length) {
            throw new StoreException("向量距离计算维度不一致(" + a.length + " vs " + b.length + ")");
        }
        return switch (metric) {
            case METRIC_COSINE -> cosineDistance(a, b);
            case METRIC_INNER_PRODUCT -> -innerProduct(a, b);
            default -> l2(a, b);
        };
    }

    /** 欧氏距离。 */
    public static double l2(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double d = (double) a[i] - b[i];
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    /** 内积 Σa·b。 */
    public static double innerProduct(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    /** 1 - cos(a,b);任意一侧全零时返回 1(与 pgvector 一致:<=> 对零向量不确定,这里取最不相似)。 */
    public static double cosineDistance(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 1.0;
        }
        double cos = dot / (Math.sqrt(na) * Math.sqrt(nb));
        return 1.0 - cos;
    }

    /** 小端 float32 编码(4×n 字节),与 BE 图段/行值口径一致。 */
    public static byte[] toBytes(float[] vec) {
        byte[] out = new byte[vec.length * 4];
        for (int i = 0; i < vec.length; i++) {
            int bits = Float.floatToRawIntBits(vec[i]);
            int base = i * 4;
            out[base] = (byte) bits;
            out[base + 1] = (byte) (bits >>> 8);
            out[base + 2] = (byte) (bits >>> 16);
            out[base + 3] = (byte) (bits >>> 24);
        }
        return out;
    }

    /** 小端 float32 解码。 */
    public static float[] fromBytes(byte[] raw, int expectedDim) {
        if (raw == null || raw.length % 4 != 0) {
            throw new StoreException("向量字节长度非法: " + (raw == null ? "null" : raw.length));
        }
        int n = raw.length / 4;
        if (expectedDim > 0 && n != expectedDim) {
            throw new StoreException("向量字节维度不符(期望 " + expectedDim + ",实际 " + n + ")");
        }
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            int base = i * 4;
            int bits = (raw[base] & 0xff)
                    | ((raw[base + 1] & 0xff) << 8)
                    | ((raw[base + 2] & 0xff) << 16)
                    | ((raw[base + 3] & 0xff) << 24);
            out[i] = Float.intBitsToFloat(bits);
        }
        return out;
    }

    /** 校验向量里没有 NaN / Inf(推理返回校验用,FR-776)。 */
    public static void checkFinite(float[] vec, String what) {
        for (int i = 0; i < vec.length; i++) {
            if (Float.isNaN(vec[i]) || Float.isInfinite(vec[i])) {
                throw new StoreException(what + " 第 " + (i + 1) + " 个元素非法("
                        + (Float.isNaN(vec[i]) ? "NaN" : "Inf") + ")");
            }
        }
    }
}
