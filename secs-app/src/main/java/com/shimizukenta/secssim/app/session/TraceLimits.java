package com.shimizukenta.secssim.app.session;

/**
 * Trace（S2F23 周期采样）容量限制。
 *
 * <p>逐键可选：字段为 {@code null} 时用内置默认（同时 1 条、每条 ≤10 个 SVID，
 * 与多数真机一致）；写了就必须 ≥1。配置里只写需要放宽的键即可。</p>
 *
 * @param maxTraces     同时允许的 Trace 条数
 * @param maxSvidCount  单条 Trace 允许的 SVID 数
 */
public record TraceLimits(Integer maxTraces, Integer maxSvidCount) {

    /** 默认：与多数真机一致。 */
    public static final int DEFAULT_MAX_TRACES = 1;

    /** 默认：单条 Trace 最多 10 个 SVID。 */
    public static final int DEFAULT_MAX_SVIDS = 10;

    /** 全默认。 */
    public static final TraceLimits DEFAULTS = new TraceLimits(null, null);

    /**
     * 校验并补默认。
     *
     * @param maxTraces    同时允许的 Trace 条数
     * @param maxSvidCount 单条 Trace 允许的 SVID 数
     */
    public TraceLimits {
        if (maxTraces != null && maxTraces < 1) {
            throw new IllegalArgumentException("maxTraces 必须 ≥ 1");
        }
        if (maxSvidCount != null && maxSvidCount < 1) {
            throw new IllegalArgumentException("maxSvidCount 必须 ≥ 1");
        }
    }

    /**
     * 生效的同时 Trace 条数。
     *
     * @return 配置值或默认
     */
    public int maxTracesOrDefault() {
        return this.maxTraces == null ? DEFAULT_MAX_TRACES : this.maxTraces;
    }

    /**
     * 生效的单条 Trace SVID 上限。
     *
     * @return 配置值或默认
     */
    public int maxSvidCountOrDefault() {
        return this.maxSvidCount == null ? DEFAULT_MAX_SVIDS : this.maxSvidCount;
    }
}
