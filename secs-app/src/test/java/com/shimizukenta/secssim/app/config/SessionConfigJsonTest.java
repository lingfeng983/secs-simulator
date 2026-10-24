package com.shimizukenta.secssim.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                new EventPlan("once", 2002, new Trigger.At(1_000L))));
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
        String json = SessionConfigJson.toJson(demoConfig()).replace("\"schemaVersion\": 5", "\"schemaVersion\": 3");

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

}
