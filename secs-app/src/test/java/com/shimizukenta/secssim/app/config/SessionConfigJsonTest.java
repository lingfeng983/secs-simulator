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
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.Issue;
import com.shimizukenta.secssim.core.curve.Rng;
import com.shimizukenta.secssim.core.value.GraphValueSource;
import com.shimizukenta.secssim.core.domain.CeidDef;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.core.domain.GlobalSvidDef;
import com.shimizukenta.secssim.core.domain.IdSpacePolicy;
import com.shimizukenta.secssim.core.domain.ReportDef;
import com.shimizukenta.secssim.core.domain.SvidDef;
import com.shimizukenta.secssim.core.domain.UnitId;
import com.shimizukenta.secssim.core.domain.UnitSpec;
import com.shimizukenta.secssim.core.domain.ValueType;
import com.shimizukenta.secssim.core.event.EventEffect;
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
                {"schemaVersion":4,"session":{"port":5000},"equipment":{"name":"E","idSpace":{},"units":[]}}
                """;

        ConfigException e = assertThrows(ConfigException.class, () -> SessionConfigJson.parse(json));
        assertEquals("$.session.id", e.path());
    }

    @Test
    void badSvidTypeReportsFieldPath() {
        String json = """
                {"schemaVersion":4,
                 "session":{"id":"s1"},
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
                {"schemaVersion":4,"session":{"id":"s1"},"equipment":{"name":"E","idSpace":{}}}
                """;

        ConfigException e = assertThrows(ConfigException.class, () -> SessionConfigJson.parse(json));
        assertTrue(e.path().startsWith("$.equipment.units"), e.path());
    }

    @Test
    void semanticErrorsAreLeftToTheCompiler() {
        String json = """
                {"schemaVersion":4,
                 "session":{"id":"s1"},
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

    @Test
    void templateConfigBuildsGraph() {
        String json = """
                {"schemaVersion":5,
                 "session":{"id":"s1"},
                 "equipment":{"name":"E","idSpace":{},
                   "units":[{"unitId":1,"name":"CH1","injectUnitId":false,
                     "svids":[{"offset":1,"slot":"TEMP","type":"F4"},
                               {"offset":2,"slot":"DERIVED","type":"F4"}],
                     "reports":[],"ceids":[]}]},
                 "templates":{
                   "curves":[{"id":"temp","type":"sine","min":100.0,"max":200.0,"periodMs":1000}],
                   "composites":[{"id":"derived","outputs":{"V":"in.temp.V * 0.5"}}],
                   "instances":[
                     {"id":"temp","template":"temp","svids":{"V":1001}},
                     {"id":"derived","template":"derived","svids":{"V":1002}}
                   ]
                 }}
                """;

        SessionConfig config = SessionConfigJson.parse(json);
        CompilationResult compiled = config.compile();
        assertTrue(compiled.ok(), () -> "配置不合法: " + compiled.issues());

        GraphValueSource source = new GraphValueSource(
                config.templates().build(compiled.equipment()), new Rng(1L).derive("graph"));
        assertEquals(150.0, source.valueAt(1001, 0L).orElseThrow(), 1e-9, "sine t=0 应为中点");
        assertEquals(75.0, source.valueAt(1002, 0L).orElseThrow(), 1e-9, "组合模板应派生");
    }

    @Test
    void afterEventTriggerRoundTrips() {
        String json = """
                {"schemaVersion":4,
                 "session":{"id":"s1"},
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
                {"schemaVersion":4,
                 "session":{"id":"s1"},
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
                {"schemaVersion":4,
                 "session":{"id":"s1"},
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
                {"schemaVersion":4,
                 "session":{"id":"s1"},
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
                {"schemaVersion":4,
                 "session":{"id":"s1"},
                 "equipment":{"name":"E","idSpace":{},
                   "units":[{"unitId":1,"name":"CH1",
                     "svids":[{"offset":1,"slot":"A","type":"F4"}],"reports":[],"ceids":[]}]},
                 "events":{"plans":[{"id":"p","ceid":2001,"trigger":{"type":"cron"}}]}}
                """;

        ConfigException e = assertThrows(ConfigException.class, () -> SessionConfigJson.parse(json));
        assertEquals("$.events.plans[0].trigger.type", e.path());
    }
    @Test
    void parsesOptionalHsmsTimeouts() {
        SessionConfig parsed = SessionConfigJson.parse("""
                {"schemaVersion": 5,
                 "session": {"id": "t", "passive": true, "port": 0, "deviceId": 1,
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
                {"schemaVersion": 5,
                 "session": {"id": "t", "passive": true, "port": 0, "deviceId": 1},
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
                                {"schemaVersion": 5,
                                 "session": {"id": "t", "passive": true, "port": 0, "deviceId": 1,
                                             "timeouts": {"t3": 0}},
                                 "equipment": {"name": "T", "idSpace": {}, "units": [{ "unitId": 1, "name": "CH1", "svids": [], "reports": [], "ceids": [] }]},
                                 "events": {"plans": []}}
                                """));
        assertTrue(error.getMessage().contains("必须大于 0"), error.getMessage());
    }

    @Test
    void hsmsTimeoutsSurviveRoundTrip() {
        SessionSpec spec = new SessionSpec("t", true, "127.0.0.1", 0, 1, 3L, twoChambers(),
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
                new SessionSpec("t", true, "127.0.0.1", 0, 1, 3L, twoChambers()),
                EventSchedule.empty(), 20);
        assertFalse(SessionConfigJson.toJson(noTimeouts).contains("\"timeouts\""));
    }

    @Test
    void unknownPlanCeidIsACompileError() {
        String json = """
                {"schemaVersion":4,
                 "session":{"id":"s1"},
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
                {"schemaVersion":4,
                 "session":{"id":"s1"},
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

    private static final String V6_SAMPLE = """
            {
              "schemaVersion": 6,
              "session": { "id": "v6-01", "passive": true, "port": 5000, "deviceId": 7, "seed": 42 },
              "equipment": {
                "name": "V6",
                "idSpace": { "svidBase": 1000, "ceidBase": 2000, "reportBase": 3000 },
                "globalSvids": [
                  { "id": 9001, "name": "FAC", "type": "F4", "curve": { "type": "sine", "min": 1, "max": 2, "periodMs": 1000 } }
                ],
                "units": [
                  {
                    "unitId": 1, "name": "CH1", "injectUnitId": true,
                    "svids": [
                      { "offset": 90, "slot": "UNIT_ID", "type": "U4" },
                      { "offset": 1, "slot": "TEMP_PV", "type": "F4", "min": 0, "max": 400,
                        "curve": { "type": "constant", "value": 180 } },
                      { "offset": 2, "slot": "PRESS", "type": "F4", "template": "press", "params": { "base": 1.0 } },
                      { "offset": 3, "slot": "HEATER", "type": "BOOLEAN", "min": 0, "max": 1 }
                    ],
                    "reports": [ { "offset": 1, "slot": "STATUS", "svids": ["UNIT_ID", "TEMP_PV"] } ],
                    "ceids": [ { "offset": 1, "slot": "E1", "reports": ["STATUS"] } ]
                  }
                ]
              },
              "events": {
                "maxPerSec": 50,
                "plans": [
                  { "id": "p1", "ceid": 2001,
                    "trigger": { "type": "periodic", "periodMs": 1000 },
                    "effects": [
                      { "svid": 1001, "op": "pulse", "value": 250, "holdMs": 5000 },
                      { "svid": 1001, "op": "set", "value": 300, "decayMs": 10000 },
                      { "svid": 1001, "op": "shift", "delta": 10 },
                      { "svid": 1003, "op": "set", "value": 1 }
                    ] }
                ]
              },
              "templates": { "curves": [ { "id": "press", "type": "sine", "min": 0.1, "max": 2, "periodMs": 5000 } ] }
            }
            """;

    @Test
    void parsesV6DirectSourcesAndEffects() {
        SessionConfig config = SessionConfigJson.parse(V6_SAMPLE);
        CompilationResult result = config.compile();
        assertTrue(result.ok(), "v6 样例应能编译: " + result.errors());

        SvidDef temp = config.spec().equipment().units().get(0).svids().get(1);
        assertNotNull(temp.source(), "TEMP_PV 应带直配曲线");
        assertNull(temp.source().template());

        SvidDef press = config.spec().equipment().units().get(0).svids().get(2);
        assertEquals("press", press.source().template());
        assertEquals(Map.of("base", 1.0), press.source().params());

        assertTrue(config.hasCustomValues());
        EventPlan plan = config.schedule().plans().get(0);
        assertEquals(4, plan.effects().size());
        assertEquals(EventEffect.Op.PULSE, plan.effects().get(0).op());
    }

    @Test
    void v6SampleBuildsValueSource() {
        SessionConfig config = SessionConfigJson.parse(V6_SAMPLE);
        CompilationResult result = config.compile();
        assertTrue(result.ok());

        var values = ValueSources.of(config, result.equipment());
        /* 值来源统一包一层值域钳制（P1-6）：模板图是它的下游 */
        assertTrue(values instanceof com.shimizukenta.secssim.core.value.BoundedValueSource);
        assertTrue(((com.shimizukenta.secssim.core.value.BoundedValueSource) values).inner()
                instanceof GraphValueSource);
        /* 直配 constant 曲线:任意时刻都是 180 */
        assertEquals(180.0, values.valueAt(1001, 12345L).orElse(-1), 1e-9);
        /* 模板图里未绑定值来源的 SVID 取不到(上层按 0 处理) */
        assertTrue(values.valueAt(1003, 12345L).isEmpty());
        /* 全局 SVID 走内联 sine */
        assertTrue(values.valueAt(9001, 0L).isPresent());
    }

    @Test
    void v6ConfigRoundTrips() {
        SessionConfig parsed = SessionConfigJson.parse(V6_SAMPLE);
        String first = SessionConfigJson.toJson(parsed);
        SessionConfig reparsed = SessionConfigJson.parse(first);
        assertEquals(first, SessionConfigJson.toJson(reparsed), "v6 字段必须往返稳定");
        assertTrue(first.contains("\"curve\""));
        assertTrue(first.contains("\"effects\""));
    }

    @Test
    void rejectsCurveAndTemplateTogether() {
        String bad = V6_SAMPLE.replace(
                "\"curve\": { \"type\": \"constant\", \"value\": 180 }",
                "\"curve\": { \"type\": \"constant\", \"value\": 180 }, \"template\": \"press\"");
        ConfigException e = assertThrows(ConfigException.class, () -> SessionConfigJson.parse(bad));
        assertTrue(e.getMessage().contains("二选一"));
    }

    @Test
    void rejectsParamsWithoutTemplate() {
        String bad = V6_SAMPLE.replace(
                "\"curve\": { \"type\": \"constant\", \"value\": 180 }",
                "\"curve\": { \"type\": \"constant\", \"value\": 180 }, \"params\": { \"a\": 1 }");
        ConfigException e = assertThrows(ConfigException.class, () -> SessionConfigJson.parse(bad));
        assertTrue(e.getMessage().contains("template"));
    }

    @Test
    void rejectsUnknownEffectSvid() {
        String bad = V6_SAMPLE.replace("{ \"svid\": 1003, \"op\": \"set\", \"value\": 1 }",
                "{ \"svid\": 4242, \"op\": \"set\", \"value\": 1 }");
        CompilationResult result = SessionConfigJson.parse(bad).compile();
        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(i -> i.toString().contains("UNKNOWN_EFFECT_SVID")));
    }

    @Test
    void rejectsEffectOnNonNumericSvid() {
        String bad = V6_SAMPLE.replace("{ \"svid\": 1003, \"op\": \"set\", \"value\": 1 }",
                "{ \"svid\": 1003, \"op\": \"pulse\", \"value\": 1, \"holdMs\": 100 }");
        CompilationResult result = SessionConfigJson.parse(bad).compile();
        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(i -> i.toString().contains("EFFECT_TYPE_MISMATCH")));
    }

    @Test
    void allowsBooleanSetForEffects() {
        CompilationResult result = SessionConfigJson.parse(V6_SAMPLE).compile();
        assertTrue(result.ok(), "BOOLEAN set 0/1 应放行: " + result.errors());
    }

    @Test
    void rejectsDirectSourceOnStringSvid() {
        String bad = V6_SAMPLE.replace(
                "\"curve\": { \"type\": \"constant\", \"value\": 180 }",
                "\"curve\": { \"type\": \"constant\", \"value\": 180 }")
                .replace("{ \"offset\": 2, \"slot\": \"PRESS\", \"type\": \"F4\", \"template\": \"press\", \"params\": { \"base\": 1.0 } }",
                        "{ \"offset\": 2, \"slot\": \"NAME\", \"type\": \"ASCII\", \"curve\": { \"type\": \"constant\", \"value\": 1 } }");
        CompilationResult result = SessionConfigJson.parse(bad).compile();
        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(i -> i.toString().contains("SOURCE_TYPE_MISMATCH")));
    }

    @Test
    void rejectsSingleWriterBetweenDirectAndInstance() {
        String bad = V6_SAMPLE.replace(
                """
                      "templates": { "curves": [ { "id": "press", "type": "sine", "min": 0.1, "max": 2, "periodMs": 5000 } ] }""",
                """
                      "templates": { "curves": [ { "id": "press", "type": "sine", "min": 0.1, "max": 2, "periodMs": 5000 } ],
                                     "instances": [ { "id": "dup", "template": "press", "svids": { "V": 1001 } } ] }""");
        SessionConfig config = SessionConfigJson.parse(bad);
        CompilationResult result = config.compile();
        assertTrue(result.ok());
        ConfigException e = assertThrows(ConfigException.class, () -> config.buildValues(result.equipment()));
        assertTrue(e.getMessage().contains("1001"), "单写者冲突应指向重复的 SVID: " + e.getMessage());
    }
}
