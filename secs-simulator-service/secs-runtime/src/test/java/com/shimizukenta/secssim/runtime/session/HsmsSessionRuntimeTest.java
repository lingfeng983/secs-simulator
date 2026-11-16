package com.shimizukenta.secssim.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shimizukenta.secssim.app.session.SessionState;
import com.shimizukenta.secssim.core.ManualEngineClock;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.event.Trigger;
import com.shimizukenta.secssim.core.value.ValueSource;
import com.shimizukenta.secssim.runtime.session.DemoEquipment;

/**
 * HsmsSessionRuntime 的状态级单元测试：不建真连接，用手动时钟驱动。
 *
 * <p>真连接行为（S6F11 往返、报警、Trace）由 secs-cli 的真链路测试覆盖；
 * 这里锁的是<b>热生效与编排状态</b>的契约——拆分这个 2000 行的类之前，
 * 这些行为必须有回归网。</p>
 */
class HsmsSessionRuntimeTest {

    @Test
    void applyHotReplacesScheduleWithoutReopening(@TempDir java.nio.file.Path tempDir) throws Exception {
        ManualEngineClock clock = new ManualEngineClock(0L);
        HsmsSessionRuntime session = runtime(clock, "hot-1", "V1");
        session.open();

        assertTrue(session.eventsJson().contains("\"events\":[]") || session.eventsJson().contains("\"events\": []"),
                "初始没有编排: " + session.eventsJson());

        /* 热生效：换上 9000ms 周期计划 */
        session.applyHot(schedule("beat", 9000L), 20, false, false, null);

        assertTrue(session.eventsJson().contains("9000"), "新编排要可见: " + session.eventsJson());
        assertEquals(SessionState.OPEN, session.state(), "热生效不能重建会话");

        /* 再热一次换周期：连续热生效都保持同一实例 */
        session.applyHot(schedule("beat", 1000L), 20, true, false, null);
        assertTrue(session.eventsJson().contains("1000"), "二次热生效: " + session.eventsJson());
        assertEquals(SessionState.OPEN, session.state());
    }

    @Test
    void countersExposeS9AndWbitEntries() throws Exception {
        HsmsSessionRuntime session = runtime(new ManualEngineClock(0L), "cnt", "V1");
        session.open();

        assertTrue(session.counters().containsKey("s9Replies"), "S9Fx 计数要在 counters 里");
        assertTrue(session.counters().containsKey("emitted"));
    }

    @Test
    void hotApplyFallsBackToDefaultCurvesWhenValuesNull(@TempDir java.nio.file.Path tempDir) throws Exception {
        ManualEngineClock clock = new ManualEngineClock(0L);
        HsmsSessionRuntime session = runtime(clock, "fb", "V1");
        session.open();

        /* 带模板图的值来源 → applyHot(null) 应回落默认曲线且不抛 */
        ValueSource graph = session.valueSource();
        session.applyHot(schedule("p", 500L), 20, false, false, graph);
        session.applyHot(schedule("p", 600L), 20, false, false, null);

        assertEquals(SessionState.OPEN, session.state());
        assertTrue(session.eventsJson().contains("600"));
    }

    /**
     * P0 修复回归（2026-11-16 设计 M1-6）：热重载此前丢弃新编译的
     * 晶圆属性表与设备常量取值器——热路径必须一起换（S2F13/S6F11 才能看到新值）。
     */
    @Test
    void applyHotSwapsContextTableAndEcidValues() throws Exception {
        ManualEngineClock clock = new ManualEngineClock(0L);
        HsmsSessionRuntime session = runtime(clock, "dm", "V1");
        session.open();

        var oldContext = session.contextTable;
        var oldEcids = session.ecidValues;

        var newContext = com.shimizukenta.secssim.core.context.ContextTable.empty();
        var newEcids = new EcidValues(java.util.Map.of(), java.util.Map.of(), null, 1L);
        session.applyHot(schedule("p", 500L), 20, false, false, session.model, null,
                newContext, newEcids, null, null);

        org.junit.jupiter.api.Assertions.assertTrue(session.contextTable != oldContext,
                "热重载要换晶圆属性表（审计 P0）");
        org.junit.jupiter.api.Assertions.assertTrue(session.ecidValues != oldEcids,
                "热重载要换设备常量取值器（审计 P0）");
        org.junit.jupiter.api.Assertions.assertEquals(SessionState.OPEN, session.state(),
                "热生效不断连");
    }

    /**
     * D6（2026-11-16 设计 §2.1-6）：钉值 = 参数变化 → 全停——启用中的计划回到 stopped。
     */
    @Test
    void pinValueStopsAllPlans() throws Exception {
        ManualEngineClock clock = new ManualEngineClock(0L);
        HsmsSessionRuntime session = runtime(clock, "pin-stop", "V1");
        session.open();
        session.applyHot(schedule("beat", 1_000L), 20, false, false, null);

        /* 测试直接推控制状态：链路 SELECTED + ONLINE 才允许启动计划（§2.2） */
        session.control.communicatable(true);
        session.control.change(com.shimizukenta.secssim.app.session.EquipmentControlState.ONLINE);
        org.junit.jupiter.api.Assertions.assertEquals(
                com.shimizukenta.secssim.app.session.PlanEnableOutcome.OK,
                session.setEventEnabled("beat", true));
        org.junit.jupiter.api.Assertions.assertFalse(session.scheduler.isDisabled("beat"), "计划已启用");

        int svid = session.model.svids().keySet().iterator().next();
        org.junit.jupiter.api.Assertions.assertTrue(session.pinSvidValue(svid, 42.0), "钉值应成功");
        org.junit.jupiter.api.Assertions.assertTrue(session.scheduler.isDisabled("beat"),
                "钉值 = 参数变化 → 全停（D6）：计划回到 stopped");
        org.junit.jupiter.api.Assertions.assertTrue(session.isSvidPinned(svid), "钉值本身生效");
    }

    /* ---------- helpers ---------- */

    private HsmsSessionRuntime runtime(ManualEngineClock clock, String id, String marker) {
        EquipmentSpec demo = DemoEquipment.twoChambers(marker, 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);
        try {
            var spec = new com.shimizukenta.secssim.app.session.SessionSpec(
                    id, true, "127.0.0.1", 0, 10, 1L, demo);
            HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), clock);
            return session;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private EventSchedule schedule(String planId, long periodMs) {
        return new EventSchedule(List.of(
                new EventPlan(planId, 2001, new Trigger.Periodic(periodMs, 0L))));
    }
}
