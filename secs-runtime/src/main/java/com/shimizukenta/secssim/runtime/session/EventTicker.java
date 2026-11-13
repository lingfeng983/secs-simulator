package com.shimizukenta.secssim.runtime.session;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 事件心跳：每 100ms 推一次事件与采样。
 *
 * <p>手动时钟（ManualEngineClock）意味着"时间由调用方推进"。
 * 这时再起一个按真实时间 100ms 的心跳，会与调用方的 pumpNow 抢着推进：
 * 调用方把时钟拨到 t=1s，心跳先跑完了触发，调用方再 pumpNow 就什么都不做，
 * 表现为"拨到点却没发"。确定性必须由时钟本身保证，而不是靠调度器碰运气。</p>
 */
final class EventTicker {

    /** 心跳间隔（毫秒）。 */
    private static final long TICK_MS = 100L;

    /** 心跳线程。 */
    private volatile ScheduledExecutorService service;

    /** 线程名。 */
    private final String threadName;

    EventTicker(String threadName) {
        this.threadName = threadName;
    }

    /**
     * 启动心跳。
     *
     * <p>幂等：先停掉可能还活着的那一条，再起新的。旧写法无条件
     * {@code newSingleThreadScheduledExecutor} 并覆盖字段——重复 open（并发连接、
     * 断开与重连交错）时先起的那条失去引用，daemon 线程继续每 100ms 推进
     * 一个已关闭会话的调度器，直到进程退出。</p>
     *
     * @param manualClock 是否是手动时钟；手动时钟下不启动线程
     * @param pump        心跳回调
     */
    synchronized void start(boolean manualClock, Runnable pump) {
        this.stopLocked();
        if (manualClock) {
            return;
        }

        ScheduledExecutorService created = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, this.threadName);
            thread.setDaemon(true);
            return thread;
        });
        try {
            created.scheduleAtFixedRate(pump, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            created.shutdownNow();
            throw e;
        }
        this.service = created;
    }

    /** 停掉心跳。 */
    synchronized void stop() {
        this.stopLocked();
    }

    private void stopLocked() {
        ScheduledExecutorService current = this.service;
        this.service = null;
        if (current != null) {
            current.shutdownNow();
        }
    }
}
