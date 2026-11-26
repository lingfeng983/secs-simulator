package com.shimizukenta.secssim.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.app.session.HsmsTimeouts;
import com.shimizukenta.secssim.app.session.TraceLimits;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.Issue;
import com.shimizukenta.secssim.core.context.ContextTable;
import com.shimizukenta.secssim.core.curve.ConstantCurve;
import com.shimizukenta.secssim.core.template.Expression;
import com.shimizukenta.secssim.core.value.DataSource;
import com.shimizukenta.secssim.core.domain.CeidDef;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.core.domain.GlobalSvidDef;
import com.shimizukenta.secssim.core.domain.IdSpacePolicy;
import com.shimizukenta.secssim.core.domain.ReportDef;
import com.shimizukenta.secssim.core.domain.SvidDef;
import com.shimizukenta.secssim.core.domain.UnitId;
import com.shimizukenta.secssim.core.domain.UnitSpec;
import com.shimizukenta.secssim.core.domain.ValueType;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.event.Trigger;

class SessionConfigJsonTest {

    private static EquipmentSpec twoChambers() {
        return new EquipmentSpec("DEMO-01", 100,
                List.of(chamber("CH1", 1), chamber("CH2", 2)),
                List.of(),
                List.of(),
                IdSpacePolicy.defaults());
    }

    private static UnitSpec chamber(String name, int unitId) {
        return new UnitSpec(UnitId.of(unitId), name,
                List.of(
                        new SvidDef(1, "TEMP_PV", ValueType.F4, "C", 2, 0.0, 400.0),
                        new SvidDef(2, "PRESS", ValueType.F4, "Torr", 3, 0.0, 10.0),
                        SvidDef.of(90, "UNIT_ID", ValueType.U4)),
                List.of(new ReportDef(1, "STATUS", List.of("UNIT_ID", "TEMP_PV", "PRESS"))),
                List.of(new CeidDef(1, "PROCESS_START", List.of("STATUS"))),
                true);
    }

    private static SessionConfig demoConfig() {
        SessionSpec spec = new SessionSpec("pri-01", true, "127.0.0.1", 5000, 100, 20240601L, twoChambers());
        EventSchedule schedule = new EventSchedule(List.of(
                new EventPlan("start", 2001, new Trigger.Periodic(5_000L, 200L)),
                new EventPlan("once", 2101, new Trigger.At(1_000L))));
        return new SessionConfig(spec, schedule, 20);
    }

    private static SessionConfig configWithContext(ContextTable.Def... defs) {
        SessionSpec spec = new SessionSpec("ctx-test", true, "127.0.0.1", 5000, 100, 20240601L, twoChambers());
        return new SessionConfig(spec, EventSchedule.empty(), 20,
                false, false, null, List.of(defs), Map.of(), Map.of());
    }

    @Test
    void contextSourceMustMatchValueType() {
        CompilationResult result = configWithContext(
                new ContextTable.Def("LOTID", ValueType.ASCII,
                        new DataSource.CurveSource(new ConstantCurve(3.0), DataSource.Clock.PHASE, null)),
                new ContextTable.Def("WAFER", ValueType.ASCII,
                        new DataSource.CycleNumber(1L, 1L, 1L)),
                new ContextTable.Def("TEMP", ValueType.F4, new DataSource.Text("x")),
                new ContextTable.Def("STEP", ValueType.U2,
                        new DataSource.CycleText(null, "W{03}", null, 1L, 1L))
        ).compile();

        assertEquals(Set.of("CONTEXT_SOURCE_TYPE_MISMATCH"),
                result.errors().stream().map(Issue::code).collect(Collectors.toSet()));
        assertEquals(4, result.errors().size(), "每条不相容属性都要单独报错");
        assertTrue(result.errors().stream().anyMatch(e -> e.message().contains("LOTID")),
                "错误要指出具体属性名");
    }

