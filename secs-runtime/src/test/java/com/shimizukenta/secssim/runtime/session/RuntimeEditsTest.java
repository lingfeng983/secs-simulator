package com.shimizukenta.secssim.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.core.ManualEngineClock;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.compile.ResolvedCeid;
import com.shimizukenta.secssim.core.compile.ResolvedReport;
import com.shimizukenta.secssim.core.compile.ResolvedSvid;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.core.domain.ValueType;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.Trigger;

/**
 * 运行时编辑（设备页·事件设置/事件编排/状态变量）的契约测试。
 *
 * <p>这些入口改的是<b>运行时状态</b>：换编排表实例、派生设备模型、钉值。
 * 拆行为之前先把契约钉住——特别是"改了模型要同步视图（组装器/Trace）"
 * 与"值设定优先级最高"这两条。</p>
 */
class RuntimeEditsTest {

    /* ---------- 计划 CRUD ---------- */

    @Test
    void eventPlanCrudRoundTrip() throws Exception {
        HsmsSessionRuntime session = runtime("crud-events");
        session.open();

        EventPlan plan = new EventPlan("p1", 2001, new Trigger.Periodic(5000L, 0L));
        assertTrue(session.addEventPlan(plan), "新增成功");
        assertFalse(session.addEventPlan(plan), "重复 id 拒绝");
        assertTrue(session.eventsJson().contains("p1"), "新增后可见: " + session.eventsJson());

        assertTrue(session.updateEventPlan("p1",
                new EventPlan("p1", 2002, new Trigger.Periodic(7000L, 0L))), "更新成功");
        assertTrue(session.eventsJson().contains("7000"), "更新生效");
        assertFalse(session.updateEventPlan("nope",
                new EventPlan("nope", 2001, new Trigger.Periodic(1000L, 0L))), "不存在的计划拒绝");

        assertTrue(session.removeEventPlan("p1"), "删除成功");
        assertFalse(session.removeEventPlan("p1"), "重复删除拒绝");
        assertFalse(session.eventsJson().contains("p1"), "删除后不可见");
    }

    @Test
    void updateRejectsMismatchedId() throws Exception {
        HsmsSessionRuntime session = runtime("crud-mismatch");
        session.open();
        assertThrows(IllegalArgumentException.class, () -> session.updateEventPlan("a",
                new EventPlan("b", 1, new Trigger.Periodic(1000L, 0L))));
    }

    /* ---------- 报告 CRUD ---------- */

    @Test
    void reportCrudReflectedInCatalog() throws Exception {
        HsmsSessionRuntime session = runtime("crud-reports");
        session.open();
        int svid = firstSvid(session);

        ResolvedReport report = new ResolvedReport(9001, "GLOBAL", "MY_REPORT", List.of(svid));
        assertTrue(session.addReport(report));
        assertFalse(session.addReport(report), "RPTID 重复拒绝");
        assertTrue(session.catalogJson().contains("9001"), "目录可见");

        assertTrue(session.updateReport(new ResolvedReport(9001, "GLOBAL", "RENAMED", List.of(svid))));
        assertTrue(session.catalogJson().contains("RENAMED"), "更新生效");
        assertFalse(session.updateReport(new ResolvedReport(9999, "GLOBAL", "X", List.of(svid))));

        assertTrue(session.removeReport(9001));
        assertFalse(session.catalogJson().contains("9001"), "删除后不可见");
    }

    /* ---------- 事件(CEID) CRUD ---------- */

    @Test
    void ceidCrudReflectedInCatalog() throws Exception {
        HsmsSessionRuntime session = runtime("crud-ceids");
        session.open();

        ResolvedCeid ceid = new ResolvedCeid(7001, "GLOBAL", "MY_EVENT", List.of());
        assertTrue(session.addCeid(ceid));
        assertFalse(session.addCeid(ceid), "CEID 重复拒绝");
        assertTrue(session.catalogJson().contains("7001"));

        assertTrue(session.updateCeid(new ResolvedCeid(7001, "GLOBAL", "RENAMED_EVENT", List.of())));
        assertTrue(session.catalogJson().contains("RENAMED_EVENT"));
        assertFalse(session.updateCeid(new ResolvedCeid(7999, "GLOBAL", "X", List.of())));

        assertTrue(session.removeCeid(7001));
        assertFalse(session.catalogJson().contains("7001"));
    }

    /* ---------- 状态变量 CRUD + 值设定 ---------- */

