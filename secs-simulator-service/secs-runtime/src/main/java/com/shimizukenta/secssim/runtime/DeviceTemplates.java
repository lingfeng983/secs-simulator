package com.shimizukenta.secssim.runtime;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 内置设备模板库：新建配置时的选型起点。
 *
 * <p>「空骨架」之外再给几类常见设备的现成配置——刻蚀腔、CVD 沉积腔、测试机。
 * 每份模板都是<b>能直接编译</b>的完整配置（SVID / 报告 / CEID / 事件编排 / 曲线模板），
 * 选型后照着改参数就行，不用从零手搓 ID 段。</p>
 *
 * <p>模板放在后端而不是前端：生成结果要过 {@link ConfigStore#validate} 这道判据，
 * 判据只有一份，「界面上给的模板起不来」这种事就不可能发生。</p>
 *
 * <p>多单元的绝对 ID = 段起点 + (位置-1) × 步长 + 槽位偏移（见
 * {@code IdSpacePolicy}），所以步长 100 时每个单元的偏移必须小于 100。</p>
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
                new Entry(BARE_ID, "空骨架", "最小可编译配置，从零开始搭"),
                new Entry("etch", "刻蚀腔", "温场/射频/腔压曲线 + 过热与腔压异常事件"),
                new Entry("cvd", "CVD 沉积腔", "温度/气流/膜厚累积曲线 + 膜厚到位与过热事件"),
                new Entry("tester", "测试机（双 SITE）", "双单元测试位、BIN 计数与低良率事件"),
                new Entry("furnace", "立式炉管（退火）", "多段炉温程序 + 氮气流量 + 降温完成事件"),
                new Entry("loadlock", "装载锁室（Loadlock）", "抽真空/破真空 pwl 曲线 + 门阀与晶圆到位事件"),
                new Entry("pump", "真空泵组", "泵速/压力游走 + 振动 + 异常停机报警事件"),
                new Entry("pvd", "PVD 镀膜腔", "溅射功率/靶材寿命/膜厚累积 + 靶材耗尽事件"),
                new Entry("cmp", "CMP 抛光机", "抛光压力/转速/去除量 + 终点检测事件"),
                new Entry("clean", "湿法清洗腔", "液位/流量脉冲 + 温度均匀性 + 干燥完成事件"),
                new Entry("metro", "量测站（Metrology）", "测量值波动/重复性 + SPC 超限事件"),
                new Entry("robot", "晶圆传送机械手", "臂位阶跃 + 晶圆计数 + 传送超时事件"));
    }

    /** 供错误提示的模板 id 清单，如 {@code bare / etch / cvd / tester / …}。 */
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
     * @return Schema v5 JSON 文本；模板不存在时为空
     */
    public static Optional<String> json(String templateId, String fileName) {
        String id = deriveId(fileName);
        return switch (templateId == null ? "" : templateId.trim().toLowerCase(Locale.ROOT)) {
            case "bare", "" -> Optional.of(ConfigTemplate.skeleton(fileName));
            case "etch" -> Optional.of(etch(id));
            case "cvd" -> Optional.of(cvd(id));
            case "tester" -> Optional.of(tester(id));
            case "furnace" -> Optional.of(furnace(id));
            case "loadlock" -> Optional.of(loadlock(id));
            case "pump" -> Optional.of(pump(id));
            case "pvd" -> Optional.of(pvd(id));
            case "cmp" -> Optional.of(cmp(id));
            case "clean" -> Optional.of(clean(id));
            case "metro" -> Optional.of(metro(id));
            case "robot" -> Optional.of(robot(id));
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
     * 刻蚀腔：温场/射频/腔压三条曲线，工艺开始结束、过热与腔压异常事件。
     */
    private static String etch(String id) {
        return """
                {
                  "schemaVersion": 5,
                  "session": {
                    "id": "%s",
                    "passive": true,
                    "bindHost": "127.0.0.1",
                    "port": 5010,
                    "deviceId": 0,
                    "seed": 20260101
                  },
                  "equipment": {
                    "name": "%s",
                    "idSpace": {
                      "svidBase": 1000,
                      "ceidBase": 2000,
                      "reportBase": 3000,
                      "svidStride": 100,
                      "ceidStride": 100,
                      "reportStride": 100,
                      "reserve": [[1, 999]]
                    },
                    "constants": [
                      { "id": 501, "name": "ETCH_PRESSURE_LIMIT", "type": "F4", "value": 8.0, "unit": "Torr" },
                      { "id": 502, "name": "STEP_TIMEOUT", "type": "U4", "value": 600, "unit": "s" }
                    ],
                    "units": [
                      {
                        "unitId": 1,
                        "name": "ETCH",
                        "injectUnitId": true,
                        "svids": [
                          { "offset": 1, "slot": "TEMP_PV", "type": "F4", "unit": "C", "decimals": 2, "min": 0, "max": 400 },
                          { "offset": 2, "slot": "TEMP_SV", "type": "F4", "unit": "C", "decimals": 2 },
                          { "offset": 3, "slot": "RF_POWER", "type": "F4", "unit": "W", "decimals": 1, "min": 0, "max": 3000 },
                          { "offset": 4, "slot": "RF_REFL", "type": "F4", "unit": "W", "decimals": 1 },
                          { "offset": 5, "slot": "CHAMBER_PRESS", "type": "F4", "unit": "Torr", "decimals": 3, "min": 0, "max": 10 },
                          { "offset": 6, "slot": "CF4_FLOW", "type": "F4", "unit": "sccm", "decimals": 1 },
                          { "offset": 7, "slot": "STEP_ID", "type": "U2" },
                          { "offset": 90, "slot": "UNIT_ID", "type": "U4" }
                        ],
                        "reports": [
                          { "offset": 1, "slot": "STATUS", "svids": ["UNIT_ID", "TEMP_PV", "CHAMBER_PRESS", "STEP_ID"] },
                          { "offset": 2, "slot": "RF", "svids": ["RF_POWER", "RF_REFL"] }
                        ],
                        "ceids": [
                          { "offset": 1, "slot": "PROCESS_START", "reports": ["STATUS"] },
                          { "offset": 2, "slot": "PROCESS_END", "reports": ["STATUS", "RF"] },
                          { "offset": 3, "slot": "TEMP_OVERHEAT", "reports": ["STATUS"] },
                          { "offset": 4, "slot": "PRESS_ABNORMAL", "reports": ["STATUS"] }
                        ]
                      }
                    ]
                  },
                  "events": {
                    "maxPerSec": 20,
                    "s6f11Wbit": true,
                    "s6f1Wbit": true,
                    "plans": [
                      { "id": "etch-start", "ceid": 2001,
                        "trigger": { "type": "periodic", "periodMs": 8000, "jitterMs": 300 } },
                      { "id": "etch-overheat", "ceid": 2003,
                        "trigger": { "type": "onCondition", "expression": "svid.1001 > 170", "debounceMs": 1500 } },
                      { "id": "etch-press", "ceid": 2004,
                        "trigger": { "type": "onCondition", "expression": "svid.1005 > 3.2", "debounceMs": 1000 } },
                      { "id": "etch-end", "ceid": 2002,
                        "trigger": { "type": "afterEvent", "sourcePlan": "etch-overheat", "delayMs": 2000 } }
                    ]
                  },
                  "templates": {
                    "curves": [
                      { "id": "etch-temp-wave", "type": "sine", "min": 20, "max": 180, "periodMs": 45000, "phaseMs": 0 },
                      { "id": "etch-press-wave", "type": "sine", "min": 0.5, "max": 3.5, "periodMs": 30000, "phaseMs": 0 },
                      { "id": "etch-rf-wave", "type": "sine", "min": 800, "max": 1200, "periodMs": 60000, "phaseMs": 10000 }
                    ],
                    "composites": [
                      { "id": "etch-temp-sv", "outputs": { "SV": "in.etchtemp.V * 0.9 + 10" } },
                      { "id": "etch-rf-deriv", "outputs": { "REFL": "in.etchrfpw.V * 0.03 + 2" } }
                    ],
                    "instances": [
                      { "id": "etchtemp", "template": "etch-temp-wave", "svids": { "V": 1001 } },
                      { "id": "etch-press", "template": "etch-press-wave", "svids": { "V": 1005 } },
                      { "id": "etchrfpw", "template": "etch-rf-wave", "svids": { "V": 1003 } },
                      { "id": "etch-tsv", "template": "etch-temp-sv", "svids": { "SV": 1002 } },
                      { "id": "etch-refl", "template": "etch-rf-deriv", "svids": { "REFL": 1004 } }
                    ]
                  }
                }
                """.formatted(id, id.toUpperCase(Locale.ROOT));
    }

    /**
     * CVD 沉积腔：温度/气压/两路气流/膜厚累积，沉积到位与过热事件。
     */
    private static String cvd(String id) {
        return """
                {
                  "schemaVersion": 5,
                  "session": {
                    "id": "%s",
                    "passive": true,
                    "bindHost": "127.0.0.1",
                    "port": 5010,
                    "deviceId": 0,
                    "seed": 20260102
                  },
                  "equipment": {
                    "name": "%s",
                    "idSpace": {
                      "svidBase": 1000,
                      "ceidBase": 2000,
                      "reportBase": 3000,
                      "svidStride": 100,
                      "ceidStride": 100,
                      "reportStride": 100,
                      "reserve": [[1, 999]]
                    },
                    "constants": [
                      { "id": 501, "name": "TARGET_THICKNESS", "type": "F4", "value": 5000.0, "unit": "A" },
                      { "id": 502, "name": "DEP_PRESSURE_LIMIT", "type": "F4", "value": 3.0, "unit": "mbar" }
                    ],
                    "units": [
                      {
                        "unitId": 1,
                        "name": "DEP",
                        "injectUnitId": true,
                        "svids": [
                          { "offset": 1, "slot": "TEMP_PV", "type": "F4", "unit": "C", "decimals": 2, "min": 0, "max": 500 },
                          { "offset": 2, "slot": "TEMP_SV", "type": "F4", "unit": "C", "decimals": 2 },
                          { "offset": 3, "slot": "PRESS_MBAR", "type": "F4", "unit": "mbar", "decimals": 3, "min": 0, "max": 10 },
                          { "offset": 4, "slot": "N2O_FLOW", "type": "F4", "unit": "sccm", "decimals": 1 },
                          { "offset": 5, "slot": "TEOS_FLOW", "type": "F4", "unit": "sccm", "decimals": 1 },
                          { "offset": 6, "slot": "THICKNESS", "type": "F4", "unit": "A", "decimals": 1, "min": 0 },
                          { "offset": 7, "slot": "STEP_ID", "type": "U2" },
                          { "offset": 90, "slot": "UNIT_ID", "type": "U4" }
                        ],
                        "reports": [
                          { "offset": 1, "slot": "STATUS", "svids": ["UNIT_ID", "TEMP_PV", "PRESS_MBAR", "THICKNESS"] },
                          { "offset": 2, "slot": "GAS", "svids": ["N2O_FLOW", "TEOS_FLOW"] }
                        ],
                        "ceids": [
                          { "offset": 1, "slot": "DEP_START", "reports": ["STATUS"] },
                          { "offset": 2, "slot": "DEP_END", "reports": ["STATUS", "GAS"] },
                          { "offset": 3, "slot": "THICKNESS_REACHED", "reports": ["STATUS"] },
                          { "offset": 4, "slot": "TEMP_OVERHEAT", "reports": ["STATUS"] }
                        ]
                      }
                    ]
                  },
                  "events": {
                    "maxPerSec": 20,
                    "s6f11Wbit": true,
                    "s6f1Wbit": true,
                    "plans": [
                      { "id": "dep-start", "ceid": 2001,
                        "trigger": { "type": "periodic", "periodMs": 15000, "jitterMs": 500 } },
                      { "id": "dep-thick", "ceid": 2003,
                        "trigger": { "type": "onCondition", "expression": "svid.1006 > 4800", "debounceMs": 2000 } },
                      { "id": "dep-hot", "ceid": 2004,
                        "trigger": { "type": "onCondition", "expression": "svid.1001 > 330", "debounceMs": 1500 } },
                      { "id": "dep-end", "ceid": 2002,
                        "trigger": { "type": "afterEvent", "sourcePlan": "dep-thick", "delayMs": 1500 } }
                    ]
                  },
                  "templates": {
                    "curves": [
                      { "id": "dep-temp-wave", "type": "sine", "min": 150, "max": 350, "periodMs": 60000, "phaseMs": 0 },
                      { "id": "dep-press-wave", "type": "uniform", "min": 0.8, "max": 2.5, "resampleMs": 2000 },
                      { "id": "dep-thick-ramp", "type": "ramp", "from": 0, "to": 5000, "durationMs": 120000, "cyclic": true },
                      { "id": "dep-n2o", "type": "uniform", "min": 100, "max": 300, "resampleMs": 3000 },
                      { "id": "dep-teos", "type": "uniform", "min": 20, "max": 80, "resampleMs": 3000 }
                    ],
                    "composites": [
                      { "id": "dep-temp-sv", "outputs": { "SV": "in.deptemp.V * 0.95 + 5" } }
                    ],
                    "instances": [
                      { "id": "deptemp", "template": "dep-temp-wave", "svids": { "V": 1001 } },
                      { "id": "dep-press", "template": "dep-press-wave", "svids": { "V": 1003 } },
                      { "id": "dep-thick", "template": "dep-thick-ramp", "svids": { "V": 1006 } },
                      { "id": "dep-n2o", "template": "dep-n2o", "svids": { "V": 1004 } },
                      { "id": "dep-teos", "template": "dep-teos", "svids": { "V": 1005 } },
                      { "id": "dep-tsv", "template": "dep-temp-sv", "svids": { "SV": 1002 } }
                    ]
                  }
                }
                """.formatted(id, id.toUpperCase(Locale.ROOT));
    }

    /**
     * 测试机（双 SITE）：两个测试单元各自带 BIN 计数与接触电阻，低良率事件。
     *
     * <p>演示多单元号段：SITE1 的 SVID 在 1000 段、CEID 在 2000 段；
     * SITE2 步进一位，SVID 在 1100 段、CEID 在 2100 段。</p>
     */
    private static String tester(String id) {
        return """
                {
                  "schemaVersion": 5,
                  "session": {
                    "id": "%s",
                    "passive": true,
                    "bindHost": "127.0.0.1",
                    "port": 5010,
                    "deviceId": 0,
                    "seed": 20260103
                  },
                  "equipment": {
                    "name": "%s",
                    "idSpace": {
                      "svidBase": 1000,
                      "ceidBase": 2000,
                      "reportBase": 3000,
                      "svidStride": 100,
                      "ceidStride": 100,
                      "reportStride": 100,
                      "reserve": [[1, 999]]
                    },
                    "constants": [
                      { "id": 501, "name": "YIELD_LIMIT", "type": "U2", "value": 85, "unit": "%%" },
                      { "id": 502, "name": "TEST_TIME_LIMIT", "type": "F4", "value": 60.0, "unit": "s" }
                    ],
                    "units": [
                      {
                        "unitId": 1,
                        "name": "SITE1",
                        "injectUnitId": true,
                        "svids": [
                          { "offset": 1, "slot": "SITE_STATUS", "type": "U1" },
                          { "offset": 2, "slot": "BIN_OK", "type": "U4", "min": 0 },
                          { "offset": 3, "slot": "BIN_FAIL", "type": "U4", "min": 0 },
                          { "offset": 4, "slot": "TEST_TIME", "type": "F4", "unit": "s", "decimals": 1 },
                          { "offset": 5, "slot": "CONTACT_R", "type": "F4", "unit": "ohm", "decimals": 2, "min": 0 },
                          { "offset": 90, "slot": "UNIT_ID", "type": "U4" }
                        ],
                        "reports": [
                          { "offset": 1, "slot": "STATUS", "svids": ["UNIT_ID", "SITE_STATUS", "BIN_OK", "BIN_FAIL"] }
                        ],
                        "ceids": [
                          { "offset": 1, "slot": "TEST_START", "reports": ["STATUS"] },
                          { "offset": 2, "slot": "TEST_END", "reports": ["STATUS"] },
                          { "offset": 3, "slot": "LOW_YIELD", "reports": ["STATUS"] }
                        ]
                      },
                      {
                        "unitId": 2,
                        "name": "SITE2",
                        "injectUnitId": true,
                        "svids": [
                          { "offset": 1, "slot": "SITE_STATUS", "type": "U1" },
                          { "offset": 2, "slot": "BIN_OK", "type": "U4", "min": 0 },
                          { "offset": 3, "slot": "BIN_FAIL", "type": "U4", "min": 0 },
                          { "offset": 4, "slot": "TEST_TIME", "type": "F4", "unit": "s", "decimals": 1 },
                          { "offset": 5, "slot": "CONTACT_R", "type": "F4", "unit": "ohm", "decimals": 2, "min": 0 },
                          { "offset": 90, "slot": "UNIT_ID", "type": "U4" }
                        ],
                        "reports": [
                          { "offset": 1, "slot": "STATUS", "svids": ["UNIT_ID", "SITE_STATUS", "BIN_OK", "BIN_FAIL"] }
                        ],
                        "ceids": [
                          { "offset": 1, "slot": "TEST_START", "reports": ["STATUS"] },
                          { "offset": 2, "slot": "TEST_END", "reports": ["STATUS"] },
                          { "offset": 3, "slot": "LOW_YIELD", "reports": ["STATUS"] }
                        ]
                      }
                    ]
                  },
                  "events": {
                    "maxPerSec": 20,
                    "s6f11Wbit": true,
                    "s6f1Wbit": true,
                    "plans": [
                      { "id": "t1-start", "ceid": 2001,
                        "trigger": { "type": "periodic", "periodMs": 10000, "jitterMs": 500 } },
                      { "id": "t1-end", "ceid": 2002,
                        "trigger": { "type": "afterEvent", "sourcePlan": "t1-start", "delayMs": 6000 } },
                      { "id": "t1-yield", "ceid": 2003,
                        "trigger": { "type": "onCondition", "expression": "svid.1003 > 12", "debounceMs": 3000 } },
                      { "id": "t2-start", "ceid": 2101,
                        "trigger": { "type": "periodic", "periodMs": 12000, "jitterMs": 500 } },
                      { "id": "t2-end", "ceid": 2102,
                        "trigger": { "type": "afterEvent", "sourcePlan": "t2-start", "delayMs": 6000 } },
                      { "id": "t2-yield", "ceid": 2103,
                        "trigger": { "type": "onCondition", "expression": "svid.1103 > 12", "debounceMs": 3000 } }
                    ]
                  },
                  "templates": {
                    "curves": [
                      { "id": "t1-contact", "type": "uniform", "min": 0.5, "max": 3.0, "resampleMs": 2000 },
                      { "id": "t2-contact", "type": "uniform", "min": 0.5, "max": 3.5, "resampleMs": 2000 },
                      { "id": "t1-binok", "type": "ramp", "from": 0, "to": 50, "durationMs": 60000, "cyclic": true },
                      { "id": "t1-binfail", "type": "uniform", "min": 0, "max": 6, "resampleMs": 5000 },
                      { "id": "t2-binok", "type": "ramp", "from": 0, "to": 48, "durationMs": 65000, "cyclic": true },
                      { "id": "t2-binfail", "type": "uniform", "min": 0, "max": 5, "resampleMs": 5000 }
                    ],
                    "composites": [],
                    "instances": [
                      { "id": "t1-r", "template": "t1-contact", "svids": { "V": 1005 } },
                      { "id": "t1-ok", "template": "t1-binok", "svids": { "V": 1002 } },
                      { "id": "t1-fail", "template": "t1-binfail", "svids": { "V": 1003 } },
                      { "id": "t2-r", "template": "t2-contact", "svids": { "V": 1105 } },
                      { "id": "t2-ok", "template": "t2-binok", "svids": { "V": 1102 } },
                      { "id": "t2-fail", "template": "t2-binfail", "svids": { "V": 1103 } }
                    ]
                  }
                }
                """.formatted(id, id.toUpperCase(Locale.ROOT));
    }

    /**
     * 立式炉管（退火）：三段炉温程序（pwl）、氮气流量、压力缓变 + 降温完成事件。
     */
    private static String furnace(String id) {
        return """
                {
                  "schemaVersion": 5,
                  "session": {
                    "id": "%s", "passive": true, "bindHost": "127.0.0.1",
                    "port": 5010, "deviceId": 0, "seed": 20260104
                  },
                  "equipment": {
                    "name": "%s",
                    "idSpace": { "svidBase": 1000, "ceidBase": 2000, "reportBase": 3000,
                                 "svidStride": 100, "ceidStride": 100, "reportStride": 100, "reserve": [[1, 999]] },
                    "constants": [
                      { "id": 501, "name": "FURNACE_TEMP_MAX", "type": "F4", "value": 1100.0, "unit": "C" },
                      { "id": 502, "name": "SOAK_TIME", "type": "U4", "value": 1800, "unit": "s" }
                    ],
                    "units": [
                      {
                        "unitId": 1, "name": "FUR", "injectUnitId": true,
                        "svids": [
                          { "offset": 1, "slot": "TEMP_PV", "type": "F4", "unit": "C", "decimals": 2, "min": 0, "max": 1200 },
                          { "offset": 2, "slot": "TEMP_SV", "type": "F4", "unit": "C", "decimals": 2 },
                          { "offset": 3, "slot": "N2_FLOW", "type": "F4", "unit": "sccm", "decimals": 1 },
                          { "offset": 4, "slot": "PRESS_MBAR", "type": "F4", "unit": "mbar", "decimals": 3, "min": 0, "max": 5 },
                          { "offset": 5, "slot": "STEP_ID", "type": "U2" },
                          { "offset": 90, "slot": "UNIT_ID", "type": "U4" }
                        ],
                        "reports": [
                          { "offset": 1, "slot": "STATUS", "svids": ["UNIT_ID", "TEMP_PV", "PRESS_MBAR", "STEP_ID"] },
                          { "offset": 2, "slot": "GAS", "svids": ["N2_FLOW"] }
                        ],
                        "ceids": [
                          { "offset": 1, "slot": "RUN_START", "reports": ["STATUS"] },
                          { "offset": 2, "slot": "RUN_END", "reports": ["STATUS", "GAS"] },
                          { "offset": 3, "slot": "TEMP_OVERSHOOT", "reports": ["STATUS"] }
                        ]
                      }
                    ]
                  },
                  "events": {
                    "maxPerSec": 20, "s6f11Wbit": true, "s6f1Wbit": true,
                    "plans": [
                      { "id": "fur-status", "ceid": 2001,
                        "trigger": { "type": "periodic", "periodMs": 10000, "jitterMs": 400 } },
                      { "id": "fur-overshoot", "ceid": 2003,
                        "trigger": { "type": "onCondition", "expression": "svid.1001 > 1000", "debounceMs": 2000 } },
                      { "id": "fur-end", "ceid": 2002,
                        "trigger": { "type": "afterEvent", "sourcePlan": "fur-status", "delayMs": 30000 } }
                    ]
                  },
                  "templates": {
                    "curves": [
                      { "id": "fur-temp-pwl", "type": "pwl",
                        "points": [ { "tMs": 0, "value": 25 }, { "tMs": 600000, "value": 950 },
                                    { "tMs": 1500000, "value": 950 }, { "tMs": 2400000, "value": 60 } ] },
                      { "id": "fur-n2", "type": "uniform", "min": 800, "max": 1200, "resampleMs": 4000 },
                      { "id": "fur-press", "type": "randomWalk",
                        "start": 0.6, "driftPerSec": 0, "sigma": 0.02, "gridMs": 2000,
                        "octaves": 1, "min": 0.4, "max": 1.2, "policy": "CLAMP" }
                    ],
                    "composites": [
                      { "id": "fur-tsv", "outputs": { "SV": "in.furtemp.V * 0.98 + 2" } }
                    ],
                    "instances": [
                      { "id": "furtemp", "template": "fur-temp-pwl", "svids": { "V": 1001 } },
                      { "id": "fur-tsv", "template": "fur-tsv", "svids": { "SV": 1002 } },
                      { "id": "fur-n2", "template": "fur-n2", "svids": { "V": 1003 } },
                      { "id": "fur-press", "template": "fur-press", "svids": { "V": 1004 } }
                    ]
                  }
                }
                """.formatted(id, id.toUpperCase(Locale.ROOT));
    }

    /**
     * 装载锁室（Loadlock）：抽真空/破真空 pwl、门阀状态阶跃、晶圆到位事件。
     */
    private static String loadlock(String id) {
        return """
                {
                  "schemaVersion": 5,
                  "session": {
                    "id": "%s", "passive": true, "bindHost": "127.0.0.1",
                    "port": 5010, "deviceId": 0, "seed": 20260105
                  },
                  "equipment": {
                    "name": "%s",
                    "idSpace": { "svidBase": 1000, "ceidBase": 2000, "reportBase": 3000,
                                 "svidStride": 100, "ceidStride": 100, "reportStride": 100, "reserve": [[1, 999]] },
                    "constants": [
                      { "id": 501, "name": "LL_VAC_TARGET", "type": "F4", "value": 0.01, "unit": "Torr" },
                      { "id": 502, "name": "LL_VENT_TARGET", "type": "F4", "value": 760.0, "unit": "Torr" }
                    ],
                    "units": [
                      {
                        "unitId": 1, "name": "LL", "injectUnitId": true,
                        "svids": [
                          { "offset": 1, "slot": "PRESS_TORR", "type": "F4", "unit": "Torr", "decimals": 3, "min": 0 },
                          { "offset": 2, "slot": "GATE_VALVE", "type": "U1" },
                          { "offset": 3, "slot": "SLOT_COUNT", "type": "U4", "min": 0 },
                          { "offset": 4, "slot": "WAFER_PRESENT", "type": "U1" },
                          { "offset": 90, "slot": "UNIT_ID", "type": "U4" }
                        ],
                        "reports": [
                          { "offset": 1, "slot": "STATUS", "svids": ["UNIT_ID", "PRESS_TORR", "GATE_VALVE", "WAFER_PRESENT"] }
                        ],
                        "ceids": [
                          { "offset": 1, "slot": "LL_VACUUM_OK", "reports": ["STATUS"] },
                          { "offset": 2, "slot": "LL_VENTED", "reports": ["STATUS"] },
                          { "offset": 3, "slot": "WAFER_LOADED", "reports": ["STATUS"] }
                        ]
                      }
                    ]
                  },
                  "events": {
                    "maxPerSec": 20, "s6f11Wbit": true, "s6f1Wbit": true,
                    "plans": [
                      { "id": "ll-status", "ceid": 2001,
                        "trigger": { "type": "periodic", "periodMs": 8000, "jitterMs": 300 } },
                      { "id": "ll-vac", "ceid": 2003,
                        "trigger": { "type": "onCondition", "expression": "svid.1001 < 0.05", "debounceMs": 3000 } },
                      { "id": "ll-load", "ceid": 2003,
                        "trigger": { "type": "afterEvent", "sourcePlan": "ll-vac", "delayMs": 8000 } }
                    ]
                  },
                  "templates": {
                    "curves": [
                      { "id": "ll-press-cycle", "type": "pwl",
                        "points": [ { "tMs": 0, "value": 760 }, { "tMs": 120000, "value": 0.01 },
                                    { "tMs": 300000, "value": 0.01 }, { "tMs": 360000, "value": 760 } ] },
                      { "id": "ll-gate", "type": "step",
                        "steps": [ { "fromMs": 0, "value": 0 }, { "fromMs": 180000, "value": 1 } ] },
                      { "id": "ll-slotcount", "type": "ramp", "from": 0, "to": 25, "durationMs": 600000, "cyclic": true },
                      { "id": "ll-wafer", "type": "step",
                        "steps": [ { "fromMs": 0, "value": 0 }, { "fromMs": 150000, "value": 1 },
                                   { "fromMs": 300000, "value": 0 } ] }
                    ],
                    "composites": [],
                    "instances": [
                      { "id": "ll-press", "template": "ll-press-cycle", "svids": { "V": 1001 } },
                      { "id": "ll-gate", "template": "ll-gate", "svids": { "V": 1002 } },
                      { "id": "ll-count", "template": "ll-slotcount", "svids": { "V": 1003 } },
                      { "id": "ll-wfr", "template": "ll-wafer", "svids": { "V": 1004 } }
                    ]
                  }
                }
                """.formatted(id, id.toUpperCase(Locale.ROOT));
    }

    /**
     * 真空泵组：泵速/压力游走、振动与温度 + 异常停机报警事件。
     */
    private static String pump(String id) {
        return """
                {
                  "schemaVersion": 5,
                  "session": {
                    "id": "%s", "passive": true, "bindHost": "127.0.0.1",
                    "port": 5010, "deviceId": 0, "seed": 20260106
                  },
                  "equipment": {
                    "name": "%s",
                    "idSpace": { "svidBase": 1000, "ceidBase": 2000, "reportBase": 3000,
                                 "svidStride": 100, "ceidStride": 100, "reportStride": 100, "reserve": [[1, 999]] },
                    "constants": [
                      { "id": 501, "name": "PUMP_SPEED_NOMINAL", "type": "F4", "value": 27000.0, "unit": "rpm" }
                    ],
                    "units": [
                      {
                        "unitId": 1, "name": "PUMP", "injectUnitId": true,
                        "svids": [
                          { "offset": 1, "slot": "PUMP_SPEED", "type": "F4", "unit": "rpm", "decimals": 0, "min": 0, "max": 30000 },
                          { "offset": 2, "slot": "EXHAUST_PRESS", "type": "F4", "unit": "Torr", "decimals": 3 },
                          { "offset": 3, "slot": "PUMP_TEMP", "type": "F4", "unit": "C", "decimals": 1 },
                          { "offset": 4, "slot": "VIBRATION", "type": "F4", "unit": "mm/s", "decimals": 2 },
                          { "offset": 5, "slot": "RUN_HOURS", "type": "F4", "unit": "h", "decimals": 1, "min": 0 },
                          { "offset": 90, "slot": "UNIT_ID", "type": "U4" }
                        ],
                        "reports": [
                          { "offset": 1, "slot": "STATUS", "svids": ["UNIT_ID", "PUMP_SPEED", "EXHAUST_PRESS", "PUMP_TEMP"] }
                        ],
                        "ceids": [
                          { "offset": 1, "slot": "PUMP_READY", "reports": ["STATUS"] },
                          { "offset": 2, "slot": "PUMP_FAULT", "reports": ["STATUS"] },
                          { "offset": 3, "slot": "MAINT_DUE", "reports": ["STATUS"] }
                        ]
                      }
                    ]
                  },
                  "events": {
                    "maxPerSec": 20, "s6f11Wbit": true, "s6f1Wbit": true,
                    "plans": [
                      { "id": "pump-status", "ceid": 2001,
                        "trigger": { "type": "periodic", "periodMs": 15000, "jitterMs": 500 } },
                      { "id": "pump-fault", "ceid": 2002,
                        "trigger": { "type": "onCondition", "expression": "svid.1004 > 4.5", "debounceMs": 3000 } },
                      { "id": "pump-maint", "ceid": 2003,
                        "trigger": { "type": "onCondition", "expression": "svid.1005 > 5000", "debounceMs": 60000 } }
                    ]
                  },
                  "templates": {
                    "curves": [
                      { "id": "pump-speed", "type": "randomWalk",
                        "start": 27000, "driftPerSec": 0, "sigma": 40, "gridMs": 2000,
                        "octaves": 1, "min": 26000, "max": 28000, "policy": "CLAMP" },
                      { "id": "pump-press", "type": "randomWalk",
                        "start": 0.02, "driftPerSec": 0, "sigma": 0.005, "gridMs": 2000,
                        "octaves": 1, "min": 0.005, "max": 0.08, "policy": "REFLECT" },
                      { "id": "pump-temp", "type": "sine", "min": 45, "max": 62, "periodMs": 180000, "phaseMs": 0 },
                      { "id": "pump-vib", "type": "uniform", "min": 0.8, "max": 2.2, "resampleMs": 1500 },
                      { "id": "pump-hours", "type": "ramp", "from": 0, "to": 8000, "durationMs": 3600000, "cyclic": false }
                    ],
                    "composites": [],
                    "instances": [
                      { "id": "pump-spd", "template": "pump-speed", "svids": { "V": 1001 } },
                      { "id": "pump-prs", "template": "pump-press", "svids": { "V": 1002 } },
                      { "id": "pump-tmp", "template": "pump-temp", "svids": { "V": 1003 } },
                      { "id": "pump-vib", "template": "pump-vib", "svids": { "V": 1004 } },
                      { "id": "pump-hrs", "template": "pump-hours", "svids": { "V": 1005 } }
                    ]
                  }
                }
                """.formatted(id, id.toUpperCase(Locale.ROOT));
    }

    /**
     * PVD 镀膜腔：溅射功率、氩气流量、靶材寿命（衰减）、膜厚累积 + 靶材耗尽事件。
     */
    private static String pvd(String id) {
        return """
                {
                  "schemaVersion": 5,
                  "session": {
                    "id": "%s", "passive": true, "bindHost": "127.0.0.1",
                    "port": 5010, "deviceId": 0, "seed": 20260107
                  },
                  "equipment": {
                    "name": "%s",
                    "idSpace": { "svidBase": 1000, "ceidBase": 2000, "reportBase": 3000,
                                 "svidStride": 100, "ceidStride": 100, "reportStride": 100, "reserve": [[1, 999]] },
                    "constants": [
                      { "id": 501, "name": "TARGET_LIFE_LIMIT", "type": "F4", "value": 2000.0, "unit": "kWh" },
                      { "id": 502, "name": "FILM_TARGET", "type": "F4", "value": 3000.0, "unit": "A" }
                    ],
                    "units": [
                      {
                        "unitId": 1, "name": "PVD", "injectUnitId": true,
                        "svids": [
                          { "offset": 1, "slot": "DC_POWER", "type": "F4", "unit": "kW", "decimals": 2 },
                          { "offset": 2, "slot": "AR_FLOW", "type": "F4", "unit": "sccm", "decimals": 1 },
                          { "offset": 3, "slot": "CHAMBER_PRESS", "type": "F4", "unit": "mTorr", "decimals": 2 },
                          { "offset": 4, "slot": "TARGET_LIFE", "type": "F4", "unit": "kWh", "decimals": 1, "min": 0 },
                          { "offset": 5, "slot": "FILM_THICK", "type": "F4", "unit": "A", "decimals": 1, "min": 0 },
                          { "offset": 90, "slot": "UNIT_ID", "type": "U4" }
                        ],
                        "reports": [
                          { "offset": 1, "slot": "STATUS", "svids": ["UNIT_ID", "DC_POWER", "CHAMBER_PRESS", "FILM_THICK"] }
                        ],
                        "ceids": [
                          { "offset": 1, "slot": "DEP_START", "reports": ["STATUS"] },
                          { "offset": 2, "slot": "DEP_END", "reports": ["STATUS"] },
                          { "offset": 3, "slot": "TARGET_EXHAUSTED", "reports": ["STATUS"] },
                          { "offset": 4, "slot": "FILM_REACHED", "reports": ["STATUS"] }
                        ]
                      }
                    ]
                  },
                  "events": {
                    "maxPerSec": 20, "s6f11Wbit": true, "s6f1Wbit": true,
                    "plans": [
                      { "id": "pvd-status", "ceid": 2001,
                        "trigger": { "type": "periodic", "periodMs": 10000, "jitterMs": 400 } },
                      { "id": "pvd-target", "ceid": 2003,
                        "trigger": { "type": "onCondition", "expression": "svid.1004 > 1990", "debounceMs": 3000 } },
                      { "id": "pvd-film", "ceid": 2004,
                        "trigger": { "type": "onCondition", "expression": "svid.1005 > 2900", "debounceMs": 2000 } }
                    ]
                  },
                  "templates": {
                    "curves": [
                      { "id": "pvd-power", "type": "sine", "min": 9.5, "max": 10.5, "periodMs": 30000, "phaseMs": 0 },
                      { "id": "pvd-ar", "type": "uniform", "min": 40, "max": 60, "resampleMs": 2500 },
                      { "id": "pvd-press", "type": "randomWalk",
                        "start": 3.0, "driftPerSec": 0, "sigma": 0.15, "gridMs": 2000,
                        "octaves": 1, "min": 2.0, "max": 5.0, "policy": "CLAMP" },
                      { "id": "pvd-life", "type": "ramp", "from": 0, "to": 2000, "durationMs": 1800000, "cyclic": true },
                      { "id": "pvd-film", "type": "ramp", "from": 0, "to": 3000, "durationMs": 300000, "cyclic": true }
                    ],
                    "composites": [],
                    "instances": [
                      { "id": "pvd-pwr", "template": "pvd-power", "svids": { "V": 1001 } },
                      { "id": "pvd-ar", "template": "pvd-ar", "svids": { "V": 1002 } },
                      { "id": "pvd-prs", "template": "pvd-press", "svids": { "V": 1003 } },
                      { "id": "pvd-life", "template": "pvd-life", "svids": { "V": 1004 } },
                      { "id": "pvd-film", "template": "pvd-film", "svids": { "V": 1005 } }
                    ]
                  }
                }
                """.formatted(id, id.toUpperCase(Locale.ROOT));
    }

    /**
     * CMP 抛光机：抛光压力/转速、去除量累积 + 终点检测事件。
     */
    private static String cmp(String id) {
        return """
                {
                  "schemaVersion": 5,
                  "session": {
                    "id": "%s", "passive": true, "bindHost": "127.0.0.1",
                    "port": 5010, "deviceId": 0, "seed": 20260108
                  },
                  "equipment": {
                    "name": "%s",
                    "idSpace": { "svidBase": 1000, "ceidBase": 2000, "reportBase": 3000,
                                 "svidStride": 100, "ceidStride": 100, "reportStride": 100, "reserve": [[1, 999]] },
                    "constants": [
                      { "id": 501, "name": "REMOVE_TARGET", "type": "F4", "value": 1500.0, "unit": "A" },
                      { "id": 502, "name": "PLATEN_SPEED_MAX", "type": "F4", "value": 120.0, "unit": "rpm" }
                    ],
                    "units": [
                      {
                        "unitId": 1, "name": "CMP", "injectUnitId": true,
                        "svids": [
                          { "offset": 1, "slot": "POLISH_PRESS", "type": "F4", "unit": "psi", "decimals": 2 },
                          { "offset": 2, "slot": "PLATEN_RPM", "type": "F4", "unit": "rpm", "decimals": 1 },
                          { "offset": 3, "slot": "HEAD_RPM", "type": "F4", "unit": "rpm", "decimals": 1 },
                          { "offset": 4, "slot": "SLURRY_FLOW", "type": "F4", "unit": "ml/min", "decimals": 1 },
                          { "offset": 5, "slot": "REMOVED_A", "type": "F4", "unit": "A", "decimals": 1, "min": 0 },
                          { "offset": 90, "slot": "UNIT_ID", "type": "U4" }
                        ],
                        "reports": [
                          { "offset": 1, "slot": "STATUS", "svids": ["UNIT_ID", "POLISH_PRESS", "PLATEN_RPM", "REMOVED_A"] }
                        ],
                        "ceids": [
                          { "offset": 1, "slot": "POLISH_START", "reports": ["STATUS"] },
                          { "offset": 2, "slot": "POLISH_END", "reports": ["STATUS"] },
                          { "offset": 3, "slot": "ENDPOINT_DETECTED", "reports": ["STATUS"] }
                        ]
                      }
                    ]
                  },
                  "events": {
                    "maxPerSec": 20, "s6f11Wbit": true, "s6f1Wbit": true,
                    "plans": [
                      { "id": "cmp-status", "ceid": 2001,
                        "trigger": { "type": "periodic", "periodMs": 8000, "jitterMs": 300 } },
                      { "id": "cmp-endpoint", "ceid": 2003,
                        "trigger": { "type": "onCondition", "expression": "svid.1005 > 1400", "debounceMs": 2000 } }
                    ]
                  },
                  "templates": {
                    "curves": [
                      { "id": "cmp-press", "type": "randomWalk",
                        "start": 2.5, "driftPerSec": 0, "sigma": 0.08, "gridMs": 2000,
                        "octaves": 1, "min": 2.0, "max": 3.5, "policy": "CLAMP" },
                      { "id": "cmp-platen", "type": "sine", "min": 80, "max": 110, "periodMs": 45000, "phaseMs": 0 },
                      { "id": "cmp-head", "type": "sine", "min": 40, "max": 70, "periodMs": 30000, "phaseMs": 5000 },
                      { "id": "cmp-slurry", "type": "uniform", "min": 180, "max": 220, "resampleMs": 3000 },
                      { "id": "cmp-remove", "type": "ramp", "from": 0, "to": 1500, "durationMs": 240000, "cyclic": true }
                    ],
                    "composites": [],
                    "instances": [
                      { "id": "cmp-prs", "template": "cmp-press", "svids": { "V": 1001 } },
                      { "id": "cmp-pln", "template": "cmp-platen", "svids": { "V": 1002 } },
                      { "id": "cmp-hd", "template": "cmp-head", "svids": { "V": 1003 } },
                      { "id": "cmp-slr", "template": "cmp-slurry", "svids": { "V": 1004 } },
                      { "id": "cmp-rmv", "template": "cmp-remove", "svids": { "V": 1005 } }
                    ]
                  }
                }
                """.formatted(id, id.toUpperCase(Locale.ROOT));
    }

    /**
     * 湿法清洗腔：药液液位/流量脉冲、温度均匀性 + 干燥完成事件。
     */
    private static String clean(String id) {
        return """
                {
                  "schemaVersion": 5,
                  "session": {
                    "id": "%s", "passive": true, "bindHost": "127.0.0.1",
                    "port": 5010, "deviceId": 0, "seed": 20260109
                  },
                  "equipment": {
                    "name": "%s",
                    "idSpace": { "svidBase": 1000, "ceidBase": 2000, "reportBase": 3000,
                                 "svidStride": 100, "ceidStride": 100, "reportStride": 100, "reserve": [[1, 999]] },
                    "constants": [
                      { "id": 501, "name": "BATH_TEMP_TARGET", "type": "F4", "value": 65.0, "unit": "C" },
                      { "id": 502, "name": "RINSE_TIME", "type": "U4", "value": 300, "unit": "s" }
                    ],
                    "units": [
                      {
                        "unitId": 1, "name": "CLN", "injectUnitId": true,
                        "svids": [
                          { "offset": 1, "slot": "BATH_TEMP", "type": "F4", "unit": "C", "decimals": 2 },
                          { "offset": 2, "slot": "BATH_LEVEL", "type": "F4", "unit": "%%", "decimals": 1, "min": 0, "max": 100 },
                          { "offset": 3, "slot": "RINSE_FLOW", "type": "F4", "unit": "L/min", "decimals": 1 },
                          { "offset": 4, "slot": "DRY_N2_TEMP", "type": "F4", "unit": "C", "decimals": 1 },
                          { "offset": 5, "slot": "STEP_ID", "type": "U2" },
                          { "offset": 90, "slot": "UNIT_ID", "type": "U4" }
                        ],
                        "reports": [
                          { "offset": 1, "slot": "STATUS", "svids": ["UNIT_ID", "BATH_TEMP", "BATH_LEVEL", "STEP_ID"] }
                        ],
                        "ceids": [
                          { "offset": 1, "slot": "CLEAN_START", "reports": ["STATUS"] },
                          { "offset": 2, "slot": "CLEAN_END", "reports": ["STATUS"] },
                          { "offset": 3, "slot": "DRY_DONE", "reports": ["STATUS"] }
                        ]
                      }
                    ]
                  },
                  "events": {
                    "maxPerSec": 20, "s6f11Wbit": true, "s6f1Wbit": true,
                    "plans": [
                      { "id": "cln-status", "ceid": 2001,
                        "trigger": { "type": "periodic", "periodMs": 9000, "jitterMs": 300 } },
                      { "id": "cln-done", "ceid": 2003,
                        "trigger": { "type": "afterEvent", "sourcePlan": "cln-status", "delayMs": 27000 } }
                    ]
                  },
                  "templates": {
                    "curves": [
                      { "id": "cln-temp", "type": "randomWalk",
                        "start": 65, "driftPerSec": 0, "sigma": 0.4, "gridMs": 2000,
                        "octaves": 1, "min": 63, "max": 67, "policy": "CLAMP" },
                      { "id": "cln-level", "type": "sine", "min": 78, "max": 92, "periodMs": 50000, "phaseMs": 0 },
                      { "id": "cln-rinse", "type": "step",
                        "steps": [ { "fromMs": 0, "value": 0 }, { "fromMs": 30000, "value": 12 } ] },
                      { "id": "cln-dry", "type": "sine", "min": 80, "max": 120, "periodMs": 40000, "phaseMs": 8000 }
                    ],
                    "composites": [],
                    "instances": [
                      { "id": "cln-tmp", "template": "cln-temp", "svids": { "V": 1001 } },
                      { "id": "cln-lvl", "template": "cln-level", "svids": { "V": 1002 } },
                      { "id": "cln-rns", "template": "cln-rinse", "svids": { "V": 1003 } },
                      { "id": "cln-dry", "template": "cln-dry", "svids": { "V": 1004 } }
                    ]
                  }
                }
                """.formatted(id, id.toUpperCase(Locale.ROOT));
    }

    /**
     * 量测站（Metrology）：膜厚/CD 测量值波动、重复性 + SPC 超限事件。
     */
    private static String metro(String id) {
        return """
                {
                  "schemaVersion": 5,
                  "session": {
                    "id": "%s", "passive": true, "bindHost": "127.0.0.1",
                    "port": 5010, "deviceId": 0, "seed": 20260110
                  },
                  "equipment": {
                    "name": "%s",
                    "idSpace": { "svidBase": 1000, "ceidBase": 2000, "reportBase": 3000,
                                 "svidStride": 100, "ceidStride": 100, "reportStride": 100, "reserve": [[1, 999]] },
                    "constants": [
                      { "id": 501, "name": "SPC_THICK_UCL", "type": "F4", "value": 520.0, "unit": "A" },
                      { "id": 502, "name": "SPC_THICK_LCL", "type": "F4", "value": 480.0, "unit": "A" }
                    ],
                    "units": [
                      {
                        "unitId": 1, "name": "MET", "injectUnitId": true,
                        "svids": [
                          { "offset": 1, "slot": "FILM_THICK", "type": "F4", "unit": "A", "decimals": 2, "min": 0 },
                          { "offset": 2, "slot": "CD_MEAN", "type": "F4", "unit": "nm", "decimals": 3 },
                          { "offset": 3, "slot": "CD_SIGMA", "type": "F4", "unit": "nm", "decimals": 3, "min": 0 },
                          { "offset": 4, "slot": "MEAS_COUNT", "type": "U4", "min": 0 },
                          { "offset": 90, "slot": "UNIT_ID", "type": "U4" }
                        ],
                        "reports": [
                          { "offset": 1, "slot": "STATUS", "svids": ["UNIT_ID", "FILM_THICK", "CD_MEAN", "MEAS_COUNT"] }
                        ],
                        "ceids": [
                          { "offset": 1, "slot": "MEAS_DONE", "reports": ["STATUS"] },
                          { "offset": 2, "slot": "SPC_VIOLATION", "reports": ["STATUS"] }
                        ]
                      }
                    ]
                  },
                  "events": {
                    "maxPerSec": 20, "s6f11Wbit": true, "s6f1Wbit": true,
                    "plans": [
                      { "id": "met-done", "ceid": 2001,
                        "trigger": { "type": "periodic", "periodMs": 12000, "jitterMs": 600 } },
                      { "id": "met-spc", "ceid": 2002,
                        "trigger": { "type": "onCondition", "expression": "svid.1001 > 520", "debounceMs": 5000 } }
                    ]
                  },
                  "templates": {
                    "curves": [
                      { "id": "met-thick", "type": "randomWalk",
                        "start": 500, "driftPerSec": 0.3, "sigma": 3, "gridMs": 3000,
                        "octaves": 1, "min": 470, "max": 530, "policy": "REFLECT" },
                      { "id": "met-cd", "type": "randomWalk",
                        "start": 45, "driftPerSec": 0, "sigma": 0.2, "gridMs": 3000,
                        "octaves": 1, "min": 44, "max": 46, "policy": "REFLECT" },
                      { "id": "met-sigma", "type": "uniform", "min": 0.8, "max": 1.6, "resampleMs": 4000 },
                      { "id": "met-count", "type": "ramp", "from": 0, "to": 500, "durationMs": 600000, "cyclic": true }
                    ],
                    "composites": [],
                    "instances": [
                      { "id": "met-thk", "template": "met-thick", "svids": { "V": 1001 } },
                      { "id": "met-cd", "template": "met-cd", "svids": { "V": 1002 } },
                      { "id": "met-sig", "template": "met-sigma", "svids": { "V": 1003 } },
                      { "id": "met-cnt", "template": "met-count", "svids": { "V": 1004 } }
                    ]
                  }
                }
                """.formatted(id, id.toUpperCase(Locale.ROOT));
    }

    /**
     * 晶圆传送机械手（Robot）：臂位阶跃、晶圆计数循环 + 传送超时事件。
     */
    private static String robot(String id) {
        return """
                {
                  "schemaVersion": 5,
                  "session": {
                    "id": "%s", "passive": true, "bindHost": "127.0.0.1",
                    "port": 5010, "deviceId": 0, "seed": 20260111
                  },
                  "equipment": {
                    "name": "%s",
                    "idSpace": { "svidBase": 1000, "ceidBase": 2000, "reportBase": 3000,
                                 "svidStride": 100, "ceidStride": 100, "reportStride": 100, "reserve": [[1, 999]] },
                    "constants": [
                      { "id": 501, "name": "MOVE_TIMEOUT", "type": "F4", "value": 15.0, "unit": "s" },
                      { "id": 502, "name": "ARM_COUNT", "type": "U1", "value": 2 }
                    ],
                    "units": [
                      {
                        "unitId": 1, "name": "ROBOT", "injectUnitId": true,
                        "svids": [
                          { "offset": 1, "slot": "ARM_POS", "type": "U2" },
                          { "offset": 2, "slot": "WAFER_ON_ARM", "type": "U1" },
                          { "offset": 3, "slot": "TRANSFER_COUNT", "type": "U4", "min": 0 },
                          { "offset": 4, "slot": "MOVE_TIME", "type": "F4", "unit": "s", "decimals": 2 },
                          { "offset": 5, "slot": "LAST_ERROR", "type": "ASCII" },
                          { "offset": 90, "slot": "UNIT_ID", "type": "U4" }
                        ],
                        "reports": [
                          { "offset": 1, "slot": "STATUS", "svids": ["UNIT_ID", "ARM_POS", "WAFER_ON_ARM", "TRANSFER_COUNT"] }
                        ],
                        "ceids": [
                          { "offset": 1, "slot": "TRANSFER_DONE", "reports": ["STATUS"] },
                          { "offset": 2, "slot": "TRANSFER_TIMEOUT", "reports": ["STATUS"] },
                          { "offset": 3, "slot": "ARM_HOME", "reports": ["STATUS"] }
                        ]
                      }
                    ]
                  },
                  "events": {
                    "maxPerSec": 20, "s6f11Wbit": true, "s6f1Wbit": true,
                    "plans": [
                      { "id": "rb-done", "ceid": 2001,
                        "trigger": { "type": "periodic", "periodMs": 6000, "jitterMs": 800 } },
                      { "id": "rb-timeout", "ceid": 2002,
                        "trigger": { "type": "onCondition", "expression": "svid.1004 > 14", "debounceMs": 1000 } },
                      { "id": "rb-home", "ceid": 2003,
                        "trigger": { "type": "afterEvent", "sourcePlan": "rb-done", "delayMs": 2000 } }
                    ]
                  },
                  "templates": {
                    "curves": [
                      { "id": "rb-pos", "type": "step",
                        "steps": [ { "fromMs": 0, "value": 1 }, { "fromMs": 3000, "value": 2 },
                                   { "fromMs": 6000, "value": 3 }, { "fromMs": 9000, "value": 4 } ] },
                      { "id": "rb-wafer", "type": "step",
                        "steps": [ { "fromMs": 0, "value": 0 }, { "fromMs": 2000, "value": 1 },
                                   { "fromMs": 6000, "value": 0 } ] },
                      { "id": "rb-count", "type": "ramp", "from": 0, "to": 999, "durationMs": 600000, "cyclic": true },
                      { "id": "rb-time", "type": "uniform", "min": 3.5, "max": 8.0, "resampleMs": 4000 }
                    ],
                    "composites": [],
                    "instances": [
                      { "id": "rb-pos", "template": "rb-pos", "svids": { "V": 1001 } },
                      { "id": "rb-wfr", "template": "rb-wafer", "svids": { "V": 1002 } },
                      { "id": "rb-cnt", "template": "rb-count", "svids": { "V": 1003 } },
                      { "id": "rb-time", "template": "rb-time", "svids": { "V": 1004 } }
                    ]
                  }
                }
                """.formatted(id, id.toUpperCase(Locale.ROOT));
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
