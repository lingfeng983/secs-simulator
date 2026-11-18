package com.shimizukenta.secssim.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.shimizukenta.jsonhub.JsonHub;

/**
 * 分段替换与守卫的单测（2026-11-18 配置按页保存）。
 *
 * <p>配置样本里：CEID 段起点 2000、步长 100，单元 1 的 offset=1 → 绝对 CEID 2001，
 * 正好是 cycle.events 里引用的那个——守卫 B 的引用链就是靠这个对上号的。</p>
 */
class ConfigSectionPatcherTest {

    private static final String CONFIG = """
            {
              "schemaVersion": 7,
              "session": { "id": "LINE1", "passive": true, "bindHost": "0.0.0.0", "port": 5010,
                           "deviceId": 0, "seed": 1 },
              "context": [
                { "id": "LOTID", "type": "ASCII", "source": { "mode": "const", "value": "L1" } }
              ],
              "equipment": {
                "name": "DEMO",
                "idSpace": { "svidBase": 1000, "ceidBase": 2000, "reportBase": 3000,
                             "svidStride": 100, "ceidStride": 100, "reportStride": 100 },
                "constants": [
                  { "id": 501, "name": "TEMP_LIMIT", "type": "F4", "value": 240, "unit": "C" }
                ],
                "units": [
                  {
                    "unitId": 1, "name": "CH1", "injectUnitId": true,
                    "svids": [
                      { "offset": 1, "slot": "TEMP_PV", "type": "F4", "unit": "C", "decimals": 2,
                        "source": { "mode": "const", "value": 200 } },
                      { "offset": 2, "slot": "PRESS", "type": "F4", "decimals": 2,
                        "source": { "mode": "const", "value": 1.2 } }
                    ],
                    "reports": [
                      { "offset": 1, "slot": "PROCESS_DATA", "svids": ["TEMP_PV", "ctx.LOTID"] }
                    ],
                    "ceids": [
                      { "offset": 1, "slot": "PROCESS_START", "reports": ["PROCESS_DATA"] }
                    ]
                  }
                ]
              },
              "cycle": {
                "durationMs": 180000, "jitterMs": 0, "count": 25,
                "events": [ { "atMs": 3000, "ceid": 2001 } ]
              },
              "events": {
                "maxPerSec": 20, "s6f11Wbit": true, "s6f1Wbit": true,
                "plans": [ { "id": "p1", "ceid": 2001, "trigger": { "type": "at", "atMs": 1000 } } ]
              }
            }
            """;

    private static Map<ConfigSection, JsonHub> payload(String json) {
        return ConfigSectionPatcher.parsePayload("{\"sections\":" + json + "}");
    }

    @Test
    void 只改周期段时其它段指纹不变() {
        String next = ConfigSectionPatcher.apply(CONFIG,
                payload("{\"cycle\":{\"durationMs\":60000,\"jitterMs\":0,\"count\":5,\"events\":[]}}"));

        Map<ConfigSection, String> before = ConfigSectionPatcher.fingerprints(CONFIG);
        Map<ConfigSection, String> after = ConfigSectionPatcher.fingerprints(next);
        assertEquals(List.of(ConfigSection.CYCLE), ConfigSectionPatcher.changed(before, after));
        assertTrue(next.contains("\"durationMs\": 60000"));
        assertTrue(next.contains("\"id\": \"LINE1\""));
    }

    @Test
    void 周期段传null表示删除() {
        String next = ConfigSectionPatcher.apply(CONFIG, payload("{\"cycle\":null}"));
        Map<ConfigSection, String> after = ConfigSectionPatcher.fingerprints(next);
        Map<ConfigSection, String> before = ConfigSectionPatcher.fingerprints(CONFIG);
        assertEquals(List.of(ConfigSection.CYCLE), ConfigSectionPatcher.changed(before, after));
        assertFalse(next.contains("\"cycle\""));
    }

    @Test
    void 换变量时单元里的报告与事件原样保留() {
        String next = ConfigSectionPatcher.apply(CONFIG, payload("""
                {"svids":{"units":[{"unitId":1,"svids":[
                  {"offset":1,"slot":"TEMP_PV","type":"F4","unit":"C","decimals":2,
                   "source":{"mode":"const","value":210}},
                  {"offset":2,"slot":"PRESS","type":"F4","decimals":2,
                   "source":{"mode":"const","value":1.2}}
                ]}]}}
                """));

        Map<ConfigSection, String> before = ConfigSectionPatcher.fingerprints(CONFIG);
        Map<ConfigSection, String> after = ConfigSectionPatcher.fingerprints(next);
        assertEquals(List.of(ConfigSection.SVIDS), ConfigSectionPatcher.changed(before, after));
        assertTrue(next.contains("PROCESS_DATA"));
        assertTrue(next.contains("\"ceids\""));
    }

