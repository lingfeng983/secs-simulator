package com.shimizukenta.secssim.app.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.jsonhub.JsonObjectPair;
import com.shimizukenta.jsonhub.JsonString;
import com.shimizukenta.secssim.core.curve.Curve;
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
        return CurveJson.parse(jh, path);
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
        List<JsonObjectPair> pairs = new ArrayList<>();
        pairs.add(b.pair("id", def.id()));
        pairs.addAll(CurveJson.pairs(def.curve(), b));
        return b.object(pairs);
    }
}
