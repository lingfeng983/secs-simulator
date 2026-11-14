package com.shimizukenta.secssim.core.trace;

/**
 * Trace 的时间与分组算术。
 *
 * <p>单独抽出来是因为它<b>没有状态</b>，可以纯测：
 * 第 k 个样本发生在 {@code t0 + k × DSPER}，
 * 每 {@code REPGSZ} 个样本组成一条 S6F1，最后一条可能不足。
 * </p>
 *
 * <p>{@code DSPER} 是 E5 规定的 {@code A[6]} 文本（hhmmss），不是整数毫秒。
 * 这个格式极易被当成数字处理，因此解析与格式化都放在这里统一。
 * </p>
 */
public final class TracePlan {

    /** DSPER 文本长度。 */
    public static final int DSPER_LENGTH = 6;

    private static final long MS_PER_SECOND = 1_000L;

    private TracePlan() {
    }

    /**
     * 第 {@code sampleNumber} 个样本的采样时刻。
     *
     * @param t0Ms         起始时刻
     * @param dsperMs      采样周期
     * @param sampleNumber 样本号（1 起）
     * @return 采样时刻
     */
    public static long sampleTimeMs(long t0Ms, long dsperMs, int sampleNumber) {
        if (sampleNumber < 1) {
            throw new IllegalArgumentException("sampleNumber must be >= 1 but was " + sampleNumber);
        }
        return t0Ms + dsperMs * sampleNumber;
    }

    /**
     * 需要发多少条 S6F1。
     *
     * @param totsmp 总样本数
     * @param repgsz 每报文样本数
     * @return 报文数
     */
    public static int frameCount(int totsmp, int repgsz) {
        if (totsmp < 1 || repgsz < 1) {
            throw new IllegalArgumentException("totsmp/repgsz must be >= 1");
        }
        /* 先减一再加一，避免 totsmp + repgsz 在两个大值下溢出成负数 */
        return (totsmp - 1) / repgsz + 1;
    }

    /**
     * 第 {@code frameIndex} 条报文（0 起）包含几个样本。
     *
     * @param frameIndex 报文下标（0 起）
     * @param totsmp     总样本数
     * @param repgsz     每报文样本数
     * @return 样本数
     */
    public static int samplesInFrame(int frameIndex, int totsmp, int repgsz) {
        int frames = frameCount(totsmp, repgsz);
        if (frameIndex < 0 || frameIndex >= frames) {
            throw new IllegalArgumentException("frameIndex out of range: " + frameIndex + " / " + frames);
        }
        long start = (long) frameIndex * repgsz;
        return (int) Math.min(repgsz, totsmp - start);
    }

    /**
     * 解析 {@code hhmmss} 文本为毫秒。
     *
     * <p>小时允许超过 23（例如 {@code 240000} 表示 24 小时），
     * 因为 SEMI 并未限制上界，且现场确有超长采样。
     * </p>
     *
     * @param dsper DSPER 文本
     * @return 毫秒
     * @throws IllegalArgumentException 格式非法或为 0
     */
    public static long parseDsperMs(String dsper) {
        if (dsper == null || dsper.length() != DSPER_LENGTH) {
            throw new IllegalArgumentException("DSPER 必须是 " + DSPER_LENGTH + " 位 hhmmss，实际: " + dsper);
        }
        for (int i = 0; i < DSPER_LENGTH; i++) {
            if (!Character.isDigit(dsper.charAt(i))) {
                throw new IllegalArgumentException("DSPER 只能包含数字，实际: " + dsper);
            }
        }

        int hours = Integer.parseInt(dsper.substring(0, 2));
        int minutes = Integer.parseInt(dsper.substring(2, 4));
        int seconds = Integer.parseInt(dsper.substring(4, 6));

        if (minutes > 59 || seconds > 59) {
            throw new IllegalArgumentException("DSPER 的 mm/ss 必须 <= 59，实际: " + dsper);
        }

        long totalSeconds = (hours * 60L + minutes) * 60L + seconds;
        if (totalSeconds <= 0L) {
            throw new IllegalArgumentException("DSPER 不能为 0");
        }
        return totalSeconds * MS_PER_SECOND;
    }
}
