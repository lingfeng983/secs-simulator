package com.shimizukenta.secssim.runtime;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 内置设备模板库：新建配置时的选型起点（schema v7）。
 *
 * <p>v7 把「值从哪来」收成一个挂在数据项上的 {@code source}，模板因此不再需要
 * 模板图与实例绑定——每份模板就是"三张表 + 一条周期"：</p>
 * <ul>
 *   <li>{@code context} —— 晶圆属性（LOTID / RCPID / PPID / WAFERID），随片号变；</li>
 *   <li>{@code svids[].source} —— 参数变量，{@code clock: "phase"} 的曲线按片重放；</li>
 *   <li>{@code constants[]} —— 设备常量，可以是常数也可以是时间函数；</li>
 *   <li>{@code cycle.events[]} —— 片内事件，只问"哪一刻、报哪个 CEID"，不改任何值。</li>
 * </ul>
 *
 * <p>目录只保留<b>有代表性</b>的五类设备：刻蚀（温场/射频/腔压）、CVD（沉积累积）、
 * 双单元测试机（号段与跨单元引用）、装载锁（抽真空阶跃）、CMP（压力/转速/去除量）。
 * 其余形态与这五类同构，照着改参数即可。</p>
 *
 * <p>每份模板都必须能直接编译——校验走 {@link ConfigStore#validate}，
 * 与保存、启动同一条判据。</p>
 */
public final class DeviceTemplates {

    /** 空骨架的模板标识：等价于原来的 ConfigTemplate.skeleton。 */
    public static final String BARE_ID = "bare";

    private DeviceTemplates() {
    }

    /**
     * 目录里的一项。
     *
     * @param id          模板标识（新建配置请求里的 {@code template} 字段）
     * @param label       界面显示名
     * @param description 一句话说明设备形态
     */
    public record Entry(String id, String label, String description) {
    }

    /**
     * 模板目录。
     *
     * @return 全部内置模板（第一项是空骨架）
     */
    public static List<Entry> catalog() {
        return List.of(
                new Entry(BARE_ID, "空骨架", "最小可编译配置：一个腔、一条温度曲线、两个片内事件"),
                new Entry("etch", "刻蚀腔", "温场/射频/腔压三条相位曲线 + 每片的开始与结束上报"),
                new Entry("cvd", "CVD 沉积腔", "温度/气流/膜厚累积 + 膜厚到位事件"),
                new Entry("tester", "测试机（双 SITE）", "两个单元各自号段，跨单元条件触发"),
                new Entry("loadlock", "装载锁室（Loadlock）", "抽真空/破真空分段曲线 + 门阀阶跃"),
                new Entry("cmp", "CMP 抛光机", "抛光压力/转速/去除量 + 终点判定"));
    }

    /** 供错误提示的模板 id 清单，如 {@code bare / etch / cvd / …}。 */
    public static String ids() {
        return String.join(" / ", catalog().stream().map(Entry::id).toList());
    }

    /**
     * 目录的 JSON 数组。
     *
     * @return JSON 文本
     */
    public static String listJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Entry entry : catalog()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"id\":\"").append(escape(entry.id()))
                    .append("\",\"label\":\"").append(escape(entry.label()))
                    .append("\",\"description\":\"").append(escape(entry.description()))
                    .append("\"}");
        }
        return sb.append(']').toString();
    }

    /**
     * 按模板生成一份新配置。
     *
     * @param templateId 模板标识（{@link #catalog()} 里的 id）
     * @param fileName   目标文件名；会话 id 与设备名取它的主名
     * @return Schema v7 JSON 文本；模板不存在时为空
     */
    public static Optional<String> json(String templateId, String fileName) {
        String id = deriveId(fileName);
        return switch (templateId == null ? "" : templateId.trim().toLowerCase(Locale.ROOT)) {
            case "bare", "" -> Optional.of(ConfigTemplate.skeleton(fileName));
            case "etch" -> Optional.of(etch(id));
            case "cvd" -> Optional.of(cvd(id));
            case "tester" -> Optional.of(tester(id));
            case "loadlock" -> Optional.of(loadlock(id));
            case "cmp" -> Optional.of(cmp(id));
            default -> Optional.empty();
        };
    }

    /** 会话 id / 设备名取文件名主名，与空骨架同一套规则。 */
    private static String deriveId(String fileName) {
        String id = fileName == null ? "" : fileName.trim();
        if (id.toLowerCase(Locale.ROOT).endsWith(".json")) {
            id = id.substring(0, id.length() - ".json".length());
        }
        if (id.isEmpty()) {
            id = "line1";
        }
        return id;
    }

    /**
     * 拼一份完整配置：session + context + cycle + equipment 外壳。
     *
     * @param id         会话 id
     * @param name       设备名
     * @param rcp        配方号（RCPID）
     * @param ppid       程序号（PPID）
     * @param durationMs 周期长度
     * @param events     片内事件 JSON 项（逗号分隔）
     * @param units      单元 JSON 数组内容
     * @return 配置文本
     */
    private static String shell(String id, String name, String rcp, String ppid,
                                long durationMs, String events, String units) {
        return """
                {
                  "schemaVersion": 7,
                  "session": {
                    "id": "%s",
                    "passive": true,
                    "bindHost": "127.0.0.1",
                    "port": 5010,
                    "deviceId": 0,
                    "seed": 11
                  },
                  "context": [
                    { "id": "LOTID",   "type": "ASCII", "source": { "mode": "cycle", "pattern": "LOT{02}", "start": 1, "every": 25 } },
                    { "id": "RCPID",   "type": "ASCII", "source": { "mode": "const", "value": "%s" } },
                    { "id": "PPID",    "type": "ASCII", "source": { "mode": "const", "value": "%s" } },
                    { "id": "WAFERID", "type": "ASCII", "source": { "mode": "cycle", "pattern": "W{03}", "start": 1 } },
                    { "id": "BATCHID", "type": "ASCII", "source": { "mode": "cycle", "pattern": "B{03}", "start": 1, "every": 25 } }
                  ],
                  "cycle": {
                    "durationMs": %d,
                    "jitterMs": 3000,
                    "count": 25,
                    "events": [%s]
                  },
                  "equipment": {
                    "name": "%s",
                    "idSpace": {
                      "svidBase": 1000, "ceidBase": 2000, "reportBase": 3000,
                      "svidStride": 100, "ceidStride": 100, "reportStride": 100
                    },
                    "units": [%s]
                  },
                  "events": { "maxPerSec": 20, "s6f11Wbit": true, "s6f1Wbit": true, "plans": [] }
                }
                """.formatted(id, rcp, ppid, durationMs, events, name.toUpperCase(Locale.ROOT), units);
    }

    /** 刻蚀腔：温场三段、射频正弦、腔压抽真空三段。 */
    private static String etch(String id) {
        String unit = """
                {
                  "unitId": 1,
                  "name": "CH1",
                  "injectUnitId": false,
                  "svids": [
                    { "offset": 1, "slot": "TEMP_PV", "type": "F4", "unit": "C", "decimals": 2, "min": 0, "max": 400,
                      "source": { "mode": "curve", "clock": "phase",
                        "curve": { "type": "sequence", "cyclic": false, "segments": [
                          { "durationMs": 60000, "curve": { "type": "ramp", "from": 25, "to": 300, "durationMs": 60000 } },
                          { "durationMs": 90000, "curve": { "type": "sine", "min": 296, "max": 304, "periodMs": 30000 } },
                          { "durationMs": 30000, "curve": { "type": "ramp", "from": 300, "to": 60, "durationMs": 30000 } }] } } },
                    { "offset": 2, "slot": "RF_POWER", "type": "F4", "unit": "W", "decimals": 1, "min": 0, "max": 2000,
                      "source": { "mode": "curve", "clock": "phase",
                        "curve": { "type": "sine", "min": 800, "max": 1200, "periodMs": 60000, "phaseMs": 10000 } } },
                    { "offset": 3, "slot": "PRESS_TORR", "type": "F4", "unit": "Torr", "decimals": 3, "min": 0, "max": 760,
                      "source": { "mode": "curve", "clock": "phase",
                        "curve": { "type": "pwl", "points": [
                          { "tMs": 0, "value": 760 }, { "tMs": 60000, "value": 0.02 },
                          { "tMs": 150000, "value": 0.05 }, { "tMs": 180000, "value": 760 }] } } }
                  ],
                  "reports": [
                    { "offset": 1, "slot": "PROCESS",
                      "svids": ["ctx.LOTID", "ctx.WAFERID", "ctx.RCPID", "TEMP_PV", "RF_POWER", "PRESS_TORR"] }
                  ],
                  "ceids": [
                    { "offset": 1, "slot": "PROCESS_START", "reports": ["PROCESS"] },
                    { "offset": 2, "slot": "PROCESS_END", "reports": ["PROCESS"] }
                  ]
                }
                """;
        String events = """
                { "atMs": 3000, "ceid": 2001 }, { "atMs": 176000, "ceid": 2002 }""";
        return shell(id, id, "ETCH-A1", "PP-ETCH-01", 180000L, events, unit);
    }

    /** CVD 沉积腔：温度、气流、膜厚累积（每片重来）。 */
    private static String cvd(String id) {
        String unit = """
                {
                  "unitId": 1,
                  "name": "CH1",
                  "injectUnitId": false,
                  "svids": [
                    { "offset": 1, "slot": "TEMP_PV", "type": "F4", "unit": "C", "decimals": 2, "min": 0, "max": 500,
                      "source": { "mode": "curve", "clock": "phase",
                        "curve": { "type": "sequence", "cyclic": false, "segments": [
                          { "durationMs": 60000, "curve": { "type": "ramp", "from": 25, "to": 400, "durationMs": 60000 } },
                          { "durationMs": 150000, "curve": { "type": "sine", "min": 396, "max": 404, "periodMs": 60000 } },
                          { "durationMs": 60000, "curve": { "type": "ramp", "from": 400, "to": 80, "durationMs": 60000 } }] } } },
                    { "offset": 2, "slot": "FLOW_SCCM", "type": "F4", "unit": "sccm", "decimals": 1, "min": 0, "max": 500,
                      "source": { "mode": "curve", "clock": "phase",
                        "curve": { "type": "uniform", "min": 180, "max": 220, "resampleMs": 3000 } } },
                    { "offset": 3, "slot": "THICKNESS", "type": "F4", "unit": "A", "decimals": 1, "min": 0, "max": 6000,
                      "source": { "mode": "curve", "clock": "phase",
                        "curve": { "type": "pwl", "points": [
                          { "tMs": 0, "value": 0 }, { "tMs": 60000, "value": 0 },
                          { "tMs": 210000, "value": 5000 }, { "tMs": 270000, "value": 5000 }] } } }
                  ],
                  "reports": [
                    { "offset": 1, "slot": "DEPOSITION",
                      "svids": ["ctx.LOTID", "ctx.WAFERID", "TEMP_PV", "FLOW_SCCM", "THICKNESS"] }
                  ],
                  "ceids": [
                    { "offset": 1, "slot": "DEPO_START", "reports": ["DEPOSITION"] },
                    { "offset": 2, "slot": "DEPO_END", "reports": ["DEPOSITION"] }
                  ]
                }
                """;
        String events = """
                { "atMs": 5000, "ceid": 2001 }, { "atMs": 260000, "ceid": 2002 }""";
        return shell(id, id, "CVD-B2", "PP-CVD-01", 270000L, events, unit);
    }

    /**
     * 双 SITE 测试机：两个单元各占一段号（1000 / 1100）。
     *
     * <p>SITE2 的第三个槽位是表达式来源（{@code mode:"expr"}）——它是"私有曲线 +
     * 一条算式"的写法，不是实例：变量 {@code v} 只在这个数据项里存在。</p>
     */
    private static String tester(String id) {
        String site1 = """
                {
                  "unitId": 1,
                  "name": "SITE1",
                  "injectUnitId": false,
                  "svids": [
                    { "offset": 1, "slot": "VOUT", "type": "F4", "unit": "V", "decimals": 3, "min": 0, "max": 10,
                      "source": { "mode": "curve", "clock": "phase",
                        "curve": { "type": "sine", "min": 4.8, "max": 5.2, "periodMs": 20000 } } },
                    { "offset": 2, "slot": "BIN_FAIL", "type": "U4", "min": 0, "max": 1000,
                      "source": { "mode": "cycle", "start": 0, "step": 1, "every": 1 } }
                  ],
                  "reports": [ { "offset": 1, "slot": "SITE_RESULT", "svids": ["ctx.WAFERID", "VOUT", "BIN_FAIL"] } ],
                  "ceids": [ { "offset": 1, "slot": "SITE1_RESULT", "reports": ["SITE_RESULT"] } ]
                }
                """;
        String site2 = """
                {
                  "unitId": 2,
                  "name": "SITE2",
                  "injectUnitId": false,
                  "svids": [
                    { "offset": 1, "slot": "VOUT", "type": "F4", "unit": "V", "decimals": 3, "min": 0, "max": 10,
                      "source": { "mode": "curve", "clock": "phase",
                        "curve": { "type": "sine", "min": 3.6, "max": 4.0, "periodMs": 20000 } } },
                    { "offset": 2, "slot": "BIN_FAIL", "type": "U4", "min": 0, "max": 1000,
                      "source": { "mode": "cycle", "start": 0, "step": 1, "every": 1 } },
                    { "offset": 3, "slot": "YIELD", "type": "F4", "unit": "%", "decimals": 2, "min": 0, "max": 100,
                      "source": { "mode": "expr", "clock": "phase",
                        "vars": { "v": { "type": "sine", "min": 3.6, "max": 4.0, "periodMs": 20000 } },
                        "expr": "clamp(v * 25, 0, 100)" } }
                  ],
                  "reports": [ { "offset": 1, "slot": "SITE_RESULT", "svids": ["ctx.WAFERID", "VOUT", "BIN_FAIL", "YIELD"] } ],
                  "ceids": [ { "offset": 1, "slot": "SITE2_RESULT", "reports": ["SITE_RESULT"] } ]
                }
                """;
        String events = """
                { "atMs": 2000, "ceid": 2001 }, { "atMs": 2000, "ceid": 2101 }""";
        return shell(id, id, "TEST-T1", "PP-TEST-01", 30000L, events, site1 + ",\n" + site2);
    }

    /** 装载锁室：抽真空 → 稳压 → 破真空，门阀阶跃。 */
    private static String loadlock(String id) {
        String unit = """
                {
                  "unitId": 1,
                  "name": "LL",
                  "injectUnitId": false,
                  "svids": [
                    { "offset": 1, "slot": "PRESS_TORR", "type": "F4", "unit": "Torr", "decimals": 3, "min": 0, "max": 800,
                      "source": { "mode": "curve", "clock": "phase",
                        "curve": { "type": "sequence", "cyclic": false, "segments": [
                          { "durationMs": 30000, "curve": { "type": "ramp", "from": 760, "to": 0.01, "durationMs": 30000 } },
                          { "durationMs": 30000, "curve": { "type": "uniform", "min": 0.008, "max": 0.02, "resampleMs": 1000 } },
                          { "durationMs": 30000, "curve": { "type": "ramp", "from": 0.01, "to": 760, "durationMs": 30000 } }] } } },
                    { "offset": 2, "slot": "GATE_VALVE", "type": "U1", "min": 0, "max": 1,
                      "source": { "mode": "curve", "clock": "phase",
                        "curve": { "type": "step", "steps": [
                          { "fromMs": 0, "value": 0 }, { "fromMs": 30000, "value": 1 },
                          { "fromMs": 60000, "value": 0 }] } } },
                    { "offset": 3, "slot": "SLOT_COUNT", "type": "U4", "min": 0, "max": 25,
                      "source": { "mode": "cycle", "start": 1, "step": 1, "every": 1 } }
                  ],
                  "reports": [
                    { "offset": 1, "slot": "LL_STATUS",
                      "svids": ["ctx.LOTID", "ctx.WAFERID", "PRESS_TORR", "GATE_VALVE", "SLOT_COUNT"] }
                  ],
                  "ceids": [
                    { "offset": 1, "slot": "LL_VACUUM_OK", "reports": ["LL_STATUS"] },
                    { "offset": 2, "slot": "LL_VENTED", "reports": ["LL_STATUS"] }
                  ]
                }
                """;
        String events = """
                { "atMs": 28000, "ceid": 2001 }, { "atMs": 86000, "ceid": 2002 }""";
        return shell(id, id, "LL-R1", "PP-LL-01", 90000L, events, unit);
    }

    /** CMP：下压 → 主抛 → 修整 → 抬起。 */
    private static String cmp(String id) {
        String unit = """
                {
                  "unitId": 1,
                  "name": "PLATEN",
                  "injectUnitId": false,
                  "svids": [
                    { "offset": 1, "slot": "PRESSURE_PSI", "type": "F4", "unit": "psi", "decimals": 3, "min": 0, "max": 8,
                      "source": { "mode": "curve", "clock": "phase",
                        "curve": { "type": "sequence", "cyclic": false, "segments": [
                          { "durationMs": 5000, "curve": { "type": "ramp", "from": 0, "to": 4, "durationMs": 5000 } },
                          { "durationMs": 60000, "curve": { "type": "sine", "min": 3.8, "max": 4.2, "periodMs": 12000 } },
                          { "durationMs": 20000, "curve": { "type": "uniform", "min": 3.5, "max": 4.5, "resampleMs": 3000 } },
                          { "durationMs": 5000, "curve": { "type": "ramp", "from": 4, "to": 0, "durationMs": 5000 } }] } } },
                    { "offset": 2, "slot": "RPM", "type": "U4", "unit": "rpm", "min": 0, "max": 200,
                      "source": { "mode": "curve", "clock": "phase",
                        "curve": { "type": "step", "steps": [
                          { "fromMs": 0, "value": 30 }, { "fromMs": 5000, "value": 93 },
                          { "fromMs": 85000, "value": 0 }] } } },
                    { "offset": 3, "slot": "REMOVAL_A", "type": "F4", "unit": "A", "decimals": 1, "min": 0, "max": 3000,
                      "source": { "mode": "curve", "clock": "phase",
                        "curve": { "type": "ramp", "from": 0, "to": 2400, "durationMs": 90000 } } }
                  ],
                  "reports": [
                    { "offset": 1, "slot": "POLISH",
                      "svids": ["ctx.LOTID", "ctx.WAFERID", "ctx.RCPID", "PRESSURE_PSI", "RPM", "REMOVAL_A"] }
                  ],
                  "ceids": [
                    { "offset": 1, "slot": "POLISH_START", "reports": ["POLISH"] },
                    { "offset": 2, "slot": "POLISH_END", "reports": ["POLISH"] }
                  ]
                }
                """;
        String events = """
                { "atMs": 4000, "ceid": 2001 }, { "atMs": 86000, "ceid": 2002 }""";
        return shell(id, id, "CMP-C1", "PP-CMP-01", 90000L, events, unit);
    }

    /** JSON 字符串转义；目录文案是常量，这里只为稳妥。 */
    private static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
