package com.shimizukenta.secssim.app.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.jsonhub.JsonString;
import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.core.template.TemplateGraph;

/**
 * 参数模板体：一段与 SVID 无关的「参数变化逻辑」。
 *
 * <p>形状是配置文档 {@code templates} 段的子集（曲线 / 组合 / 实例）加上一个
 * {@code result}（{@code <实例或模板id>.<输出名>}，最终输出）——实例不绑定
 * SVID（空 {@code svids}），绑定发生在设备配置时（复制快照进设备文档后由
 * 直配糖落 SVID）。两种写法：</p>
 *
 * <ul>
 *   <li>单曲线简形：{@code { "curve": { "type": "sine", ... } }}（可选
 *       {@code "id"} 定曲线 id，缺省 {@code main}）</li>
 *   <li>完整形：{@code { "curves": [...], "composites": [...],
 *       "instances": [...], "result": "main.value" }}</li>
 * </ul>
 *
 * <p>校验完全复用既有规则：曲线经 {@link CurveJson}、表达式经
 * {@code Expression.parse}、未知引用 / 环 / 重复 id 走
 * {@link TemplateConfig} 的模板图——本类不重写任何一条。</p>
 */
public final class ParamTemplateBody {

    private static final String PATH = "$";

    /** 空设备模型：模板体不绑定 SVID，SVID 存在性检查自然全部跳过。 */
    private static final ResolvedEquipment NO_EQUIPMENT =
            new ResolvedEquipment("", 0, Map.of(), Map.of(), Map.of(), Map.of());

    /**
     * 解析并校验后的模板体。
     *
     * @param templates      归一化后的模板配置（实例一律空 svids）
     * @param resultInstance result 指向的实例 id
     * @param resultOutput   result 指向的输出名
     */
    public record Body(TemplateConfig templates, String resultInstance, String resultOutput) {
    }

    private ParamTemplateBody() {
    }

    /**
     * 解析、归一化并校验模板体。
     *
     * <p>归一化：单曲线简形展开为完整形；{@code result} 直接指向模板 id
     * （而不是实例 id）时自动补一枚同名空绑定实例。返回的
     * {@link Body#templates()} 可直接经 {@link TemplateConfigJson#toJson}
     * 序列化回存。</p>
     *
     * @param text 模板体 JSON 原文
     * @return 校验后的模板体
     * @throws ConfigException JSON 非法、缺 result、引用/环/重复 id、
     *                         实例带了 svids、result 指向不存在的输出
     */
    public static Body parse(String text) {
        JsonHub body;
        try {
            body = JsonHub.fromJson(text);
        } catch (RuntimeException e) {
            throw new ConfigException(PATH, "不是合法的 JSON: " + e.getMessage());
        }
        if (!body.isObject()) {
            throw new ConfigException(PATH, "参数模板体应为 JSON 对象");
        }

        JsonHubBuilder b = JsonHubBuilder.getInstance();

        /* 单曲线简形 → 完整形（用户显式给了 result 就保留，不覆盖） */
        if (body.containsKey("curve")) {
            JsonHub curve = JsonAccess.object(body, "curve", PATH);
            String curveId = body.containsKey("id")
                    ? JsonAccess.string(body, "id", PATH)
                    : "main";
            String result = body.containsKey("result")
                    ? JsonAccess.string(body, "result", PATH)
                    : curveId + ".V";
            body = b.object(
                    b.pair("curves", b.array(List.of(withId(curve, curveId, b)))),
                    b.pair("result", result));
        }

        TemplateConfig templates = TemplateConfigJson.parse(
                b.object(b.pair("templates", body)));

        String result = JsonAccess.string(body, "result", PATH);
        int dot = result.lastIndexOf('.');
        if (dot <= 0 || dot == result.length() - 1) {
            throw new ConfigException(PATH + ".result",
                    "result 应写成 实例.输出（如 main.value）: " + result);
        }
        String targetId = result.substring(0, dot);
        String resultOutput = result.substring(dot + 1);

        Set<String> instanceIds = new LinkedHashSet<>();
        Set<String> templateIds = new LinkedHashSet<>();
        for (TemplateConfig.CurveDef def : templates.curves()) {
            templateIds.add(def.id());
        }
        for (TemplateConfig.CompositeDef def : templates.composites()) {
            templateIds.add(def.id());
        }

        /* 参数模板里的实例不绑定 SVID：SVID 归属是设备配置时的事 */
        for (TemplateConfig.InstanceDef def : templates.instances()) {
            instanceIds.add(def.id());
            if (!def.svids().isEmpty()) {
                throw new ConfigException(PATH + ".instances",
                        "参数模板里的实例不绑定 SVID，请清空 svids: " + def.id());
            }
        }

        /* result 指向模板 id 时自动补一枚同名空绑定实例 */
        List<TemplateConfig.InstanceDef> instances = new ArrayList<>(templates.instances());
        if (!instanceIds.contains(targetId)) {
            if (!templateIds.contains(targetId)) {
                throw new ConfigException(PATH + ".result",
                        "result 指向未知的实例或模板: " + targetId);
            }
            instances.add(new TemplateConfig.InstanceDef(targetId, targetId, Map.of(), Map.of()));
        }
        templates = new TemplateConfig(templates.curves(), templates.composites(), instances);

        /* 未知引用 / 环 / 重复 id 全部走模板图同一道门 */
        TemplateGraph graph = templates.buildWithDirect(NO_EQUIPMENT, Map.of());

        if (!graph.instance(targetId).template().outputs().contains(resultOutput)) {
            throw new ConfigException(PATH + ".result",
                    "result 的输出不存在: " + result + "（可用: "
                            + graph.instance(targetId).template().outputs() + "）");
        }

        return new Body(templates, targetId, resultOutput);
    }

    /**
     * 归一化为完整形并序列化（入库统一存完整形，前端拿到的都是同一形状）。
     *
     * @param text 模板体 JSON 原文
     * @return 归一化后的模板体 JSON（含 curves/composites/instances/result）
     * @throws ConfigException 同 {@link #parse(String)}
     */
    public static String normalize(String text) {
        Body body = parse(text);

        JsonHubBuilder b = JsonHubBuilder.getInstance();
        JsonHub templates = TemplateConfigJson.toJson(body.templates(), b);
        Map<JsonString, JsonHub> members = new LinkedHashMap<>();
        for (JsonString key : templates.keySet()) {
            members.put(key, templates.get(key.unescaped()));
        }
        members.put(JsonString.unescaped("result"),
                b.string(body.resultInstance() + "." + body.resultOutput()));
        return b.object(members).prettyPrint();
    }

    /** 拷贝一枚曲线定义并强制指定 id（其余键原样保留）。 */
    private static JsonHub withId(JsonHub curve, String id, JsonHubBuilder b) {
        Map<String, JsonHub> members = new LinkedHashMap<>();
        members.put("id", b.string(id));
        for (JsonString key : curve.keySet()) {
            String name = key.unescaped();
            if (!name.equals("id")) {
                members.put(name, curve.get(name));
            }
        }
        List<com.shimizukenta.jsonhub.JsonObjectPair> pairs = new ArrayList<>();
        for (Map.Entry<String, JsonHub> entry : members.entrySet()) {
            pairs.add(b.pair(entry.getKey(), entry.getValue()));
        }
        return b.object(pairs);
    }
}
