package com.shimizukenta.secssim.core.event;

/**
 * 一次待发生的事件。
 *
 * @param atMs   触发时刻
 * @param planId 计划标识（用于日志与去重）
 * @param ceid   采集事件 ID
 */
public record Fire(long atMs, String planId, int ceid) {

    /**
     * 校验并创建。
     *
     * @param atMs   触发时刻
     * @param planId 计划标识
     * @param ceid   CEID
     */
    public Fire {
        if (planId == null || planId.isBlank()) {
            throw new IllegalArgumentException("planId must not be blank");
        }
        if (ceid < 1) {
            throw new IllegalArgumentException("ceid must be >= 1 but was " + ceid);
        }
    }
}
