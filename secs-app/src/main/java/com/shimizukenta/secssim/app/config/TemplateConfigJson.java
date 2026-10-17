package com.shimizukenta.secssim.app.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.jsonhub.JsonObjectPair;
import com.shimizukenta.jsonhub.JsonString;
import com.shimizukenta.secssim.core.curve.BoundPolicy;
import com.shimizukenta.secssim.core.curve.ConstantCurve;
import com.shimizukenta.secssim.core.curve.Curve;
import com.shimizukenta.secssim.core.curve.PwlCurve;
import com.shimizukenta.secssim.core.curve.RampCurve;
import com.shimizukenta.secssim.core.curve.RandomWalkCurve;
import com.shimizukenta.secssim.core.curve.SineCurve;
import com.shimizukenta.secssim.core.curve.StepCurve;
import com.shimizukenta.secssim.core.curve.UniformCurve;
import com.shimizukenta.secssim.core.template.Expression;
import com.shimizukenta.secssim.core.template.ExpressionException;

/**
 * Schema v5 的 templates 段读写。
 *
 * <p>曲线参数按类型展开；组合模板的 outputs 是“输出名 → 表达式”；
 * 实例的 svids 是“输出名 → 绝对 SVID”。语义校验（SVID 是否存在、
 * 单写者、环）在 {@link TemplateConfig#build} 里做。</p>
 */
public final class TemplateConfigJson {

    private static final String PATH = "$.templates";

    private TemplateConfigJson() {
    }

    /**
     * 解析 templates 段。
     *
     * @param root 配置根
     * @return 模板配置；没有该段时返回空
     */
    public static TemplateConfig parse(JsonHub root) {
        if (!root.containsKey("templates")) {
            return TemplateConfig.empty();
        }

        JsonHub templates = JsonAccess.object(root, "templates", "$");

        List<TemplateConfig.CurveDef> curves = new ArrayList<>();
        if (templates.containsKey("curves")) {
            List<JsonHub> items = JsonAccess.elements(
                    JsonAccess.array(templates, "curves", PATH), PATH + ".curves");
            for (int i = 0; i < items.size(); i++) {
                String itemPath = PATH + ".curves[" + i + "]";
                JsonHub item = items.get(i);
                curves.add(new TemplateConfig.CurveDef(
                        JsonAccess.string(item, "id", itemPath),
                        parseCurve(item, itemPath)));
            }
        }

        List<TemplateConfig.CompositeDef> composites = new ArrayList<>();
        if (templates.containsKey("composites")) {
            List<JsonHub> items = JsonAccess.elements(
                    JsonAccess.array(templates, "composites", PATH), PATH + ".composites");
            for (int i = 0; i < items.size(); i++) {
                composites.add(parseComposite(items.get(i), PATH + ".composites[" + i + "]"));
            }
        }

        List<TemplateConfig.InstanceDef> instances = new ArrayList<>();
        if (templates.containsKey("instances")) {
            List<JsonHub> items = JsonAccess.elements(
                    JsonAccess.array(templates, "instances", PATH), PATH + ".instances");
            for (int i = 0; i < items.size(); i++) {
                instances.add(parseInstance(items.get(i), PATH + ".instances[" + i + "]"));
            }
        }

        return new TemplateConfig(curves, composites, instances);
    }

    private static Curve parseCurve(JsonHub jh, String path) {
        String type = JsonAccess.string(jh, "type", path);

        try {
            return switch (type) {
                case "constant" -> new ConstantCurve(requiredDouble(jh, "value", path));
                case "ramp" -> new RampCurve(
                        requiredDouble(jh, "from", path),
                        requiredDouble(jh, "to", path),
                        requiredLong(jh, "durationMs", path),
                        JsonAccess.bool(jh, "cyclic", false, path));
                case "sine" -> new SineCurve(
                        requiredDouble(jh, "min", path),
                        requiredDouble(jh, "max", path),
                        requiredLong(jh, "periodMs", path),
                        optionalLong(jh, "phaseMs", 0L, path));
                case "step" -> new StepCurve(parseSteps(jh, path));
                case "pwl" -> new PwlCurve(parsePoints(jh, path));
                case "uniform" -> new UniformCurve(
                        requiredDouble(jh, "min", path),
                        requiredDouble(jh, "max", path),
                        requiredLong(jh, "resampleMs", path));
                case "randomWalk" -> new RandomWalkCurve(
                        requiredDouble(jh, "start", path),
                        optionalDouble(jh, "driftPerSec", 0.0, path),
                        optionalDouble(jh, "sigma", 0.0, path),
                        optionalLong(jh, "gridMs", 1_000L, path),
                        optionalInt(jh, "octaves", 1, path),
                        requiredDouble(jh, "min", path),
                        requiredDouble(jh, "max", path),
                        parsePolicy(jh, path));
                default -> throw new ConfigException(path + ".type",
                        "未知曲线类型: " + type + "（可选 constant / ramp / sine / step / pwl / uniform / randomWalk）");
            };
        } catch (IllegalArgumentException e) {
            throw new ConfigException(path, e.getMessage());
        }
    }

