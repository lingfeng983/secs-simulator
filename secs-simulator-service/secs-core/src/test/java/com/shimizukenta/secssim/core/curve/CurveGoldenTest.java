package com.shimizukenta.secssim.core.curve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;

/**
 * 黄金样本：把一整场景的曲线指纹钉死。
 *
 * <p>曲线引擎的任何改动（插值方式、哈希函数、噪声叠加）都会改变指纹。
 * 这是<b>故意的</b>：数据形状变了，必须显式承认并更新指纹，
 * 而不是让"看起来差不多"的改动悄悄溜进生产。
 * </p>
 *
 * <p>更新方式：先跑一次，看断言里的 actual 值，确认变化符合预期后再粘贴回来。
 * </p>
 */
class CurveGoldenTest {

    /** 场景指纹，改动曲线实现时必须同步更新。 */
    private static final String GOLDEN = "3ba1c704";

    @Test
    void sceneFingerprintIsStable() {
        String actual = fingerprint();
        assertEquals(GOLDEN, actual, "曲线指纹变化，请确认这是预期行为后再更新 GOLDEN");
    }

    @Test
    void fingerprintHasFixedWidthHexShape() {
        /* 曾经的断言是 length() >= 8，而 %08x 恒为 8 字符——永远真。
           改成校验形状 + 非零，哈希退化成常量或格式被改短都会红。 */
        String actual = fingerprint();
        assertTrue(actual.matches("[0-9a-f]{8}"),
                "指纹必须是 8 位小写十六进制，实际: " + actual);
        assertNotEquals("00000000", actual, "指纹不能是全零（哈希没覆盖到内容）");
    }

    @Test
    void fingerprintChangesWhenSeedChanges() {
        /* 防「哈希退化为常量」：换随机种子后指纹必须变，否则说明哈希没覆盖到值。 */
        assertNotEquals(fingerprint(20240601L), fingerprint(20240602L),
                "换随机种子后指纹必须变化，否则哈希没有覆盖值");
    }

    /**
     * 计算场景指纹：60 秒、每秒一点、四条不同曲线，格式化为 6 位小数后取 CRC32。
     *
     * @return 十六进制指纹
     */
    static String fingerprint() {
        return fingerprint(20240601L);
    }

    /**
     * 用指定随机种子计算场景指纹。
     *
     * @param seed 随机种子
     * @return 十六进制指纹
     */
    static String fingerprint(long seed) {
        Rng rng = new Rng(seed);

        Curve ramp = new RampCurve(25.0, 180.0, 45_000L, false);
        Curve walk = new RandomWalkCurve(180.0, 0.0, 1.5, 2_000L, 3, 150.0, 210.0, BoundPolicy.REFLECT);
        Curve uniform = new UniformCurve(0.05, 0.15, 5_000L);
        Curve sine = new SineCurve(0.0, 100.0, 20_000L, 0L);

        StringBuilder sb = new StringBuilder();
        for (long t = 0L; t <= 60_000L; t += 1_000L) {
            sb.append(String.format(Locale.ROOT, "%.6f|%.6f|%.6f|%.6f;",
                    ramp.at(t, rng),
                    walk.at(t, rng.derive("WALK")),
                    uniform.at(t, rng.derive("PRESS")),
                    sine.at(t, rng)));
        }

        CRC32 crc = new CRC32();
        crc.update(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return String.format(Locale.ROOT, "%08x", crc.getValue());
    }
}
