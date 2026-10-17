package com.shimizukenta.secssim.core.domain;

/**
 * 闭区间 ID 范围，用于声明保留段（例如 1..999 留给设备级消息）。
 *
 * @param from 起始（含）
 * @param to   结束（含）
 */
public record IdRange(int from, int to) {

    /**
     * 校验并创建。
     *
     * @param from 起始
     * @param to   结束
     */
    public IdRange {
        if (from < 1) {
            throw new IllegalArgumentException("range start must be >= 1 but was " + from);
        }
        if (to < from) {
            throw new IllegalArgumentException("range end " + to + " is before start " + from);
        }
    }

    /**
     * 是否包含某 ID。
     *
     * @param id 待判定 ID
     * @return 包含返回 {@code true}
     */
    public boolean contains(int id) {
        return from <= id && id <= to;
    }

    /**
     * 是否与另一区间相交。
     *
     * @param other 另一区间
     * @return 相交返回 {@code true}
     */
    public boolean overlaps(IdRange other) {
        return this.from <= other.to && other.from <= this.to;
    }
}
