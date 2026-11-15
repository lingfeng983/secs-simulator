package com.shimizukenta.secssim.runtime.hostsim;

import com.shimizukenta.jsonhub.JsonHub;

/** 主机采集配置的轻量 JSON 字段读取（避免与 secs-app JsonAccess 依赖纠缠）。 */
final class JsonFieldReader {

    private JsonFieldReader() {
    }

    static int intField(JsonHub obj, String key) {
        if (!obj.containsKey(key)) {
            throw new IllegalArgumentException("缺少必填字段: " + key);
        }
        return obj.get(key).optionalInt()
                .orElseThrow(() -> new IllegalArgumentException(key + " 应为整数"));
    }

    static long longField(JsonHub obj, String key, long fallback) {
        if (!obj.containsKey(key)) {
            return fallback;
        }
        return obj.get(key).optionalLong()
                .orElseThrow(() -> new IllegalArgumentException(key + " 应为整数"));
    }

    static String stringField(JsonHub obj, String key) {
        if (!obj.containsKey(key)) {
            return "";
        }
        return obj.get(key).optionalString().orElse("");
    }

    static JsonHub arrayField(JsonHub obj, String key) {
        if (!obj.containsKey(key)) {
            throw new IllegalArgumentException("缺少必填字段: " + key);
        }
        JsonHub value = obj.get(key);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(key + " 应为数组");
        }
        return value;
    }
}