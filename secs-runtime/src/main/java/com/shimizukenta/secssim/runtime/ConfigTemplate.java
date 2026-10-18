package com.shimizukenta.secssim.runtime;

/**
 * 配置编辑器的骨架模板。
 *
 * <p>界面上「新建」按钮摆的就是这份：一改就能编译通过的最小配置。
 * 放在代码里而不是散在前端，是为了让它和后端的校验判据保持同一份。
 * </p>
 */
public final class ConfigTemplate {

    private ConfigTemplate() {
    }

    /**
     * 生成一份最小可用配置。
     *
     * @param name 文件名（不含目录）；会话 id 取它的主名
     * @return Schema v5 JSON 文本
     */
    public static String skeleton(String name) {
        String id = name == null ? "" : name.trim();
        if (id.toLowerCase().endsWith(".json")) {
            id = id.substring(0, id.length() - ".json".length());
        }
        if (id.isEmpty()) {
            id = "line1";
        }

        return """
                {
                  "schemaVersion": 5,
                  "session": {
                    "id": "%s",
                    "passive": true,
                    "bindHost": "127.0.0.1",
                    "port": 0,
                    "deviceId": 61,
                    "seed": 3
                  },
                  "equipment": {
                    "name": "%s",
                    "idSpace": {},
                    "units": [
                      {
                        "unitId": 1,
                        "name": "CH1",
                        "injectUnitId": false,
                        "svids": [
                          { "offset": 1, "slot": "TEMP", "type": "F4", "unit": "C" }
                        ],
                        "reports": [],
                        "ceids": []
                      }
                    ]
                  },
                  "events": { "maxPerSec": 5, "plans": [] }
                }
                """.formatted(id, id.toUpperCase(java.util.Locale.ROOT));
    }
}
