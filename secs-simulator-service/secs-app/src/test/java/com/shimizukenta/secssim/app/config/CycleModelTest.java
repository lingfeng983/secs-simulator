package com.shimizukenta.secssim.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.context.ContextTable;
import com.shimizukenta.secssim.core.value.ValueSource;

/**
 * v7 周期模型：一个周期内参数曲线按相位重放，晶圆属性按片号变化，事件按片展开。
 *
 * <p>这三件事必须同时成立，否则"参数配置与事件配置共用一条时间轴"就是空话：
 * 曲线若用绝对时间，第二片就不会重来；事件若不按片展开，就只在第一片发生。</p>
 */
class CycleModelTest {

    private static final String JSON = """
            {
              "schemaVersion": 7,
              "session": { "id": "pri-01", "passive": true, "port": 5000, "deviceId": 0, "seed": 7 },
              "context": [
                { "id": "LOTID",   "type": "ASCII", "source": { "mode": "cycle", "pattern": "LOT{02}", "start": 1 } },
                { "id": "WAFERID", "type": "ASCII", "source": { "mode": "cycle", "pattern": "W{03}", "start": 1 } },
                { "id": "RCPID",   "type": "ASCII", "source": { "mode": "const", "value": "RCP-A1" } }
              ],
              "cycle": {
                "durationMs": 60000,
                "jitterMs": 0,
                "count": 3,
                "events": [ { "atMs": 3000, "ceid": 2001 } ]
              },
              "equipment": {
                "name": "PRI",
                "idSpace": {},
                "units": [
                  {
                    "unitId": 1,
                    "name": "CH1",
                    "injectUnitId": false,
                    "svids": [
                      {
                        "offset": 1, "slot": "TEMP", "type": "F4",
                        "source": {
                          "mode": "curve", "clock": "phase",
                          "curve": { "type": "sine", "min": 150, "max": 350, "periodMs": 60000 }
                        }
                      }
                    ],
                    "reports": [ { "offset": 1, "slot": "STATUS", "svids": ["TEMP"] } ],
                    "ceids": [ { "offset": 1, "slot": "STATUS_REPORT", "reports": ["STATUS"] } ]
                  }
                ]
              },
              "events": { "maxPerSec": 5, "plans": [] }
            }
            """;

    @Test
    void 曲线按相位重放而晶圆属性按片推进() {
        SessionConfig config = SessionConfigJson.parse(JSON);
        CompilationResult compiled = config.compile();
        assertTrue(compiled.ok(), () -> "应通过编译: " + compiled.issues());

        int svid = compiled.equipment().svids().keySet().iterator().next();
        ValueSource values = ValueSources.of(config, compiled.equipment());

        long period = 60_000L;
        double first = values.valueAt(svid, 15_000L).orElseThrow();
        double second = values.valueAt(svid, 15_000L + period).orElseThrow();
        double third = values.valueAt(svid, 15_000L + 2 * period).orElseThrow();
        double other = values.valueAt(svid, 45_000L).orElseThrow();

        assertEquals(first, second, 1e-9, "第二片同一相位必须回到同一个值");
        assertEquals(first, third, 1e-9, "第三片同理");
        assertNotEquals(first, other, 1e-9, "相位不同则值不同");

        ContextTable context = config.contextTable();
        assertEquals("W001", context.textAt("WAFERID", 0L).orElseThrow());
        assertEquals("W002", context.textAt("WAFERID", period).orElseThrow());
        assertEquals("W003", context.textAt("WAFERID", 2 * period).orElseThrow());
        assertEquals("LOT01", context.textAt("LOTID", 0L).orElseThrow());
        assertEquals("RCP-A1", context.textAt("RCPID", period).orElseThrow());
    }

    @Test
    void 周期事件按片展开() {
        SessionConfig config = SessionConfigJson.parse(JSON);

        assertEquals(0, config.schedule().size(), "配置里手写计划为空");
        assertEquals(3, config.effectiveSchedule().size(), "3 片 × 1 个事件 = 3 条计划");
    }

    @Test
    void 值来源与周期定义能往返() {
        SessionConfig config = SessionConfigJson.parse(JSON);
        String text = SessionConfigJson.toJson(config);

        SessionConfig again = SessionConfigJson.parse(text);

        assertEquals(config.svidSources().keySet(), again.svidSources().keySet());
        assertEquals(config.cycle(), again.cycle());
        assertEquals(config.context().size(), again.context().size());
        assertTrue(text.contains("\"WAFERID\""), text);
        assertTrue(text.contains("\"clock\": \"phase\""), text);
        assertEquals(List.of("LOTID", "WAFERID", "RCPID"), List.copyOf(again.contextTable().ids()));
    }

    @Test
    void 抖动落在区间内且可复现() {
        SessionConfig config = SessionConfigJson.parse(JSON.replace("\"jitterMs\": 0", "\"jitterMs\": 3000"));
        var clock = config.cycleClock();

        for (int wafer = 0; wafer < clock.count(); wafer++) {
            long duration = clock.durationOf(wafer);
            assertTrue(duration >= 57_000L && duration <= 63_000L, "片长应在 ±3s 内: " + duration);
            assertEquals(duration, config.cycleClock().durationOf(wafer), "同一片长必须可复现");
        }
    }
}
