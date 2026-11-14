package com.shimizukenta.secssim.core.trace;

import java.util.List;

/**
 * 一条 Trace 的定义（来自 S2F23）。
 *
 * @param trid    Trace ID
 * @param svids   被采样的 SVID（顺序即 S6F1 里的值顺序）
 * @param dsperMs 采样周期（毫秒）
 * @param totsmp  总样本数
 * @param repgsz  每条 S6F1 携带的样本数
 */
public record TraceSpec(int trid, List<Integer> svids, long dsperMs, int totsmp, int repgsz) {

    /**
     * 校验并创建。
     *
     * @param trid    Trace ID
     * @param svids   SVID 列表
     * @param dsperMs 采样周期
     * @param totsmp  总样本数
     * @param repgsz  每报文样本数
     */
    public TraceSpec {
        if (svids == null || svids.isEmpty()) {
            throw new IllegalArgumentException("trace needs at least one svid");
        }
        svids = List.copyOf(svids);
        if (dsperMs < 1_000L) {
            throw new IllegalArgumentException("S2F23 的 DSPER 最小是 1 秒（A[6] 格式），实际 " + dsperMs + " ms");
        }
        if (totsmp < 1) {
            throw new IllegalArgumentException("totsmp must be >= 1 but was " + totsmp);
        }
        if (repgsz < 1 || repgsz > totsmp) {
            throw new IllegalArgumentException("repgsz 必须在 1..totsmp 之间，实际 " + repgsz + " / " + totsmp);
        }
    }

    /**
     * 样本数。
     *
     * @return 总样本数
     */
    public int sampleCount() {
        return this.totsmp;
    }

    /**
     * 本期 Trace 需要发多少条 S6F1。
     *
     * @return 报文数
     */
    public int frameCount() {
        return TracePlan.frameCount(this.totsmp, this.repgsz);
    }

    @Override
    public String toString() {
        return "TraceSpec(trid=" + this.trid + ", svids=" + this.svids
                + ", dsper=" + this.dsperMs + "ms, totsmp=" + this.totsmp + ", repgsz=" + this.repgsz + ")";
    }
}
