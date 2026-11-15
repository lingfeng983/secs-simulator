package com.shimizukenta.secssim.app.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonString;
import com.shimizukenta.secssim.core.curve.Curve;
import com.shimizukenta.secssim.core.template.Expression;
import com.shimizukenta.secssim.core.value.DataSource;

/**
 * {@code source} 的解析（schema v7）。
 *
 * <p>一个数据项的值定义就是这五种形状之一：</p>
 *
 * <pre>
 * {"mode":"const",  "value": 180}
 * {"mode":"curve",  "clock":"phase", "curve": {...}, "transform": "roundTo(V, 0.1)"}
 * {"mode":"expr",   "clock":"phase", "vars": {"a": {...}}, "expr": "a * 0.9 + 10"}
 * {"mode":"cycle",  "start": 1, "step": 1, "every": 25}          // 数值：每 25 片进一档
 * {"mode":"cycle",  "pattern": "LOT{02}", "start": 1, "every": 25} // 文本：只用于 Context
 * </pre>
 *
 * <p>解析只管形状；"这个 SVID 是不是数值型""这个 ctx 名有没有定义"由编译层判。</p>
 */
final class DataSourceJson {

    private DataSourceJson() {
    }

    /**
     * 解析一个 source 对象。
     *
     * @param jh   source JSON
     * @param path 字段路径
     * @return 值定义
     */
    static DataSource parse(JsonHub jh, String path) {
        if (jh == null || !jh.isObject()) {
            throw new ConfigException(path, "source 应为对象");
        }
        String mode = JsonAccess.string(jh, "mode", path);

        return switch (mode) {
            case "const" -> parseConst(jh, path);
            case "curve" -> new DataSource.CurveSource(
                    CurveJson.parse(JsonAccess.object(jh, "curve", path), path + ".curve"),
                    parseClock(jh, path),
                    parseTransform(jh, path));
            case "expr" -> new DataSource.ExprSource(
                    parseVars(jh, path),
                    parseExpression(JsonAccess.string(jh, "expr", path), path + ".expr"),
                    parseClock(jh, path));
            case "cycle" -> parseCycle(jh, path);
            default -> throw new ConfigException(path + ".mode",
                    "未知的值来源类型 " + mode + "（可用: const / curve / expr / cycle）");
        };
    }

    /** 判定一个对象是不是 source（供 svid/ecid 的可选字段判断）。 */
    static boolean looksLikeSource(JsonHub jh) {
        return jh != null && jh.isObject() && jh.containsKey("mode");
    }

    private static DataSource parseConst(JsonHub jh, String path) {
        if (!jh.containsKey("value")) {
            throw new ConfigException(path + ".value", "const 需要 value");
        }
        JsonHub value = jh.get("value");
        if (value.isNumber()) {
            return new DataSource.Const(value.doubleValue());
        }
        return new DataSource.Text(value.optionalString()
                .orElseThrow(() -> new ConfigException(path + ".value", "const 的 value 应为数字或字符串")));
    }

    private static DataSource parseCycle(JsonHub jh, String path) {
        boolean textual = jh.containsKey("pattern") || jh.containsKey("list")
                || JsonAccess.optionalString(jh, "value", path).isPresent();
        long start = (long) JsonAccess.optionalDouble(jh, "start", path).orElse(1.0);
        long every = (long) JsonAccess.optionalDouble(jh, "every", path).orElse(1.0);
        if (every < 1L) {
            throw new ConfigException(path + ".every", "every 必须 >= 1");
        }

        if (textual) {
            String pattern = JsonAccess.optionalString(jh, "pattern", path).orElse(null);
            String value = JsonAccess.optionalString(jh, "value", path).orElse(null);
            List<String> list = new ArrayList<>();
            if (jh.containsKey("list")) {
                for (JsonHub item : JsonAccess.elements(JsonAccess.array(jh, "list", path), path + ".list")) {
                    list.add(item.optionalString()
                            .orElseThrow(() -> new ConfigException(path + ".list", "轮换列表只接受字符串")));
                }
            }
            return new DataSource.CycleText(value, pattern, list, start, every);
        }

        long step = (long) JsonAccess.optionalDouble(jh, "step", path).orElse(0.0);
        long base = (long) JsonAccess.optionalDouble(jh, "value", path).orElse((double) start);
        return new DataSource.CycleNumber(base, step, every);
    }