    @Test
    void compatibleContextSourcesCompile() {
        CompilationResult result = configWithContext(
                new ContextTable.Def("LOTID", ValueType.ASCII, new DataSource.Text("LOT")),
                new ContextTable.Def("WAFER", ValueType.ASCII,
                        new DataSource.CycleText(null, "W{03}", null, 1L, 1L)),
                new ContextTable.Def("RCP", ValueType.ASCII, new DataSource.Const(7.0)),
                new ContextTable.Def("TEMP", ValueType.F4, new DataSource.Const(3.0)),
                new ContextTable.Def("CURVE", ValueType.F4,
                        new DataSource.CurveSource(new ConstantCurve(3.0), DataSource.Clock.PHASE, null)),
                new ContextTable.Def("EXPR", ValueType.F4,
                        new DataSource.ExprSource(Map.of(), Expression.parse("t"), DataSource.Clock.PHASE)),
                new ContextTable.Def("STEP", ValueType.U2, new DataSource.CycleNumber(1L, 1L, 1L))
        ).compile();

        assertTrue(result.ok(), () -> "相容组合不应报错: " + result.errors());
    }

    @Test
    void roundTripsThroughJson() {
        SessionConfig original = demoConfig();
        String first = SessionConfigJson.toJson(original);

        SessionConfig parsed = SessionConfigJson.parse(first);
        String second = SessionConfigJson.toJson(parsed);

        assertEquals(first, second, "JSON -> 模型 -> JSON 必须等价");
    }

    @Test
    void parsedConfigCompilesAndResolvesIds() {
        CompilationResult result = SessionConfigJson.parse(SessionConfigJson.toJson(demoConfig())).compile();

        assertTrue(result.ok(), () -> "不应有编译错误: " + result.errors());
        assertEquals(Set.of(1001, 1002, 1090, 1101, 1102, 1190), result.equipment().svids().keySet());
        assertEquals(Set.of(2001, 2101), result.equipment().ceids().keySet());
    }

    @Test
    void scheduleSurvivesRoundTrip() {
        SessionConfig parsed = SessionConfigJson.parse(SessionConfigJson.toJson(demoConfig()));

        assertEquals(2, parsed.schedule().size());
        assertEquals(20, parsed.maxEventsPerSec());

        Map<String, Trigger> byId = parsed.schedule().plans().stream()
                .collect(Collectors.toMap(EventPlan::id, EventPlan::trigger));

        assertTrue(byId.get("start") instanceof Trigger.Periodic);
        assertEquals(5_000L, ((Trigger.Periodic) byId.get("start")).periodMs());
        assertTrue(byId.get("once") instanceof Trigger.At);
        assertEquals(1_000L, ((Trigger.At) byId.get("once")).atMs());
    }

    @Test
    void unsupportedSchemaVersionIsRejected() {
        String json = SessionConfigJson.toJson(demoConfig()).replaceAll("\"schemaVersion\": \\d+", "\"schemaVersion\": 3");

        ConfigException e = assertThrows(ConfigException.class, () -> SessionConfigJson.parse(json));
        assertEquals("$.schemaVersion", e.path());
    }

    @Test
    void missingSessionIdReportsPath() {
        String json = """
                {"schemaVersion": 7,"session":{"port":5000},"equipment":{"name":"E","idSpace":{},"units":[]}}
                """;

        ConfigException e = assertThrows(ConfigException.class, () -> SessionConfigJson.parse(json));
        assertEquals("$.session.id", e.path());
    }

    @Test
    void badSvidTypeReportsFieldPath() {
        String json = """
                {"schemaVersion": 7,
                 "session":{"id":"s1","port":5010},
                 "equipment":{"name":"E","idSpace":{},
                   "units":[{"unitId":1,"name":"CH1",
                     "svids":[{"offset":1,"slot":"TEMP","type":"FLOAT"}],
                     "reports":[],"ceids":[]}]}}
                """;

        ConfigException e = assertThrows(ConfigException.class, () -> SessionConfigJson.parse(json));
        assertEquals("$.equipment.units[0].svids[0].type", e.path());
    }

    @Test
    void missingUnitArrayIsRejected() {
        String json = """
                {"schemaVersion": 7,"session":{"id":"s1","port":5010},"equipment":{"name":"E","idSpace":{}}}
                """;

        ConfigException e = assertThrows(ConfigException.class, () -> SessionConfigJson.parse(json));
        assertTrue(e.path().startsWith("$.equipment.units"), e.path());
    }

