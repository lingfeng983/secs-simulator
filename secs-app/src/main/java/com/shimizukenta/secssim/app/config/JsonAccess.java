package com.shimizukenta.secssim.app.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.shimizukenta.jsonhub.JsonHub;

/**
 * 带路径追踪的 JSON 读取。
 *
 * <p>所有失败都抛 {@link ConfigException} 并带上字段路径，
 * 这样"哪个字段错了"不用靠猜。
 * </p>
 */
final class JsonAccess {

    private JsonAccess() {
    }

    static JsonHub object(JsonHub parent, String key, String path) {
        if (!parent.containsKey(key)) {
            throw new ConfigException(path + "." + key, "缺少必填字段");
        }
        JsonHub value = parent.get(key);
        if (!value.isObject()) {
            throw new ConfigException(path + "." + key, "应为对象");
        }
        return value;
    }

    static JsonHub array(JsonHub parent, String key, String path) {
        if (!parent.containsKey(key)) {
            throw new ConfigException(path + "." + key, "缺少必填数组");
        }
        JsonHub value = parent.get(key);
        if (!value.isArray()) {
            throw new ConfigException(path + "." + key, "应为数组");
        }
        return value;
    }

    static List<JsonHub> elements(JsonHub array, String path) {
        List<JsonHub> items = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            items.add(array.get(i));
        }
        if (items.isEmpty() && !array.isArray()) {
            throw new ConfigException(path, "应为数组");
        }
        return items;
    }

    static String string(JsonHub parent, String key, String path) {
        Optional<String> value = optionalString(parent, key, path);
        return value.orElseThrow(() -> new ConfigException(path + "." + key, "缺少必填字段"));
    }

    static Optional<String> optionalString(JsonHub parent, String key, String path) {
        if (!parent.containsKey(key)) {
            return Optional.empty();
        }
        JsonHub value = parent.get(key);
        Optional<String> text = value.optionalString();
        if (text.isEmpty()) {
            throw new ConfigException(path + "." + key, "应为字符串");
        }
        return text;
    }

    static int integer(JsonHub parent, String key, String path) {
        OptionalInt value = optionalInt(parent, key, path);
        return value.orElseThrow(() -> new ConfigException(path + "." + key, "缺少必填字段"));
    }

    static OptionalInt optionalInt(JsonHub parent, String key, String path) {
        if (!parent.containsKey(key)) {
            return OptionalInt.empty();
        }
        JsonHub value = parent.get(key);
        OptionalInt number = value.optionalInt();
        if (number.isEmpty()) {
            throw new ConfigException(path + "." + key, "应为整数");
        }
        return number;
    }

    static long longValue(JsonHub parent, String key, String path) {
        if (!parent.containsKey(key)) {
            throw new ConfigException(path + "." + key, "缺少必填字段");
        }
        JsonHub value = parent.get(key);
        if (!value.isNumber()) {
            throw new ConfigException(path + "." + key, "应为数字");
        }
        return value.optionalLong().orElse(value.optionalInt().orElse(0));
    }

    static OptionalDouble optionalDouble(JsonHub parent, String key, String path) {
        if (!parent.containsKey(key)) {
            return OptionalDouble.empty();
        }
        JsonHub value = parent.get(key);
        OptionalDouble number = value.optionalDouble();
        if (number.isEmpty()) {
            throw new ConfigException(path + "." + key, "应为数字");
        }
        return number;
    }

    static boolean bool(JsonHub parent, String key, boolean fallback, String path) {
        if (!parent.containsKey(key)) {
            return fallback;
        }
        JsonHub value = parent.get(key);
        Optional<Boolean> flag = value.optionalBoolean();
        if (flag.isEmpty()) {
            throw new ConfigException(path + "." + key, "应为布尔值");
        }
        return flag.get();
    }
}
