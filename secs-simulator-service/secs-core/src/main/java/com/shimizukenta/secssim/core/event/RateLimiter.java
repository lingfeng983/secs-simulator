package com.shimizukenta.secssim.core.event;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 滑动窗口限流。
 *
 * <p>多单元设备里最容易出的事故是"某个单元剧本配错，一秒发几百条 S6F11"，
 * 主机侧要么 T3 排队、要么直接判定设备异常。限流必须做在<b>设备级</b>，
 * 而不只是每个单元各管各的。
 * </p>
 */
public final class RateLimiter {

    private final int maxPerWindow;
    private final long windowMs;
    private final Deque<Long> accepted = new ArrayDeque<>();

    private long rejected;

    /**
     * 创建限流器。
     *
     * @param maxPerWindow 每个窗口允许的次数
     * @param windowMs     窗口长度（毫秒）
     */
    public RateLimiter(int maxPerWindow, long windowMs) {
        if (maxPerWindow < 1) {
            throw new IllegalArgumentException("maxPerWindow must be >= 1");
        }
        if (windowMs < 1L) {
            throw new IllegalArgumentException("windowMs must be >= 1");
        }
        this.maxPerWindow = maxPerWindow;
        this.windowMs = windowMs;
    }

    /**
     * 尝试获取一次许可。
     *
     * @param nowMs 当前时刻
     * @return 允许返回 {@code true}
     */
    public synchronized boolean tryAcquire(long nowMs) {
        this.evict(nowMs);

        if (this.accepted.size() >= this.maxPerWindow) {
            this.rejected++;
            return false;
        }

        this.accepted.addLast(nowMs);
        return true;
    }

    /**
     * 被拒绝的次数。
     *
     * @return 次数
     */
    public synchronized long rejected() {
        return this.rejected;
    }

    /**
     * 当前窗口内已接受的次数。
     *
     * @param nowMs 当前时刻
     * @return 次数
     */
    public synchronized int used(long nowMs) {
        this.evict(nowMs);
        return this.accepted.size();
    }

    private void evict(long nowMs) {
        while (!this.accepted.isEmpty() && nowMs - this.accepted.peekFirst() >= this.windowMs) {
            this.accepted.removeFirst();
        }
    }
}