    @Test
    void semanticErrorsAreLeftToTheCompiler() {
        String json = """
                {"schemaVersion": 7,
                 "session":{"id":"s1","port":5010},
                 "equipment":{"name":"E","idSpace":{},
                   "units":[{"unitId":1,"name":"CH1",
                     "svids":[{"offset":1,"slot":"A","type":"F4"},{"offset":1,"slot":"B","type":"F4"}],
                     "reports":[],"ceids":[]}]}}
                """;

        CompilationResult result = SessionConfigJson.parse(json).compile();

        assertFalse(result.ok(), "同单元偏移重复应由编译器拦下");
        assertTrue(result.errors().stream().map(Issue::code).collect(Collectors.toSet())
                .contains("DUPLICATE_OFFSET"));
    }

    @Test
    void utf8BomIsTolerated() {
        String json = "\uFEFF" + SessionConfigJson.toJson(demoConfig());

        SessionConfig parsed = SessionConfigJson.parse(json);
        assertEquals("pri-01", parsed.spec().id());
    }

    /** 空绑定实例是合法的纯中间节点：输出只喂组合，组合经直配糖落到 SVID。 */
    @Test
    void afterEventTriggerRoundTrips() {
        String json = """
                {"schemaVersion": 7,
                 "session":{"id":"s1","port":5010},
                 "equipment":{"name":"E","idSpace":{},
                   "units":[{"unitId":1,"name":"CH1",
                     "svids":[{"offset":1,"slot":"TEMP","type":"F4"}],"reports":[],"ceids":[]}]},
                 "events":{"plans":[
                   {"id":"start","ceid":2001,"trigger":{"type":"at","atMs":1000}},
                   {"id":"follow","ceid":2003,"trigger":{"type":"afterEvent","sourcePlan":"start","delayMs":500}}
                 ]}}
                """;

        SessionConfig parsed = SessionConfigJson.parse(json);
        Trigger trigger = parsed.schedule().plans().get(1).trigger();
        assertTrue(trigger instanceof Trigger.AfterEvent, "应为 AfterEvent");

        Trigger.AfterEvent after = (Trigger.AfterEvent) trigger;
        assertEquals("start", after.sourcePlan());
        assertEquals(500L, after.delayMs());
        assertTrue(SessionConfigJson.toJson(parsed).contains("\"afterEvent\""));
    }

    @Test
    void afterEventUnknownSourceIsRejected() {
        String json = """
                {"schemaVersion": 7,
                 "session":{"id":"s1","port":5010},
                 "equipment":{"name":"E","idSpace":{},
                   "units":[{"unitId":1,"name":"CH1",
                     "svids":[{"offset":1,"slot":"TEMP","type":"F4"}],"reports":[],"ceids":[]}]},
                 "events":{"plans":[{"id":"follow","ceid":2003,
                   "trigger":{"type":"afterEvent","sourcePlan":"missing","delayMs":500}}]}}
                """;

        ConfigException e = assertThrows(ConfigException.class, () -> SessionConfigJson.parse(json));
        assertTrue(e.getMessage().contains("不存在的计划"), e.getMessage());
    }

    @Test
    void onConditionTriggerRoundTrips() {
        String json = """
                {"schemaVersion": 7,
                 "session":{"id":"s1","port":5010},
                 "equipment":{"name":"E","idSpace":{},
                   "units":[{"unitId":1,"name":"CH1",
                     "svids":[{"offset":1,"slot":"TEMP","type":"F4"}],"reports":[],"ceids":[]}]},
                 "events":{"plans":[{"id":"hot","ceid":2001,
                   "trigger":{"type":"onCondition","expression":"svid.1001 > 190","debounceMs":1000}}]}}
                """;

        SessionConfig parsed = SessionConfigJson.parse(json);
        Trigger trigger = parsed.schedule().plans().get(0).trigger();
        assertTrue(trigger instanceof Trigger.OnCondition, "应为 OnCondition");

        Trigger.OnCondition condition = (Trigger.OnCondition) trigger;
        assertEquals("svid.1001 > 190", condition.expression().source());
        assertEquals(1_000L, condition.debounceMs());

        String serialized = SessionConfigJson.toJson(parsed);
        assertTrue(serialized.contains("\"onCondition\""));
        assertTrue(serialized.contains("svid.1001 > 190"));
    }

