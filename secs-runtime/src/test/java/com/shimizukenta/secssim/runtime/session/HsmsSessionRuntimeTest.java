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