    private static List<StepCurve.Step> parseSteps(JsonHub jh, String path) {
        List<JsonHub> items = JsonAccess.elements(JsonAccess.array(jh, "steps", path), path + ".steps");
        List<StepCurve.Step> steps = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            String itemPath = path + ".steps[" + i + "]";
            JsonHub item = items.get(i);
            steps.add(new StepCurve.Step(
                    requiredLong(item, "fromMs", itemPath),
                    requiredDouble(item, "value", itemPath)));
        }
        return steps;
    }

    private static List<PwlCurve.Point> parsePoints(JsonHub jh, String path) {
        List<JsonHub> items = JsonAccess.elements(JsonAccess.array(jh, "points", path), path + ".points");
        List<PwlCurve.Point> points = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            String itemPath = path + ".points[" + i + "]";
            JsonHub item = items.get(i);
            points.add(new PwlCurve.Point(
                    requiredLong(item, "tMs", itemPath),
                    requiredDouble(item, "value", itemPath)));
        }
        return points;
    }

    private static BoundPolicy parsePolicy(JsonHub jh, String path) {
        String name = JsonAccess.optionalString(jh, "policy", path).orElse("REFLECT");
        try {
            return BoundPolicy.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigException(path + ".policy", "未知越界策略: " + name + "（可选 CLAMP / REFLECT / WRAP）");
        }
    }

    private static TemplateConfig.CompositeDef parseComposite(JsonHub item, String path) {
        String id = JsonAccess.string(item, "id", path);
        JsonHub outputsJson = JsonAccess.object(item, "outputs", path);
        Map<String, Expression> outputs = new LinkedHashMap<>();

        for (JsonString key : outputsJson.keySet()) {
            String name = key.unescaped();
            Optional<String> source = outputsJson.get(name).optionalString();
            if (source.isEmpty()) {
                throw new ConfigException(path + ".outputs." + name, "应为表达式字符串");
            }
            try {
                outputs.put(name, Expression.parse(source.get()));
            } catch (ExpressionException e) {
                throw new ConfigException(path + ".outputs." + name, e.getMessage());
            }
        }

        return new TemplateConfig.CompositeDef(id, outputs);
    }

    private static TemplateConfig.InstanceDef parseInstance(JsonHub item, String path) {
        String id = JsonAccess.string(item, "id", path);
        String template = JsonAccess.string(item, "template", path);

        JsonHub svidsJson = JsonAccess.object(item, "svids", path);
        Map<String, Integer> svids = new LinkedHashMap<>();
        for (JsonString key : svidsJson.keySet()) {
            String output = key.unescaped();
            OptionalInt svid = svidsJson.get(output).optionalInt();
            if (svid.isEmpty()) {
                throw new ConfigException(path + ".svids." + output, "应为整数 SVID");
            }
            svids.put(output, svid.getAsInt());
        }

        Map<String, Double> params = new LinkedHashMap<>();
        if (item.containsKey("params")) {
            JsonHub paramsJson = JsonAccess.object(item, "params", path);
            for (JsonString key : paramsJson.keySet()) {
                String name = key.unescaped();
                OptionalDouble value = paramsJson.get(name).optionalDouble();
                if (value.isEmpty()) {
                    throw new ConfigException(path + ".params." + name, "应为数字");
                }
                params.put(name, value.getAsDouble());
            }
        }

        return new TemplateConfig.InstanceDef(id, template, svids, params);
    }

    private static double requiredDouble(JsonHub jh, String key, String path) {
        return JsonAccess.optionalDouble(jh, key, path)
                .orElseThrow(() -> new ConfigException(path + "." + key, "缺少必填字段"));
    }

    private static long requiredLong(JsonHub jh, String key, String path) {
        if (!jh.containsKey(key)) {
            throw new ConfigException(path + "." + key, "缺少必填字段");
        }
        return JsonAccess.longValue(jh, key, path);
    }

    private static double optionalDouble(JsonHub jh, String key, double fallback, String path) {
        return JsonAccess.optionalDouble(jh, key, path).orElse(fallback);
    }

    private static long optionalLong(JsonHub jh, String key, long fallback, String path) {
        if (!jh.containsKey(key)) {
            return fallback;
        }
        return JsonAccess.longValue(jh, key, path);
    }

    private static int optionalInt(JsonHub jh, String key, int fallback, String path) {
        return JsonAccess.optionalInt(jh, key, path).orElse(fallback);
    }

    /**
     * 写回 templates 段。
     *
     * @param config 模板配置
     * @param b      JSON builder
     * @return 对象；空配置调用方不应写
     */
    public static JsonHub toJson(TemplateConfig config, JsonHubBuilder b) {
        List<JsonHub> curves = new ArrayList<>();
        for (TemplateConfig.CurveDef def : config.curves()) {
            curves.add(curveJson(def, b));
        }

        List<JsonHub> composites = new ArrayList<>();
        for (TemplateConfig.CompositeDef def : config.composites()) {
            List<JsonObjectPair> outputs = new ArrayList<>();
            for (Map.Entry<String, Expression> entry : def.outputs().entrySet()) {
                outputs.add(b.pair(entry.getKey(), entry.getValue().source()));
            }
            composites.add(b.object(
                    b.pair("id", def.id()),
                    b.pair("outputs", b.object(outputs))));
        }

        List<JsonHub> instances = new ArrayList<>();
        for (TemplateConfig.InstanceDef def : config.instances()) {
            List<JsonObjectPair> svids = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : def.svids().entrySet()) {
                svids.add(b.pair(entry.getKey(), entry.getValue()));
            }

            List<JsonObjectPair> pairs = new ArrayList<>();
            pairs.add(b.pair("id", def.id()));
            pairs.add(b.pair("template", def.template()));
            pairs.add(b.pair("svids", b.object(svids)));
            if (!def.params().isEmpty()) {
                List<JsonObjectPair> params = new ArrayList<>();
                for (Map.Entry<String, Double> entry : def.params().entrySet()) {
                    params.add(b.pair(entry.getKey(), entry.getValue()));
                }
                pairs.add(b.pair("params", b.object(params)));
            }
            instances.add(b.object(pairs));
        }

        return b.object(
                b.pair("curves", b.array(curves)),
                b.pair("composites", b.array(composites)),
                b.pair("instances", b.array(instances)));
    }

    private static JsonHub curveJson(TemplateConfig.CurveDef def, JsonHubBuilder b) {
        Curve curve = def.curve();

        if (curve instanceof ConstantCurve c) {
            return b.object(b.pair("id", def.id()), b.pair("type", "constant"), b.pair("value", c.value()));
        }
        if (curve instanceof RampCurve c) {
            return b.object(b.pair("id", def.id()), b.pair("type", "ramp"),
                    b.pair("from", c.from()), b.pair("to", c.to()),
                    b.pair("durationMs", c.durationMs()), b.pair("cyclic", c.cyclic()));
        }
        if (curve instanceof SineCurve c) {
            return b.object(b.pair("id", def.id()), b.pair("type", "sine"),
                    b.pair("min", c.min()), b.pair("max", c.max()),
                    b.pair("periodMs", c.periodMs()), b.pair("phaseMs", c.phaseMs()));
        }
        if (curve instanceof StepCurve c) {
            List<JsonHub> steps = new ArrayList<>();
            for (StepCurve.Step step : c.steps()) {
                steps.add(b.object(b.pair("fromMs", step.fromMs()), b.pair("value", step.value())));
            }
            return b.object(b.pair("id", def.id()), b.pair("type", "step"), b.pair("steps", b.array(steps)));
        }
        if (curve instanceof PwlCurve c) {
            List<JsonHub> points = new ArrayList<>();
            for (PwlCurve.Point point : c.points()) {
                points.add(b.object(b.pair("tMs", point.tMs()), b.pair("value", point.value())));
            }
            return b.object(b.pair("id", def.id()), b.pair("type", "pwl"), b.pair("points", b.array(points)));
        }
        if (curve instanceof UniformCurve c) {
            return b.object(b.pair("id", def.id()), b.pair("type", "uniform"),
                    b.pair("min", c.min()), b.pair("max", c.max()), b.pair("resampleMs", c.resampleMs()));
        }
        if (curve instanceof RandomWalkCurve c) {
            return b.object(b.pair("id", def.id()), b.pair("type", "randomWalk"),
                    b.pair("start", c.start()), b.pair("driftPerSec", c.driftPerSec()),
                    b.pair("sigma", c.sigma()), b.pair("gridMs", c.gridMs()),
                    b.pair("octaves", c.octaves()), b.pair("min", c.min()), b.pair("max", c.max()),
                    b.pair("policy", c.policy().name()));
        }
        throw new IllegalStateException("unsupported curve type: " + curve.getClass());
    }
}
