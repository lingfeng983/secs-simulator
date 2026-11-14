package com.shimizukenta.secssim.app.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.jsonhub.JsonObjectPair;
import com.shimizukenta.secssim.core.curve.BoundPolicy;
import com.shimizukenta.secssim.core.curve.ConstantCurve;
import com.shimizukenta.secssim.core.curve.Curve;
import com.shimizukenta.secssim.core.curve.PwlCurve;
import com.shimizukenta.secssim.core.curve.RampCurve;
import com.shimizukenta.secssim.core.curve.RandomWalkCurve;
import com.shimizukenta.secssim.core.curve.SineCurve;
import com.shimizukenta.secssim.core.curve.StepCurve;
import com.shimizukenta.secssim.core.curve.UniformCurve;

/**
 * 曲线对象的 JSON 读写（schema v5 引入，v6 起同时用于 svid 直配）。
 *
 * <p>从 {@code TemplateConfigJson} 抽出来：templates.curves 与
 * equipment 里的 svid.curve 必须是<b>同一种形状</b>，否则"模板里能写的、
 * 直配上写不了"会让用户把两套语法各学一遍。这里只管形状，
 * 语义校验（类型是否允许、SVID 是否存在）在编译层做。</p>
 */
final class CurveJson {

    private CurveJson() {
    }

    /**
     * 解析曲线对象（不含 id 字段）。
     *
     * @param jh   曲线 JSON
     * @param path 字段路径
     * @return 曲线
     */
    static Curve parse(JsonHub jh, String path) {
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

    /**
     * 曲线对象的键值对（不含 id，由调用方决定要不要加）。
     *
     * @param curve 曲线
     * @param b     JSON builder
     * @return 键值对
     */
    static List<JsonObjectPair> pairs(Curve curve, JsonHubBuilder b) {
        List<JsonObjectPair> pairs = new ArrayList<>();
        pairs.add(b.pair("type", typeName(curve)));

        if (curve instanceof ConstantCurve c) {
            pairs.add(b.pair("value", c.value()));
        } else if (curve instanceof RampCurve c) {
            pairs.add(b.pair("from", c.from()));
            pairs.add(b.pair("to", c.to()));
            pairs.add(b.pair("durationMs", c.durationMs()));
            pairs.add(b.pair("cyclic", c.cyclic()));
        } else if (curve instanceof SineCurve c) {
            pairs.add(b.pair("min", c.min()));
            pairs.add(b.pair("max", c.max()));
            pairs.add(b.pair("periodMs", c.periodMs()));
            pairs.add(b.pair("phaseMs", c.phaseMs()));
        } else if (curve instanceof StepCurve c) {
            List<JsonHub> steps = new ArrayList<>();
            for (StepCurve.Step step : c.steps()) {
                steps.add(b.object(b.pair("fromMs", step.fromMs()), b.pair("value", step.value())));
            }
            pairs.add(b.pair("steps", b.array(steps)));
        } else if (curve instanceof PwlCurve c) {
            List<JsonHub> points = new ArrayList<>();
            for (PwlCurve.Point point : c.points()) {
                points.add(b.object(b.pair("tMs", point.tMs()), b.pair("value", point.value())));
            }
            pairs.add(b.pair("points", b.array(points)));
        } else if (curve instanceof UniformCurve c) {
            pairs.add(b.pair("min", c.min()));
            pairs.add(b.pair("max", c.max()));
            pairs.add(b.pair("resampleMs", c.resampleMs()));
        } else if (curve instanceof RandomWalkCurve c) {
            pairs.add(b.pair("start", c.start()));
            pairs.add(b.pair("driftPerSec", c.driftPerSec()));
            pairs.add(b.pair("sigma", c.sigma()));
            pairs.add(b.pair("gridMs", c.gridMs()));
            pairs.add(b.pair("octaves", c.octaves()));
            pairs.add(b.pair("min", c.min()));
            pairs.add(b.pair("max", c.max()));
            pairs.add(b.pair("policy", c.policy().name()));
        }
        return pairs;
    }

    private static String typeName(Curve curve) {
        if (curve instanceof ConstantCurve) {
            return "constant";
        }
        if (curve instanceof RampCurve) {
            return "ramp";
        }
        if (curve instanceof SineCurve) {
            return "sine";
        }
        if (curve instanceof StepCurve) {
            return "step";
        }
        if (curve instanceof PwlCurve) {
            return "pwl";
        }
        if (curve instanceof UniformCurve) {
            return "uniform";
        }
        if (curve instanceof RandomWalkCurve) {
            return "randomWalk";
        }
        throw new IllegalStateException("unsupported curve type: " + curve.getClass());
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
}
