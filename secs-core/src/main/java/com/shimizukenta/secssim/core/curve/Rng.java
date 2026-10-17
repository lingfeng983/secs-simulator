package com.shimizukenta.secssim.core.curve;

/**
 * 确定性噪声源。
 *
 * <p>用 64 位混合哈希把 {@code (seed, 时间网格)} 映射成伪随机值，
 * 再在网格间做平滑插值，得到一条"看起来随机、但完全可复现"的连续曲线。
 * </p>
 *
 * <p>相比 {@code java.util.Random} 的关键优势：<b>与调用次数无关</b>。
 * 同一个时刻永远得到同一个值，因此可以乱序求值、可以跳采、可以加速回放。
 * </p>
 */
public final class Rng {

    private final long seed;

    /**
     * 创建噪声源。
     *
     * @param seed 种子
     */
    public Rng(long seed) {
        this.seed = seed;
    }

    /**
     * 派生一个独立子流。
     *
     * <p>按 SVID / 模板实例派生，保证"新增一个变量不会改变其它变量的曲线"。
     * </p>
     *
     * @param key 用于派生的稳定标识
     * @return 新的噪声源
     */
    public Rng derive(String key) {
        return new Rng(mix(this.seed ^ hash(key)));
    }

    /**
     * 取一个落在 [0,1) 的离散随机值。
     *
     * @param bucket 离散桶号（例如"第几个重采样周期"）
     * @return 随机值
     */
    public double uniform01(long bucket) {
        long h = mix(this.seed ^ (bucket * 0x9E3779B97F4A7C15L));
        return (h >>> 11) * 0x1.0p-53;
    }

    /**
     * 取一个落在 [0,1) 的平滑连续噪声。
     *
     * @param tMs    时间（毫秒）
     * @param gridMs 噪声网格宽度（毫秒），越大越平缓
     * @return 噪声值
     */
    public double smooth01(long tMs, long gridMs) {
        long grid = Math.max(1L, gridMs);
        long index = Math.floorDiv(tMs, grid);
        double frac = (tMs - index * grid) / (double) grid;
        double eased = frac * frac * (3.0 - 2.0 * frac);
        double left = this.uniform01(index);
        double right = this.uniform01(index + 1L);
        return left + (right - left) * eased;
    }

    /**
     * 分形噪声：多倍频叠加，更接近真实传感器的漂移。
     *
     * @param tMs     时间（毫秒）
     * @param gridMs  基频网格宽度
     * @param octaves 倍频层数（1..6 有实际意义）
     * @return 落在 [-1,1] 的噪声
     */
    public double fbm(long tMs, long gridMs, int octaves) {
        int layers = Math.max(1, Math.min(6, octaves));
        double sum = 0.0;
        double amplitude = 1.0;
        double amplitudeSum = 0.0;
        long grid = Math.max(1L, gridMs);

        for (int i = 0; i < layers; i++) {
            sum += (this.smooth01(tMs, grid) * 2.0 - 1.0) * amplitude;
            amplitudeSum += amplitude;
            amplitude *= 0.5;
            grid = Math.max(1L, grid / 2L);
        }

        return amplitudeSum == 0.0 ? 0.0 : sum / amplitudeSum;
    }

    /**
     * 稳定的字符串哈希（FNV-1a 64）。
     *
     * @param key 字符串
     * @return 哈希值
     */
    public static long hash(String key) {
        long h = 0xCBF29CE484222325L;
        for (int i = 0; i < key.length(); i++) {
            h ^= key.charAt(i);
            h *= 0x100000001B3L;
        }
        return h;
    }

    /** splitmix64 终混函数。 */
    private static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