    @Test
    void s6f11WbitSurvivesRoundTrip() {
        SessionConfig base = demoConfig();
        SessionConfig withWbit = new SessionConfig(base.spec(), base.schedule(), base.maxEventsPerSec(), true);

        String json = SessionConfigJson.toJson(withWbit);
        assertTrue(json.contains("\"s6f11Wbit\""), "true 时必须写回 JSON");
        assertTrue(SessionConfigJson.parse(json).s6f11Wbit());
    }

    @Test
    void s6f1WbitSurvivesRoundTrip() {
        SessionConfig base = demoConfig();
        SessionConfig withWbit = new SessionConfig(base.spec(), base.schedule(), base.maxEventsPerSec(),
                false, true);

        String json = SessionConfigJson.toJson(withWbit);
        assertTrue(json.contains("\"s6f1Wbit\""), "true 时必须写回 JSON");
        assertTrue(SessionConfigJson.parse(json).s6f1Wbit());
    }

    @Test
    void globalSvidRangeRoundTrips() {
        String json = """
                {"schemaVersion": 7,
                 "session":{"id":"s1","port":5010},
                 "equipment":{"name":"E","idSpace":{},
                   "globalSvids":[{"id":9001,"name":"FACILITY_TEMP","type":"F4","min":18.0,"max":26.0}],
                   "units":[{"unitId":1,"name":"CH1",
                     "svids":[{"offset":1,"slot":"TEMP","type":"F4"}],"reports":[],"ceids":[]}]}}
                """;

        SessionConfig parsed = SessionConfigJson.parse(json);
        GlobalSvidDef global = parsed.spec().equipment().globalSvids().get(0);

        assertEquals(18.0, global.min());
        assertEquals(26.0, global.max());

        String serialized = SessionConfigJson.toJson(parsed);
        assertTrue(serialized.contains("\"min\""), "min 必须被写回 JSON");
        assertTrue(serialized.contains("\"max\""), "max 必须被写回 JSON");
    }

    @Test
    void unknownTriggerTypeReportsPath() {
        String json = """
                {"schemaVersion": 7,
                 "session":{"id":"s1","port":5010},
                 "equipment":{"name":"E","idSpace":{},
                   "units":[{"unitId":1,"name":"CH1",
                     "svids":[{"offset":1,"slot":"A","type":"F4"}],"reports":[],"ceids":[]}]},
                 "events":{"plans":[{"id":"p","ceid":2001,"trigger":{"type":"cron"}}]}}
                """;

        ConfigException e = assertThrows(ConfigException.class, () -> SessionConfigJson.parse(json));
        assertEquals("$.events.plans[0].trigger.type", e.path());
    }
    @Test
    void portIsRequiredAndCannotBeZero() {
        /* 端口必填（1..65535）：没有兜底，设备的 SECS 口以配置（数据库）为准 */
        String missing = """
                {"schemaVersion": 7,
                 "session": {"id": "t", "passive": true, "deviceId": 1},
                 "equipment": {"name": "T", "idSpace": {}, "units": []},
                 "events": {"plans": []}}
                """;
        ConfigException missingError = assertThrows(ConfigException.class, () -> SessionConfigJson.parse(missing));
        assertTrue(missingError.getMessage().contains("port"), missingError.getMessage());

        String zero = """
                {"schemaVersion": 7,
                 "session": {"id": "t", "passive": true, "port": 0, "deviceId": 1},
                 "equipment": {"name": "T", "idSpace": {}, "units": []},
                 "events": {"plans": []}}
                """;
        ConfigException zeroError = assertThrows(ConfigException.class, () -> SessionConfigJson.parse(zero));
        assertTrue(zeroError.getMessage().contains("1..65535"), zeroError.getMessage());
    }

    @Test
    void parsesOptionalHsmsTimeouts() {
        SessionConfig parsed = SessionConfigJson.parse("""
                {"schemaVersion": 7,
                 "session": {"id": "t", "passive": true, "port": 5010, "deviceId": 1,
                             "timeouts": {"t3": 60, "t7": 15}},
                 "equipment": {"name": "T", "idSpace": {}, "units": [{ "unitId": 1, "name": "CH1", "svids": [], "reports": [], "ceids": [] }]},
                 "events": {"plans": []}}
                """);

        assertEquals(60.0, parsed.spec().timeouts().t3());
        assertEquals(15.0, parsed.spec().timeouts().t7());
        assertNull(parsed.spec().timeouts().t5());
        assertNull(parsed.spec().timeouts().t6());
        assertNull(parsed.spec().timeouts().t8());
    }

