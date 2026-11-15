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
import com.shimizukenta.secssim.core.curve.SequenceCurve;
import com.shimizukenta.secssim.core.curve.SineCurve;
import com.shimizukenta.secssim.core.curve.StepCurve;
import com.shimizukenta.secssim.core.curve.UniformCurve;

/**
 * 曲线对象的 JSON 读写（schema v7：值定义里 {@code mode:"curve"} 的 {@code curve} 段，
 * 以及 {@code expr} 的私有 {@code vars}）。
 *
 * <p>参数模板库、设备变量、设备常量用的是<b>同一种形状</b>，所以只有这一个解析器：
 * 库里能写的曲线，粘到任何数据项上都一样能写。这里只管形状，
 * 语义校验（类型是否允许、编号是否存在）在编译层做。</p>
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
                case "sequence" -> new SequenceCurve(
                        parseSegments(jh, path),
                        JsonAccess.bool(jh, "cyclic", false, path));
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
                        "未知曲线类型: " + type
                                + "（可选 constant / ramp / sine / step / pwl / uniform / randomWalk / sequence）");
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
        } else if (curve instanceof SequenceCurve c) {
            List<JsonHub> segments = new ArrayList<>();
            for (SequenceCurve.Segment segment : c.segments()) {
                segments.add(b.object(
                        b.pair("durationMs", segment.durationMs()),
                        b.pair("curve", b.object(pairs(segment.curve(), b)))));
            }
            pairs.add(b.pair("segments", b.array(segments)));
            pairs.add(b.pair("cyclic", c.cyclic()));
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
        if (curve instanceof SequenceCurve) {
            return "sequence";
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

    /**
     * 分段序列的各段；每段的 {@code curve} 递归走同一个 {@link #parse}，
     * 所以段里可以再放任何曲线类型（包括嵌套序列），校验规则只有一套。
     */
    private static List<SequenceCurve.Segment> parseSegments(JsonHub jh, String path) {
        List<JsonHub> items = JsonAccess.elements(JsonAccess.array(jh, "segments", path), path + ".segments");
        List<SequenceCurve.Segment> segments = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            String itemPath = path + ".segments[" + i + "]";
            JsonHub item = items.get(i);
            if (!item.isObject()) {
                throw new ConfigException(itemPath, "应为对象（{ durationMs, curve }）");
            }
            segments.add(new SequenceCurve.Segment(
                    requiredLong(item, "durationMs", itemPath),
                    parse(JsonAccess.object(item, "curve", itemPath), itemPath + ".curve")));
        }
        return segments;
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

    /**
     * 序列化曲线对象（不含 id）。
     *
     * <p>v7 之后曲线直接挂在数据项的 {@code source} 上，配置回写与界面展示都要
     * 能把它写回去——缺了这一半，"改一次常量就把曲线弄丢"是必然的。</p>
     *
     * @param curve 曲线
     * @param b     构造器
     * @return JSON
     */
    static JsonHub toJson(Curve curve, JsonHubBuilder b) {
        List<JsonObjectPair> pairs = new ArrayList<>();

        switch (curve) {
            case ConstantCurve value -> {
                pairs.add(b.pair("type", b.string("constant")));
                pairs.add(b.pair("value", b.number(value.value())));
            }
            case RampCurve ramp -> {
                pairs.add(b.pair("type", b.string("ramp")));
                pairs.add(b.pair("from", b.number(ramp.from())));
                pairs.add(b.pair("to", b.number(ramp.to())));
                pairs.add(b.pair("durationMs", b.number(ramp.durationMs())));
                pairs.add(b.pair("cyclic", ramp.cyclic()));
            }
            case SineCurve sine -> {
                pairs.add(b.pair("type", b.string("sine")));
                pairs.add(b.pair("min", b.number(sine.min())));
                pairs.add(b.pair("max", b.number(sine.max())));
                pairs.add(b.pair("periodMs", b.number(sine.periodMs())));
                pairs.add(b.pair("phaseMs", b.number(sine.phaseMs())));
            }
            case StepCurve step -> {
                pairs.add(b.pair("type", b.string("step")));
                List<JsonHub> steps = new ArrayList<>();
                for (StepCurve.Step item : step.steps()) {
                    steps.add(b.object(b.pair("fromMs", b.number(item.fromMs())),
                            b.pair("value", b.number(item.value()))));
                }
                pairs.add(b.pair("steps", b.array(steps)));
            }
            case PwlCurve pwl -> {
                pairs.add(b.pair("type", b.string("pwl")));
                List<JsonHub> points = new ArrayList<>();
                for (PwlCurve.Point item : pwl.points()) {
                    points.add(b.object(b.pair("tMs", b.number(item.tMs())),
                            b.pair("value", b.number(item.value()))));
                }
                pairs.add(b.pair("points", b.array(points)));
            }
            case UniformCurve uniform -> {
                pairs.add(b.pair("type", b.string("uniform")));
                pairs.add(b.pair("min", b.number(uniform.min())));
                pairs.add(b.pair("max", b.number(uniform.max())));
                pairs.add(b.pair("resampleMs", b.number(uniform.resampleMs())));
            }
            case RandomWalkCurve walk -> {
                pairs.add(b.pair("type", b.string("randomWalk")));
                pairs.add(b.pair("start", b.number(walk.start())));
                pairs.add(b.pair("driftPerSec", b.number(walk.driftPerSec())));
                pairs.add(b.pair("sigma", b.number(walk.sigma())));
                pairs.add(b.pair("gridMs", b.number(walk.gridMs())));
                pairs.add(b.pair("octaves", b.number(walk.octaves())));
                pairs.add(b.pair("min", b.number(walk.min())));
                pairs.add(b.pair("max", b.number(walk.max())));
                pairs.add(b.pair("policy", b.string(walk.policy().name())));
            }
            case SequenceCurve sequence -> {
                pairs.add(b.pair("type", b.string("sequence")));
                pairs.add(b.pair("cyclic", sequence.cyclic()));
                List<JsonHub> segments = new ArrayList<>();
                for (SequenceCurve.Segment segment : sequence.segments()) {
                    segments.add(b.object(b.pair("durationMs", b.number(segment.durationMs())),
                            b.pair("curve", toJson(segment.curve(), b))));
                }
                pairs.add(b.pair("segments", b.array(segments)));
            }
        }
        return b.object(pairs);
    }
}
