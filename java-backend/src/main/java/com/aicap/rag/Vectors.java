package com.aicap.rag;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 向量字节编解码与相似度计算(无第三方依赖,对齐项目"不引 SDK"的一贯风格)。 */
public final class Vectors {

    private Vectors() {
    }

    /** float32 小端连续存储。写入前用 ByteOrder.LITTLE_ENDIAN 显式指定,不依赖平台默认。 */
    public static byte[] toBytes(float[] v) {
        ByteBuffer buf = ByteBuffer.allocate(v.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) {
            buf.putFloat(f);
        }
        return buf.array();
    }

    public static float[] toFloats(byte[] bytes, int dimension) {
        if (bytes == null || bytes.length != dimension * Float.BYTES) {
            throw new IllegalStateException("向量字节长度与维度不符:"
                    + (bytes == null ? "null" : bytes.length) + " vs " + dimension);
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            out[i] = buf.getFloat();
        }
        return out;
    }

    /**
     * 余弦相似度 ∈ [-1, 1]。
     *
     * <p>零向量返回 0 而不是 NaN:NaN 会让后续所有排序比较失去传递性,
     * 表现为"结果顺序随机",极难排查。宁可当它不相似。
     */
    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("维度不一致:" + a.length + " vs " + b.length);
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