    @Test
    void timeoutsSectionIsOptional() {
        SessionConfig parsed = SessionConfigJson.parse("""
                {"schemaVersion": 7,
                 "session": {"id": "t", "passive": true, "port": 5010, "deviceId": 1},
                 "equipment": {"name": "T", "idSpace": {}, "units": [{ "unitId": 1, "name": "CH1", "svids": [], "reports": [], "ceids": [] }]},
                 "events": {"plans": []}}
                """);

        assertEquals(HsmsTimeouts.DEFAULTS, parsed.spec().timeouts());
    }

    @Test
    void rejectsNonPositiveTimeout() {
        com.shimizukenta.secssim.app.config.ConfigException error =
                assertThrows(com.shimizukenta.secssim.app.config.ConfigException.class,
                        () -> SessionConfigJson.parse("""
                                {"schemaVersion": 7,
                                 "session": {"id": "t", "passive": true, "port": 5010, "deviceId": 1,
                                             "timeouts": {"t3": 0}},
                                 "equipment": {"name": "T", "idSpace": {}, "units": [{ "unitId": 1, "name": "CH1", "svids": [], "reports": [], "ceids": [] }]},
                                 "events": {"plans": []}}
                                """));
        assertTrue(error.getMessage().contains("必须大于 0"), error.getMessage());
    }

    @Test
    void hsmsTimeoutsSurviveRoundTrip() {
        SessionSpec spec = new SessionSpec("t", true, "127.0.0.1", 5010, 1, 3L, twoChambers(),
                new HsmsTimeouts(60.0, null, null, 15.0, null));
        SessionConfig config = new SessionConfig(spec, EventSchedule.empty(), 20);

        String json = SessionConfigJson.toJson(config);
        assertTrue(json.contains("\"timeouts\""), "显式写过的超时必须能往返: " + json);

        SessionConfig parsed = SessionConfigJson.parse(json);
        assertEquals(60.0, parsed.spec().timeouts().t3());
        assertEquals(15.0, parsed.spec().timeouts().t7());
        assertNull(parsed.spec().timeouts().t5(), "没写的键不应被补成默认值写进文件");

        /* 没有 timeouts 的配置不应凭空多出一个空对象 */
        SessionConfig noTimeouts = new SessionConfig(
                new SessionSpec("t", true, "127.0.0.1", 5010, 1, 3L, twoChambers()),
                EventSchedule.empty(), 20);
        assertFalse(SessionConfigJson.toJson(noTimeouts).contains("\"timeouts\""));
    }

    @Test
    void traceSectionIsOptional() {
        SessionConfig parsed = SessionConfigJson.parse("""
                {"schemaVersion": 7,
                 "session": {"id": "t", "passive": true, "port": 5010, "deviceId": 1},
                 "equipment": {"name": "T", "idSpace": {}, "units": [{ "unitId": 1, "name": "CH1", "svids": [], "reports": [], "ceids": [] }]},
                 "events": {"plans": []}}
                """);

        assertEquals(TraceLimits.DEFAULTS, parsed.spec().traceLimits());
    }

    @Test
    void rejectsNonPositiveTraceLimit() {
        com.shimizukenta.secssim.app.config.ConfigException error =
                assertThrows(com.shimizukenta.secssim.app.config.ConfigException.class,
                        () -> SessionConfigJson.parse("""
                                {"schemaVersion": 7,
                                 "session": {"id": "t", "passive": true, "port": 5010, "deviceId": 1,
                                             "trace": {"maxTraces": 0}},
                                 "equipment": {"name": "T", "idSpace": {}, "units": [{ "unitId": 1, "name": "CH1", "svids": [], "reports": [], "ceids": [] }]},
                                 "events": {"plans": []}}
                                """));
        assertTrue(error.getMessage().contains("必须 ≥ 1"), error.getMessage());
    }

