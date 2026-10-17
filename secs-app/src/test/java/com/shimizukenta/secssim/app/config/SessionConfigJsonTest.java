package com.shimizukenta.secssim.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.Issue;
import com.shimizukenta.secssim.core.domain.CeidDef;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
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
        String json = SessionConfigJson.toJson(demoConfig()).replace("\"schemaVersion\": 4", "\"schemaVersion\": 3");

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
}
