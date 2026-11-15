package com.shimizukenta.secssim.runtime.session;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secssim.app.config.SessionConfig;
import com.shimizukenta.secssim.app.config.SessionConfigJson;
import com.shimizukenta.secssim.app.config.ValueSources;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.value.ValueSource;

/**
 * S6F11 里三类数据混排：晶圆属性、设备常量、参数变量。
 *
 * <p>这是 v7 的关键闭环——过去报告只能装 SVID，LOTID 这种字符串根本没有落脚点
 * （ASCII 型 SVID 只能存数字）。现在一条报告里能同时给出"哪一批、哪一片、
 * 门限多少、当前温度"，而事件本身不改任何值。</p>
 */
class ReportFieldsTest {

    private static final String JSON = """
            {
              "schemaVersion": 7,
              "session": { "id": "pri-01", "passive": true, "port": 5000, "deviceId": 0, "seed": 3 },
              "context": [
                { "id": "LOTID",   "type": "ASCII", "source": { "mode": "cycle", "pattern": "LOT{02}", "start": 1 } },
                { "id": "WAFERID", "type": "ASCII", "source": { "mode": "cycle", "pattern": "W{03}", "start": 1 } }
              ],
              "cycle": { "durationMs": 60000, "jitterMs": 0, "count": 3, "events": [] },
              "equipment": {
                "name": "PRI",
                "idSpace": {},
                "constants": [
                  { "id": 501, "name": "TEMP_LIMIT", "type": "F4", "value": 5.0 }
                ],
                "units": [
                  {
                    "unitId": 1,
                    "name": "CH1",
                    "injectUnitId": false,
                    "svids": [
                      { "offset": 1, "slot": "TEMP", "type": "F4",
                        "source": { "mode": "const", "value": 200 } }
                    ],
                    "reports": [
                      { "offset": 1, "slot": "STATUS",
                        "svids": ["ctx.LOTID", "ctx.WAFERID", "ecid.501", "TEMP"] }
                    ],
                    "ceids": [ { "offset": 1, "slot": "STATUS_REPORT", "reports": ["STATUS"] } ]
                  }
                ]
              },
              "events": { "maxPerSec": 5, "plans": [] }
            }
            """;

    @Test
    void 报告可同时携带晶圆属性设备常量与参数变量() {
        SessionConfig config = SessionConfigJson.parse(JSON);
        CompilationResult compiled = config.compile();
        assertTrue(compiled.ok(), () -> "应通过编译: " + compiled.issues());

        ValueSource values = ValueSources.of(config, compiled.equipment());
        java.util.Map<Integer, com.shimizukenta.secssim.core.domain.EquipmentConstantDef> constants =
                new java.util.LinkedHashMap<>();
        for (com.shimizukenta.secssim.core.domain.EquipmentConstantDef def
                : config.spec().equipment().constants()) {
            constants.put(def.id(), def);
        }
        EcidValues ecids = new EcidValues(constants, config.ecidSources(),
                config.cycleClock(), config.spec().seed());

        S6F11Assembler assembler = new S6F11Assembler(compiled.equipment(), values,
                new com.shimizukenta.secssim.app.session.HostReportTable(),
                config.contextTable(), ecids);

        int ceid = compiled.equipment().ceids().keySet().iterator().next();
        Secs2 body = assembler.build(ceid, 0L);
        String dump = String.valueOf(body);

        assertTrue(dump.contains("LOT01"), "第一片应带 LOT01: " + dump);
        assertTrue(dump.contains("W001"), "第一片应带 W001: " + dump);

        Secs2 second = assembler.build(ceid, 60_000L);
        String secondDump = String.valueOf(second);
        assertTrue(secondDump.contains("W002"), "第二片应带 W002: " + secondDump);
    }
}