    @Test
    void traceLimitsSurviveRoundTrip() {
        SessionSpec spec = new SessionSpec("t", true, "127.0.0.1", 5010, 1, 3L, twoChambers(),
                HsmsTimeouts.DEFAULTS, new TraceLimits(4, 32));
        SessionConfig config = new SessionConfig(spec, EventSchedule.empty(), 20);

        String json = SessionConfigJson.toJson(config);
        assertTrue(json.contains("\"trace\""), "显式写过的容量必须能往返: " + json);

        SessionConfig parsed = SessionConfigJson.parse(json);
        assertEquals(4, parsed.spec().traceLimits().maxTraces());
        assertEquals(32, parsed.spec().traceLimits().maxSvidCount());

        /* 没有 trace 的配置不应凭空多出一个空对象 */
        SessionConfig noTrace = new SessionConfig(
                new SessionSpec("t", true, "127.0.0.1", 5010, 1, 3L, twoChambers()),
                EventSchedule.empty(), 20);
        assertFalse(SessionConfigJson.toJson(noTrace).contains("\"trace\""));
    }

    @Test
    void unknownPlanCeidIsACompileError() {
        String json = """
                {"schemaVersion": 7,
                 "session":{"id":"s1","port":5010},
                 "equipment":{"name":"E","idSpace":{},
                   "units":[{"unitId":1,"name":"CH1","injectUnitId":false,
                     "svids":[{"offset":1,"slot":"TEMP","type":"F4"}],"reports":[],"ceids":[]}]},
                 "events":{"plans":[{"id":"p","ceid":9999,
                   "trigger":{"type":"periodic","periodMs":1000}}]}}
                """;

        CompilationResult result = SessionConfigJson.parse(json).compile();

        assertFalse(result.ok(), "引用不存在的 CEID 必须在编译期拦下");
        assertTrue(result.errors().stream().map(Issue::code).collect(Collectors.toSet())
                        .contains("UNKNOWN_CEID"),
                () -> "应报 UNKNOWN_CEID: " + result.issues());
    }

    @Test
    void duplicateGlobalSvidNameIsAWarning() {
        String json = """
                {"schemaVersion": 7,
                 "session":{"id":"s1","port":5010},
                 "equipment":{"name":"E","idSpace":{},
                   "globalSvids":[
                     {"id":9001,"name":"FACILITY_TEMP","type":"F4"},
                     {"id":9002,"name":"FACILITY_TEMP","type":"F4"}],
                   "units":[{"unitId":1,"name":"CH1","injectUnitId":false,
                     "svids":[{"offset":1,"slot":"TEMP","type":"F4"}],"reports":[],"ceids":[]}]}}
                """;

        CompilationResult result = SessionConfigJson.parse(json).compile();

        assertTrue(result.ok(), "重名只提示，不阻断编译: " + result.errors());
        assertTrue(result.warnings().stream().map(Issue::code).collect(Collectors.toSet())
                        .contains("DUPLICATE_GLOBAL_SVID_NAME"),
                () -> "应提示重名: " + result.warnings());
    }

    /* ===== schema v6:事件效果 + SVID 直配值来源 ===== */

    private static final String V7_SAMPLE = """
            {"schemaVersion":7,
             "session":{"id":"s1","port":5010},
             "context":[
               {"id":"LOTID","type":"ASCII","source":{"mode":"cycle","pattern":"LOT{02}","start":1}},
               {"id":"WAFERID","type":"ASCII","source":{"mode":"cycle","pattern":"W{03}","start":1}}
             ],
             "cycle":{"durationMs":60000,"jitterMs":0,"count":3,"noisePerWafer":false,
                      "events":[{"atMs":3000,"ceid":2001}]},
             "equipment":{"name":"E","idSpace":{},
               "constants":[{"id":501,"name":"TEMP_LIMIT","type":"F4","value":5,
                               "source":{"mode":"const","value":5}}],
               "units":[{"unitId":1,"name":"CH1","injectUnitId":false,
                 "svids":[
                   {"offset":1,"slot":"TEMP","type":"F4",
                    "source":{"mode":"curve","clock":"phase","curve":{"type":"constant","value":180}}},
                   {"offset":2,"slot":"PRESS","type":"F4",
                    "source":{"mode":"expr","clock":"phase","vars":{"a":{"type":"constant","value":2}},"expr":"a*2"}}
                 ],
                 "reports":[{"offset":1,"slot":"STATUS",
                             "svids":["TEMP","ctx.LOTID","ctx.WAFERID","ecid.501"]}],
                 "ceids":[{"offset":1,"slot":"E1","reports":["STATUS"]}]}]},
             "events":{"maxPerSec":50,"plans":[]}}
            """;

