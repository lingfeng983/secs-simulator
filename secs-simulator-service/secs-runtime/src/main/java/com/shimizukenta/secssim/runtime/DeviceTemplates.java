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
                new Entry("tester", "测试机（双 SITE）", "双单元测试位、BIN 计数与低良率事件"));
    }

    /** 供错误提示的模板 id 清单，如 {@code bare / etch / cvd / tester}。 */
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
                    "port": 0,
                    "deviceId": 101,
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
                    "port": 0,
                    "deviceId": 102,
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
                    "port": 0,
                    "deviceId": 103,
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