    @Test
    void 结构段加单元时新单元带空表_删单元时整块消失() {
        String next = ConfigSectionPatcher.apply(CONFIG, payload("""
                {"structure":{"name":"DEMO","units":[
                  {"unitId":1,"name":"CH1","injectUnitId":true},
                  {"unitId":2,"name":"CH2","injectUnitId":false}
                ]}}
                """));
        assertTrue(next.contains("\"unitId\": 2"));
        assertTrue(next.contains("\"svids\": []"));
        assertEquals(2, countOccurrences(next, "\"unitId\""));

        String dropped = ConfigSectionPatcher.apply(CONFIG, payload("""
                {"structure":{"units":[]}}
                """));
        assertFalse(dropped.contains("CH1"));
    }

    @Test
    void 提交未知单元直接报错() {
        assertThrows(IllegalArgumentException.class, () -> ConfigSectionPatcher.apply(CONFIG, payload("""
                {"svids":{"units":[{"unitId":9,"svids":[]}]}}
                """)));
    }

    @Test
    void 守卫B_改被周期引用的变量会被拦下并报出CEID() {
        Map<ConfigSection, JsonHub> body = payload("""
                {"svids":{"units":[{"unitId":1,"svids":[
                  {"offset":1,"slot":"TEMP_PV","type":"F4","unit":"C","decimals":2,
                   "source":{"mode":"const","value":210}},
                  {"offset":2,"slot":"PRESS","type":"F4","decimals":2,
                   "source":{"mode":"const","value":1.2}}
                ]}]}}
                """);
        List<ConfigSectionGuards.Conflict> conflicts =
                ConfigSectionGuards.cycleRefConflicts(CONFIG, body, List.of(ConfigSection.SVIDS));

        assertEquals(1, conflicts.size());
        assertEquals("svid", conflicts.get(0).kind());
        assertEquals("TEMP_PV", conflicts.get(0).name());
        assertEquals(List.of(2001), conflicts.get(0).ceids());
    }

    @Test
    void 守卫B_改没被引用的变量放行() {
        Map<ConfigSection, JsonHub> body = payload("""
                {"svids":{"units":[{"unitId":1,"svids":[
                  {"offset":1,"slot":"TEMP_PV","type":"F4","unit":"C","decimals":2,
                   "source":{"mode":"const","value":200}},
                  {"offset":2,"slot":"PRESS","type":"F4","decimals":2,
                   "source":{"mode":"const","value":3.0}}
                ]}]}}
                """);
        assertTrue(ConfigSectionGuards.cycleRefConflicts(CONFIG, body, List.of(ConfigSection.SVIDS)).isEmpty());
    }

    @Test
    void 守卫B_改被引用的晶圆属性会被拦下() {
        Map<ConfigSection, JsonHub> body = payload("""
                {"context":[{"id":"LOTID","type":"ASCII","source":{"mode":"const","value":"L9"}}]}
                """);
        List<ConfigSectionGuards.Conflict> conflicts =
                ConfigSectionGuards.cycleRefConflicts(CONFIG, body, List.of(ConfigSection.CONTEXT));

        assertEquals(1, conflicts.size());
        assertEquals("context", conflicts.get(0).kind());
        assertEquals("LOTID", conflicts.get(0).name());
        assertEquals(List.of(2001), conflicts.get(0).ceids());
    }

    @Test
    void 守卫C_运行时常量与配置值不同就给提示() {
        Map<ConfigSection, JsonHub> body = payload("""
                {"constants":[{"id":501,"name":"TEMP_LIMIT","type":"F4","value":250,"unit":"C"}]}
                """);
        List<ConfigSectionGuards.ConstantOverwrite> warnings =
                ConfigSectionGuards.constantOverwrites(CONFIG, body, Map.of(501, 255.0));

        assertEquals(1, warnings.size());
        assertEquals(501, warnings.get(0).ecid());
        assertEquals(255.0, warnings.get(0).runtime());
        assertEquals(250.0, warnings.get(0).config());

        assertTrue(ConfigSectionGuards.constantOverwrites(CONFIG, body, Map.of(501, 250.0)).isEmpty());
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            count += 1;
            at += needle.length();
        }
        return count;
    }
}
