package com.shimizukenta.secssim.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.core.curve.Rng;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.event.Trigger;

/**
 * 分段生效换编排表的行为：只改一条计划时，别的计划不能跟着回到禁用。
 *
 * <p>这是「按页保存」最容易做错的一处：{@code enableSchedule} 是"重建会话"
 * 的语义（全停），改事件计划如果也走它，操作员会发现只是改了一条无关计划，
 * 手上正在跑的十条全停了。</p>
 */
class EventSchedulerTest {

    private static EventPlan at(String id, int ceid, long atMs) {
        return new EventPlan(id, ceid, new Trigger.At(atMs));
    }

    private static EventScheduler scheduler() {
        return new EventScheduler(new Rng(1L), new SessionRateGate(),
                () -> 0L, () -> null, () -> false, (ceid, planId) -> { });
    }

    @Test
    void 只改一条时其它计划保留启用态() {
        EventScheduler scheduler = scheduler();
        EventPlan p1 = at("p1", 2001, 10_000L);
        EventPlan p2 = at("p2", 2002, 20_000L);
        scheduler.enableSchedule(new EventSchedule(List.of(p1, p2)), 50);
        assertEquals(0, scheduler.enabledCount(), "装载后所有计划默认禁用");

        assertTrue(scheduler.setEventEnabled("p1", true));
        assertEquals(1, scheduler.enabledCount());

        /* 只改 p2：p1 内容没变，应继续启用 */
        scheduler.replaceSchedulePreservingState(
                new EventSchedule(List.of(p1, at("p2", 2002, 30_000L))), 50);
        assertEquals(1, scheduler.enabledCount(), "没改动的计划不该被停");
    }

    @Test
    void 改动过的计划回禁用() {
        EventScheduler scheduler = scheduler();
        EventPlan p1 = at("p1", 2001, 10_000L);
        scheduler.enableSchedule(new EventSchedule(List.of(p1)), 50);
        scheduler.setEventEnabled("p1", true);

        scheduler.replaceSchedulePreservingState(
                new EventSchedule(List.of(at("p1", 2001, 99_000L))), 50);
        assertEquals(0, scheduler.enabledCount(), "改了触发规则的计划要重新手动启动");
    }

    @Test
    void 新增计划默认禁用且不影响老计划() {
        EventScheduler scheduler = scheduler();
        EventPlan p1 = at("p1", 2001, 10_000L);
        scheduler.enableSchedule(new EventSchedule(List.of(p1)), 50);
        scheduler.setEventEnabled("p1", true);

        scheduler.replaceSchedulePreservingState(
                new EventSchedule(List.of(p1, at("p3", 2003, 5_000L))), 50);
        assertEquals(1, scheduler.enabledCount(), "新增的计划要等操作员显式启动");
    }

    @Test
    void 原样再换一次表不改变任何启用态() {
        EventScheduler scheduler = scheduler();
        EventPlan p1 = at("p1", 2001, 10_000L);
        EventPlan p2 = at("p2", 2002, 20_000L);
        scheduler.enableSchedule(new EventSchedule(List.of(p1, p2)), 50);
        scheduler.setEventEnabled("p1", true);

        scheduler.replaceSchedulePreservingState(new EventSchedule(List.of(p1, p2)), 50);
        assertEquals(1, scheduler.enabledCount());
    }

    @Test
    void 删掉的计划不留下挂起触发() {
        EventScheduler scheduler = scheduler();
        EventPlan p1 = at("p1", 2001, 10_000L);
        scheduler.enableSchedule(new EventSchedule(List.of(p1)), 50);
        scheduler.setEventEnabled("p1", true);

        scheduler.replaceSchedulePreservingState(new EventSchedule(List.of()), 50);
        assertEquals(0, scheduler.enabledCount());
    }

    @Test
    void 全停仍然清掉所有启用态() {
        EventScheduler scheduler = scheduler();
        EventPlan p1 = at("p1", 2001, 10_000L);
        scheduler.enableSchedule(new EventSchedule(List.of(p1)), 50);
        scheduler.setEventEnabled("p1", true);
        scheduler.stopAll();
        assertEquals(0, scheduler.enabledCount());
    }
}