    private static DataSource.Clock parseClock(JsonHub jh, String path) {
        String clock = JsonAccess.optionalString(jh, "clock", path).orElse("phase");
        return switch (clock) {
            case "phase" -> DataSource.Clock.PHASE;
            case "absolute" -> DataSource.Clock.ABSOLUTE;
            case "cycle", "perCycle" -> DataSource.Clock.CYCLE;
            default -> throw new ConfigException(path + ".clock",
                    "未知时钟 " + clock + "（可用: phase / absolute / cycle）");
        };
    }

    private static Expression parseTransform(JsonHub jh, String path) {
        String text = JsonAccess.optionalString(jh, "transform", path).orElse("");
        return text.isBlank() ? null : parseExpression(text, path + ".transform");
    }

    private static Expression parseExpression(String text, String path) {
        try {
            return Expression.parse(text);
        } catch (RuntimeException e) {
            throw new ConfigException(path, "表达式非法：" + e.getMessage());
        }
    }

    private static Map<String, Curve> parseVars(JsonHub jh, String path) {
        Map<String, Curve> vars = new LinkedHashMap<>();
        if (!jh.containsKey("vars")) {
            return vars;
        }
        JsonHub object = JsonAccess.object(jh, "vars", path);
        for (JsonString key : object.keySet()) {
            String name = key.unescaped();
            vars.put(name, CurveJson.parse(object.get(name), path + ".vars." + name));
        }
        return vars;
    }

    /**
     * 序列化回 JSON（配置回写与界面展示用）。
     *
     * @param source 值定义
     * @param b      构造器
     * @return JSON
     */
    static JsonHub toJson(DataSource source, com.shimizukenta.jsonhub.JsonHubBuilder b) {
        List<com.shimizukenta.jsonhub.JsonObjectPair> pairs = new ArrayList<>();
        switch (source) {
            case DataSource.Const constant -> {
                pairs.add(b.pair("mode", b.string("const")));
                pairs.add(b.pair("value", b.number(constant.value())));
            }
            case DataSource.Text text -> {
                pairs.add(b.pair("mode", b.string("const")));
                pairs.add(b.pair("value", b.string(text.value())));
            }
            case DataSource.CurveSource curve -> {
                pairs.add(b.pair("mode", b.string("curve")));
                pairs.add(b.pair("clock", b.string(clockName(curve.clock()))));
                pairs.add(b.pair("curve", CurveJson.toJson(curve.curve(), b)));
                if (curve.transform() != null) {
                    pairs.add(b.pair("transform", b.string(curve.transform().source())));
                }
            }
            case DataSource.ExprSource expr -> {
                pairs.add(b.pair("mode", b.string("expr")));
                pairs.add(b.pair("clock", b.string(clockName(expr.clock()))));
                List<com.shimizukenta.jsonhub.JsonObjectPair> vars = new ArrayList<>();
                for (Map.Entry<String, Curve> entry : expr.vars().entrySet()) {
                    vars.add(b.pair(entry.getKey(), CurveJson.toJson(entry.getValue(), b)));
                }
                pairs.add(b.pair("vars", b.object(vars)));
                pairs.add(b.pair("expr", b.string(expr.expr().source())));
            }
            case DataSource.CycleNumber step -> {
                pairs.add(b.pair("mode", b.string("cycle")));
                pairs.add(b.pair("start", b.number(step.start())));
                pairs.add(b.pair("step", b.number(step.step())));
                pairs.add(b.pair("every", b.number(step.every())));
            }
            case DataSource.CycleText text -> {
                pairs.add(b.pair("mode", b.string("cycle")));
                if (text.value() != null) {
                    pairs.add(b.pair("value", b.string(text.value())));
                }
                if (text.pattern() != null) {
                    pairs.add(b.pair("pattern", b.string(text.pattern())));
                }
                if (!text.list().isEmpty()) {
                    List<JsonHub> items = new ArrayList<>();
                    for (String item : text.list()) {
                        items.add(b.string(item));
                    }
                    pairs.add(b.pair("list", b.array(items)));
                }
                pairs.add(b.pair("start", b.number(text.start())));
                pairs.add(b.pair("every", b.number(text.every())));
            }
        }
        return b.object(pairs);
    }

    private static String clockName(DataSource.Clock clock) {
        return switch (clock) {
            case PHASE -> "phase";
            case ABSOLUTE -> "absolute";
            case CYCLE -> "cycle";
        };
    }
}
