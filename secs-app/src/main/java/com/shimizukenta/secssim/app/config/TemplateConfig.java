package com.shimizukenta.secssim.app.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.core.curve.Curve;
import com.shimizukenta.secssim.core.template.CompositeTemplate;
import com.shimizukenta.secssim.core.template.Expression;
import com.shimizukenta.secssim.core.template.GraphException;
import com.shimizukenta.secssim.core.template.ParameterLayers;
import com.shimizukenta.secssim.core.template.SingleCurveTemplate;
import com.shimizukenta.secssim.core.template.Template;
import com.shimizukenta.secssim.core.template.TemplateGraph;

/**
 * Schema v5 的模板/曲线配置。
 *
 * <p>它只描述“有哪些模板、每个实例把哪个输出绑到哪个 SVID”，
 * 真正的 DAG 校验（单写者、环、未知引用）交给 {@link TemplateGraph}。
 * 这样配置层和值引擎不会各写一套规则。</p>
 *
 * @param curves     曲线定义
 * @param composites 组合模板定义
 * @param instances  实例
 */
public record TemplateConfig(List<CurveDef> curves, List<CompositeDef> composites, List<InstanceDef> instances) {

    /**
     * 空模板配置。
     *
     * @return 空配置
     */
    public static TemplateConfig empty() {
        return new TemplateConfig(List.of(), List.of(), List.of());
    }

    /**
     * 校验并创建。
     *
     * @param curves     曲线
     * @param composites 组合模板
     * @param instances  实例
     */
    public TemplateConfig {
        curves = List.copyOf(curves == null ? List.of() : curves);
        composites = List.copyOf(composites == null ? List.of() : composites);
        instances = List.copyOf(instances == null ? List.of() : instances);
    }

    /**
     * 是否没有任何模板配置。
     *
     * @return 空返回 {@code true}
     */
    public boolean isEmpty() {
        return this.curves.isEmpty() && this.composites.isEmpty() && this.instances.isEmpty();
    }

    /**
     * 构建模板图，并把输出绑定到绝对 SVID。
     *
     * @param equipment 已编译的设备模型（用于校验 SVID 是否存在）
     * @return 模板图
     * @throws ConfigException 未知模板/SVID、输出名不匹配、环或单写者冲突
     */
    public TemplateGraph build(ResolvedEquipment equipment) {
        Map<String, Template> templates = new LinkedHashMap<>();

        for (CurveDef def : this.curves) {
            putTemplate(templates, def.id(), new SingleCurveTemplate(def.id(), def.curve()));
        }
        for (CompositeDef def : this.composites) {
            putTemplate(templates, def.id(), new CompositeTemplate(def.id(), def.outputs()));
        }

        TemplateGraph.Builder graph = TemplateGraph.builder();

        for (InstanceDef def : this.instances) {
            Template template = templates.get(def.template());
            if (template == null) {
                throw new ConfigException("$.templates.instances",
                        "实例 " + def.id() + " 引用了未知模板: " + def.template());
            }
            if (def.svids().isEmpty()) {
                throw new ConfigException("$.templates.instances",
                        "实例 " + def.id() + " 至少要把一个输出绑定到 SVID");
            }

            for (Map.Entry<String, Integer> binding : def.svids().entrySet()) {
                if (!template.outputs().contains(binding.getKey())) {
                    throw new ConfigException("$.templates.instances",
                            "实例 " + def.id() + " 绑定了模板不存在的输出: " + binding.getKey()
                                    + "（可用: " + template.outputs() + "）");
                }
                if (!equipment.svids().containsKey(binding.getValue())) {
                    throw new ConfigException("$.templates.instances",
                            "实例 " + def.id() + " 绑定了未定义的 SVID: " + binding.getValue());
                }
            }

            ParameterLayers params = def.params().isEmpty()
                    ? ParameterLayers.empty()
                    : ParameterLayers.empty().with(ParameterLayers.Layer.UNIT, def.params());

            graph.add(new TemplateGraph.Instance(def.id(), template, def.svids(), params));
        }

        try {
            return graph.build();
        } catch (GraphException e) {
            throw new ConfigException("$.templates.instances", e.getMessage());
        }
    }

    private static void putTemplate(Map<String, Template> templates, String id, Template template) {
        Template previous = templates.putIfAbsent(id, template);
        if (previous != null) {
            throw new ConfigException("$.templates", "模板 id 重复: " + id);
        }
    }

    /**
     * 一条曲线定义。
     *
     * @param id    标识
     * @param curve 曲线
     */
    public record CurveDef(String id, Curve curve) {
    }

    /**
     * 一个组合模板定义。
     *
     * @param id      标识
     * @param outputs 输出名 → 表达式
     */
    public record CompositeDef(String id, Map<String, Expression> outputs) {
    }

    /**
     * 一个模板实例。
     *
     * @param id       实例标识
     * @param template 模板标识
     * @param svids    输出名 → 绝对 SVID
     * @param params   实例参数（最高层 UNIT）
     */
    public record InstanceDef(String id, String template, Map<String, Integer> svids, Map<String, Double> params) {

        /**
         * 校验并创建。
         *
         * @param id       实例标识
         * @param template 模板标识
         * @param svids    输出绑定
         * @param params   参数
         */
        public InstanceDef {
            ids(id, "instance id");
            ids(template, "template id");
            svids = Map.copyOf(svids == null ? Map.of() : svids);
            params = Map.copyOf(params == null ? Map.of() : params);
        }

        private static void ids(String value, String label) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(label + " must not be blank");
            }
        }
    }
}
