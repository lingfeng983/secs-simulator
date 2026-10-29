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
     * @param manualClock 是否是手动时钟；手动时钟下不启动线程
     * @param pump        心跳回调
     */
    void start(boolean manualClock, Runnable pump) {
        if (manualClock) {
            return;
        }

        ScheduledExecutorService created = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, this.threadName);
            thread.setDaemon(true);
            return thread;
        });
        created.scheduleAtFixedRate(pump, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        this.service = created;
    }

    /** 停掉心跳。 */
    void stop() {
        ScheduledExecutorService current = this.service;
        this.service = null;
        if (current != null) {
            current.shutdownNow();
        }
    }
}