    @Test
    void parsesV7SourcesAndContext() {
        SessionConfig config = SessionConfigJson.parse(V7_SAMPLE);
        CompilationResult result = config.compile();
        assertTrue(result.ok(), () -> "v7 样例应能编译: " + result.issues());

        assertEquals(2, config.context().size());
        assertEquals("LOTID", config.context().get(0).id());
        assertEquals(2, config.svidSources().size(), "两个 SVID 都配了 source");
        assertTrue(config.svidSources().containsKey(1001));
        assertEquals(1, config.ecidSources().size(), "只有配了 source 的 ECID 进映射");
    }

    @Test
    void v7ReportCarriesContextAndEcid() {
        SessionConfig config = SessionConfigJson.parse(V7_SAMPLE);
        CompilationResult result = config.compile();
        assertTrue(result.ok(), () -> "编译应通过: " + result.issues());

        var report = result.equipment().reports().get(3001);
        assertNotNull(report, "STATUS 报告应解析成 3001");
        assertEquals(4, report.fields().size(), "TEMP + 两个属性 + 一个常量");
        assertTrue(report.fields().stream().anyMatch(f -> f instanceof com.shimizukenta.secssim.core.compile.FieldRef.Context));
        assertTrue(report.fields().stream().anyMatch(f -> f instanceof com.shimizukenta.secssim.core.compile.FieldRef.Ecid));
    }

    @Test
    void unknownContextReferenceIsCompileError() {
        String bad = V7_SAMPLE.replace("\"ctx.WAFERID\"", "\"ctx.NOPE\"");
        CompilationResult result = SessionConfigJson.parse(bad).compile();
        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(i -> i.toString().contains("UNKNOWN_CONTEXT_REF")),
                () -> "应报 UNKNOWN_CONTEXT_REF: " + result.errors());
    }

    @Test
    void unknownEcidReferenceIsCompileError() {
        String bad = V7_SAMPLE.replace("\"ecid.501\"", "\"ecid.999\"");
        CompilationResult result = SessionConfigJson.parse(bad).compile();
        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(i -> i.toString().contains("UNKNOWN_ECID_REF")),
                () -> "应报 UNKNOWN_ECID_REF: " + result.errors());
    }

    @Test
    void cycleEventsExpandPerWafer() {
        SessionConfig config = SessionConfigJson.parse(V7_SAMPLE);
        /* M3 拆分：编排表只含手写计划；展开改走 CycleConfig.expandPlans（预览合成用） */
        assertEquals(0, config.schedule().plans().size(), "手写计划为空");
        var plans = config.cycle().expandPlans(config.spec().seed());
        assertEquals(3, plans.size(), "3 片 × 1 条周期事件");
        assertEquals("cyc0-0", plans.get(0).id());
        assertEquals(3_000L, ((Trigger.At) plans.get(0).trigger()).atMs(), "第一片事件在片内 3s");
        assertTrue(((Trigger.At) plans.get(2).trigger()).atMs() > 60_000L, "第三片要等前两片跑完");
    }

    @Test
    void v7ConfigRoundTrips() {
        SessionConfig parsed = SessionConfigJson.parse(V7_SAMPLE);
        String first = SessionConfigJson.toJson(parsed);
        SessionConfig reparsed = SessionConfigJson.parse(first);
        assertEquals(first, SessionConfigJson.toJson(reparsed), "v7 字段必须往返稳定");
        assertTrue(first.contains("\"source\""));
        assertTrue(first.contains("\"context\""));
        assertTrue(first.contains("\"cycle\""));
        assertFalse(first.contains("\"effects\""), "v7 不再有事件效果");
    }

    @Test
    void rejectsSourceOnNonNumericSvid() {
        String bad = V7_SAMPLE.replace(
                "{\"offset\":2,\"slot\":\"PRESS\",\"type\":\"F4\"",
                "{\"offset\":2,\"slot\":\"PRESS\",\"type\":\"ASCII\"");
        CompilationResult result = SessionConfigJson.parse(bad).compile();
        assertFalse(result.ok(), "非数值 SVID 配值定义应报错");
        assertTrue(result.errors().stream().anyMatch(i -> i.toString().contains("SOURCE_TYPE_MISMATCH")),
                () -> "应报 SOURCE_TYPE_MISMATCH: " + result.errors());
    }

}
