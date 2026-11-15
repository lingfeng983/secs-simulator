package com.shimizukenta.secssim.runtime.session;

import com.shimizukenta.secs.SecsWaitReplyMessageException;

/**
 * 会话诊断：错误记录与事件速率。
 *
 * <p>只承载"对操作员可见的故障信息"与 overview 用的事件速率，
 * 与链路生命周期解耦，链路重建时不清空。</p>
 */
final class SessionDiagnostics {

    /** 最近一次会话级错误。 */
    private volatile String lastError;

    /** overview 事件速率统计(60 秒滑动窗口)。 */
    private final RateTracker eventRate = new RateTracker();

    /** 最近一次真正发出事件的时间（墙钟）。 */
    private volatile long lastEventAtMs = 0L;

    RateTracker eventRate() {
        return this.eventRate;
    }

    long lastEventAtMs() {
        return this.lastEventAtMs;
    }

    String lastError() {
        return this.lastError;
    }

    void recordError(Exception e) {
        recordFailure(e);
    }

    /**
     * 记一次真正发出去的事件（速率与最近时刻）。
     *
     * <p>overview 里的 {@code s6f11PerMin} / {@code lastEventAt} 读的就是这两个字段；
     * 以前它们只有声明没有写入，工作台永远显示 0。</p>
     */
    void recordEventFired() {
        long now = System.currentTimeMillis();
        this.eventRate.record(now);
        this.lastEventAtMs = now;
    }

    /**
     * 记下一条会话级错误（含协议层日志里的异常）。
     *
     * <p>协议层把 bind 失败、连接被拒、超时等都塞进日志队列；没有监听者时这些信息
     * 会直接消失。HsmsConnector 把其中的 Throwable 转到这里，界面与 lastError 才看得见。</p>
     *
     * <p>对端超时(W 位等 T3/T5 没等到回复)不算会话故障——报文已发出,
     * 只是主机没回;把它钉在 lastError 上,设备页会一直飘着一行红色,
     * 看起来像设备坏了。这类异常只进计数,不进 lastError。</p>
     *
     * @param e 错误
     */
    void recordFailure(Throwable e) {
        if (e == null || isPeerTimeout(e)) {
            return;
        }
        this.lastError = e.toString();
    }

    private static boolean isPeerTimeout(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SecsWaitReplyMessageException) {
                return true;
            }
            String name = cause.getClass().getName();
            if (name.contains("HsmsSsTimeout")) {
                return true;
            }
        }
        return false;
    }
}