package com.shimizukenta.secssim.runtime.session;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 60 秒滑动窗口速率计数（overview 端点用）。
 *
 * <p>不是精确计量:环形里过期的点在读取时顺带清理,
 * 内存上界 = 窗口内事件数（上限裁剪到 10_000,超出的按比例折算已无意义——
 * 那种速率下用户看的是"打爆了"而不是小数点）。</p>
 */
public final class RateTracker {

    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_SAMPLES = 10_000;

    private final Deque<Long> stamps = new ArrayDeque<>();

    /**
     * 记录一次发生。
     *
     * @param nowMs 当前时刻
     */
    public void record(long nowMs) {
        synchronized (this.stamps) {
            this.stamps.addLast(nowMs);
            while (this.stamps.size() > MAX_SAMPLES) {
                this.stamps.pollFirst();
            }
        }
    }

    /**
     * 窗口内的发生次数（= 每分钟速率）。
     *
     * @param nowMs 当前时刻
     * @return 60 秒内的计数
     */
    public long countInWindow(long nowMs) {
        synchronized (this.stamps) {
            Long head;
            while ((head = this.stamps.peekFirst()) != null && nowMs - head > WINDOW_MS) {
                this.stamps.pollFirst();
            }
            return this.stamps.size();
        }
    }
}
