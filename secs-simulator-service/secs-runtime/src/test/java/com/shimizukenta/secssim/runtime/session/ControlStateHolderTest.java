package com.shimizukenta.secssim.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.app.session.EquipmentControlState;

/**
 * 主动上报的放行条件（严格 GEM）。
 *
 * <p>S6F11 / S6F1 只在设备 {@code ONLINE} 时允许：只建链（{@code COMMUNICATING}，
 * 即"已连接但离线"）不算在线，此时上报属于违规。上线有两条路——主机发 S1F17，
 * 或操作员手动置为在线——两者最终都落到 {@code ONLINE} 这一档。</p>
 */
class ControlStateHolderTest {

    @Test
    void requiresOnlineForUnsolicitedMessages() {
        ControlStateHolder holder = new ControlStateHolder(state -> { });

        assertFalse(holder.canSendUnsolicited(), "没建链时不能发");

        holder.communicatable(true);
        assertFalse(holder.canSendUnsolicited(), "链路已选中但还没通信，仍不能发");

        holder.change(EquipmentControlState.COMMUNICATING);
        assertFalse(holder.canSendUnsolicited(), "已连接但离线（COMMUNICATING）不能发 S6F11");

        holder.change(EquipmentControlState.ONLINE);
        assertTrue(holder.canSendUnsolicited(), "ONLINE 才允许主动上报");

        holder.change(EquipmentControlState.OFFLINE);
        assertFalse(holder.canSendUnsolicited(), "S1F15 之后必须停发");

        holder.change(EquipmentControlState.ONLINE);
        holder.communicatable(false);
        assertFalse(holder.canSendUnsolicited(), "链路断开后必须停发");
    }

    /**
     * UP-03：change() 的 synchronized 不只是可见性——同一目标状态被并发提交时
     * 只能通知一次。没有原子 check-then-set 时，N 条线程都可能看到旧状态再各发一次通知。
     */
    @Test
    void concurrentTransitionsToSameStateNotifyOnce() throws Exception {
        AtomicInteger notifications = new AtomicInteger();
        ControlStateHolder holder = new ControlStateHolder(state -> notifications.incrementAndGet());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < 100; i++) {
                pool.submit(() -> {
                    start.await();
                    holder.change(EquipmentControlState.ONLINE);
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5L, TimeUnit.SECONDS), "并发迁移不应卡住");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, notifications.get(), "同一目标状态只发一次通知");
        assertEquals(EquipmentControlState.ONLINE, holder.state());
    }

    @Test
    void onlineCanBeDrivenByOperatorAsWellAsByHost() {
        ControlStateHolder holder = new ControlStateHolder(state -> { });
        holder.communicatable(true);
        holder.change(EquipmentControlState.COMMUNICATING);

        /* 手动上线等价于主机发 S1F17：状态到 ONLINE，之后事件才真正发出去 */
        holder.change(EquipmentControlState.ONLINE);
        assertTrue(holder.canSendUnsolicited());

        /* 手动下线等价于 S1F15 */
        holder.change(EquipmentControlState.OFFLINE);
        assertFalse(holder.canSendUnsolicited());
    }
}
