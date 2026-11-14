package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.EngineClock;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.compile.ResolvedCeid;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.event.Trigger;
import com.shimizukenta.secssim.runtime.session.DemoEquipment;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;

/**
 * 事件编排的归属：计划本身不存归属，视图里的 owner 必须由它引用的 CEID 反查。
 *
 * <p>现场需求是"按事件的归属看计划"：同一个 CEID 属于哪个单元，计划就属于哪个单元；
 * 两处分开存必然漂移，所以这里守住"反查"这条语义。</p>
 */
class SessionEventOwnerTest {

    @Test
    void planOwnerIsDerivedFromItsCeid() {
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);
        assertTrue(compiled.ok(), () -> "内置演示设备不合法: " + compiled.issues());

        ResolvedCeid ceid = compiled.equipment().ceids().get(2001);
        assertTrue(ceid != null, "演示设备应定义 CEID 2001");
        assertFalse(ceid.owner().isEmpty(), "CEID 必须有归属单元");

        SessionSpec spec = new SessionSpec("owner-1", true, "127.0.0.1", 0, 10, 1L, demo);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(),
                EngineClock.manual(0L));
        try {
            session.enableSchedule(new EventSchedule(List.of(
                    new EventPlan("plan-1", 2001, new Trigger.Periodic(1_000L, 0L))) ), 20);

            JsonHub view = JsonHub.fromJson(session.eventsJson());
            JsonHub plan = view.get("events").get(0);

            assertEquals("plan-1", plan.get("id").optionalString().orElse(""));
            assertEquals(2001, plan.get("ceid").intValue());
            assertEquals(ceid.owner(), plan.get("owner").optionalString().orElse(""),
                    "计划归属必须跟随所引用 CEID 的归属");
        }
        finally {
            session.close();
        }
    }
}