    @Test
    void svidCrudAndPinnedValue() throws Exception {
        ManualEngineClock clock = new ManualEngineClock(0L);
        HsmsSessionRuntime session = runtime(clock, "crud-svids");
        session.open();

        ResolvedSvid def = new ResolvedSvid(4242, "GLOBAL", "MY_VAR", ValueType.F4, "C");
        assertTrue(session.addSvid(def));
        assertFalse(session.addSvid(def), "SVID 重复拒绝");
        assertTrue(session.catalogJson().contains("4242"));

        /* 新变量默认恒 0;「设定」钉住后值优先于曲线 */
        assertEquals(0.0, session.valueSource().valueAt(4242, 12345L).getAsDouble(), 1e-9);
        assertTrue(session.pinSvidValue(4242, 88.5));
        assertEquals(88.5, session.valueSource().valueAt(4242, 99999L).getAsDouble(), 1e-9,
                "设定值优先于曲线");

        assertTrue(session.unpinSvidValue(4242));
        assertFalse(session.unpinSvidValue(4242), "重复取消拒绝");
        assertFalse(session.pinSvidValue(9999, 1.0), "不存在的变量拒绝设定");

        assertTrue(session.updateSvid(new ResolvedSvid(4242, "GLOBAL", "RENAMED_VAR", ValueType.F4, "C")));
        assertTrue(session.catalogJson().contains("RENAMED_VAR"));

        assertTrue(session.removeSvid(4242));
        assertFalse(session.catalogJson().contains("4242"));
        assertTrue(session.valueSource().valueAt(4242, 0L).isEmpty(), "删除后无值");
    }

    /* ---------- 请求体解析 ---------- */

    @Test
    void parsesPlanReportCeidSvidBodies() {
        EventPlan plan = RuntimeEdits.parseEventPlan(
                "{\"id\":\"px\",\"ceid\":2001,\"trigger\":{\"type\":\"periodic\",\"periodMs\":250,\"jitterMs\":10},"
                        + "\"effects\":[{\"svid\":1001,\"op\":\"set\",\"value\":80}]}");
        assertEquals("px", plan.id());
        assertEquals(2001, plan.ceid());
        assertTrue(plan.trigger() instanceof Trigger.Periodic);
        assertEquals(1, plan.effects().size());

        ResolvedReport report = RuntimeEdits.parseReport(
                "{\"id\":3001,\"slot\":\"R\",\"svids\":[1001,1002]}");
        assertEquals(List.of(1001, 1002), report.svidIds());

        ResolvedCeid ceid = RuntimeEdits.parseCeid(
                "{\"id\":2001,\"slot\":\"E\",\"reports\":[3001]}");
        assertEquals(List.of(3001), ceid.reportIds());

        ResolvedSvid svid = RuntimeEdits.parseSvid(
                "{\"id\":1001,\"slot\":\"TEMP\",\"type\":\"F4\",\"owner\":\"GLOBAL\",\"unit\":\"C\"}");
        assertEquals(ValueType.F4, svid.type());
        assertEquals(1.25, RuntimeEdits.parsePinnedValue("{\"value\":1.25}"), 1e-12);
    }

    @Test
    void rejectsBadBodiesWithReadableErrors() {
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeEdits.parseEventPlan("{\"id\":\"x\",\"ceid\":1,\"trigger\":{\"type\":\"nope\"}}"));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeEdits.parseEventPlan("{\"ceid\":1,\"trigger\":{\"type\":\"at\",\"atMs\":1}}"));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeEdits.parseSvid("{\"id\":1,\"slot\":\"T\",\"type\":\"NOPE\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeEdits.parsePinnedValue("{}"));
    }

    /* ---------- helpers ---------- */

    private HsmsSessionRuntime runtime(String id) {
        return runtime(new ManualEngineClock(0L), id);
    }

    private HsmsSessionRuntime runtime(ManualEngineClock clock, String id) {
        EquipmentSpec demo = DemoEquipment.twoChambers("EDITS", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);
        try {
            var spec = new com.shimizukenta.secssim.app.session.SessionSpec(
                    id, true, "127.0.0.1", 0, 10, 1L, demo);
            return new HsmsSessionRuntime(spec, compiled.equipment(), clock);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int firstSvid(HsmsSessionRuntime session) {
        return session.catalogJson().isEmpty()
                ? 1001
                : compiledFirst(session);
    }

    private static int compiledFirst(HsmsSessionRuntime session) {
        /* 目录 JSON 里第一个 svid;两腔演示设备的 CH1 基址是确定的 */
        String json = session.catalogJson();
        int at = json.indexOf("\"svid\":");
        int value = Integer.parseInt(json.substring(at + 7).split("[^0-9]")[0]);
        return value;
    }
}
