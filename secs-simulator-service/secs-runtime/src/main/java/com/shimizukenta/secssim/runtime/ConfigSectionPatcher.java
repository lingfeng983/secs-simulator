package com.shimizukenta.secssim.runtime;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.jsonhub.JsonObjectPair;
import com.shimizukenta.jsonhub.JsonString;

/**
 * 按段替换配置正文。
 *
 * <p>2026-11-18：页面只提交自己拥有的片段，这里只换那一段，其余节点、键顺序、
 * 未知键全部原样保留（结构上等价于 {@code ConfigStore.writeConstants} 的做法）。
 * 整份重排缩进由 {@link JsonHub#prettyPrint()} 负责——结构化编辑本来就会把
 * 文本规整成统一缩进，所以这里的格式化差异不是新引入的。</p>
 *
 * <p>提交体形如 {@code {"sections":{"cycle":{...}}}}；某段传 {@code null}
 * 表示删除该节点（目前只有 {@code cycle} 允许删）。</p>
 */
public final class ConfigSectionPatcher {

    private ConfigSectionPatcher() {
    }

    /**
     * 解析提交体：{@code {"sections":{...}}}。
     *
     * @param json 请求体
     * @return 段 → 片段节点（可能为 null 节点）
     * @throws IllegalArgumentException 请求体不合法或段名未知
     */
    public static Map<ConfigSection, JsonHub> parsePayload(String json) {
        JsonHub root;
        try {
            root = JsonHub.fromJson(stripBom(json));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("请求体不是合法 JSON: " + e.getMessage(), e);
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("请求体必须是 JSON 对象");
        }
        JsonHub sections = root.get("sections");
        if (sections == null || !sections.isObject()) {
            throw new IllegalArgumentException("缺少 sections 对象");
        }
        Map<ConfigSection, JsonHub> payload = new LinkedHashMap<>();
        for (JsonString key : sections.keySet()) {
            String name = key.unescaped();
            ConfigSection section = ConfigSection.of(name)
                    .orElseThrow(() -> new IllegalArgumentException("未知配置段: " + name));
            payload.put(section, sections.get(name));
        }
        if (payload.isEmpty()) {
            throw new IllegalArgumentException("sections 不能为空");
        }
        return payload;
    }

    /**
     * 把片段写回正文。
     *
     * @param text   当前正文
     * @param payload 段 → 片段
     * @return 新正文
     * @throws IllegalArgumentException 片段形状不对（调用方按 400/422 处理）
     */
    public static String apply(String text, Map<ConfigSection, JsonHub> payload) {
        JsonHub root = parseRoot(text);
        JsonHubBuilder b = JsonHub.getBuilder();
        for (Map.Entry<ConfigSection, JsonHub> entry : payload.entrySet()) {
            root = patch(root, entry.getKey(), entry.getValue(), b);
        }
        return root.prettyPrint();
    }

    /**
     * 每段的指纹：只要该段内容不变，指纹就不变。
     *
     * <p>用来在保存前判定「这次实际改到了哪几段」——不信任前端报的段名，
     * 也顺便让「提交了但内容没变」变成幂等操作。</p>
     *
     * @param text 正文
     * @return 段 → 规范 JSON
     */
    public static Map<ConfigSection, String> fingerprints(String text) {
        JsonHub root = parseRoot(text);
        Map<ConfigSection, String> out = new EnumMap<>(ConfigSection.class);
        for (ConfigSection section : ConfigSection.values()) {
            out.put(section, canonical(view(root, section)));
        }
        return out;
    }

    /** 两次指纹之间真正变了的段（保持枚举声明顺序）。 */
    public static List<ConfigSection> changed(Map<ConfigSection, String> before, Map<ConfigSection, String> after) {
        List<ConfigSection> out = new ArrayList<>();
        for (ConfigSection section : ConfigSection.values()) {
            String left = before.get(section);
            String right = after.get(section);
            if (!java.util.Objects.equals(left, right)) {
                out.add(section);
            }
        }
        return out;
    }

    /* ---------------- 分段的规范视图（指纹与依赖检查共用） ---------------- */

    /**
     * 某一段在「判定是否变化」时的规范视图。
     *
     * <p>注意 {@code structure} 只看单元身份（unitId/名称/injectUnitId），
     * 不看单元里的 svids/reports/ceids —— 那些属于各自的段。</p>
     */
    public static JsonHub view(JsonHub root, ConfigSection section) {
        JsonHubBuilder b = JsonHub.getBuilder();
        switch (section) {
            case SESSION:
                return nodeOrNull(root.get("session"));
            case STRUCTURE: {
                JsonHub equipment = root.get("equipment");
                if (equipment == null || !equipment.isObject()) {
                    return b.nullValue();
                }
                List<JsonHub> units = new ArrayList<>();
                JsonHub rawUnits = equipment.get("units");
                if (rawUnits != null && rawUnits.isArray()) {
                    for (int i = 0; i < rawUnits.length(); i += 1) {
                        JsonHub unit = rawUnits.get(i);
                        if (unit != null && unit.isObject()) {
                            units.add(b.object(
                                    b.pair("unitId", nodeOrNull(unit.get("unitId"))),
                                    b.pair("name", nodeOrNull(unit.get("name"))),
                                    b.pair("injectUnitId", nodeOrNull(unit.get("injectUnitId")))));
                        }
                    }
                }
                return b.object(
                        b.pair("name", nodeOrNull(equipment.get("name"))),
                        b.pair("idSpace", nodeOrNull(equipment.get("idSpace"))),
                        b.pair("units", b.array(units)));
            }
            case SVIDS:
                return b.object(
                        b.pair("units", unitField(root, "svids")),
                        b.pair("globalSvids", equipmentField(root, "globalSvids")));
            case REPORTS:
                return unitField(root, "reports");
            case CEIDS:
                return unitField(root, "ceids");
            case CONSTANTS:
                return equipmentField(root, "constants");
            case CONTEXT:
                return nodeOrNull(root.get("context"));
            case CYCLE:
                return nodeOrNull(root.get("cycle"));
            case PLANS:
                return eventsField(root, "plans");
            case THROTTLE:
                return b.object(
                        b.pair("maxPerSec", eventsField(root, "maxPerSec")),
                        b.pair("s6f11Wbit", eventsField(root, "s6f11Wbit")),
                        b.pair("s6f1Wbit", eventsField(root, "s6f1Wbit")));
            default:
                return b.nullValue();
        }
    }

    /** 每个单元的某个字段：{@code [{unitId, <field>}, ...]}。 */
    private static JsonHub unitField(JsonHub root, String field) {
        JsonHubBuilder b = JsonHub.getBuilder();
        List<JsonHub> items = new ArrayList<>();
        JsonHub units = unitsOf(root);
        if (units != null) {
            for (int i = 0; i < units.length(); i += 1) {
                JsonHub unit = units.get(i);
                if (unit != null && unit.isObject()) {
                    items.add(b.object(
                            b.pair("unitId", nodeOrNull(unit.get("unitId"))),
                            b.pair(field, unit.get(field))));
                }
            }
        }
        return b.array(items);
    }

    private static JsonHub equipmentField(JsonHub root, String field) {
        JsonHub equipment = root.get("equipment");
        return equipment != null && equipment.isObject() ? nodeOrNull(equipment.get(field)) : nodeOrNull(null);
    }

    private static JsonHub eventsField(JsonHub root, String field) {
        JsonHub events = root.get("events");
        return events != null && events.isObject() ? nodeOrNull(events.get(field)) : nodeOrNull(null);
    }

    private static JsonHub unitsOf(JsonHub root) {
        JsonHub equipment = root.get("equipment");
        if (equipment == null || !equipment.isObject()) {
            return null;
        }
        JsonHub units = equipment.get("units");
        return units != null && units.isArray() ? units : null;
    }

    private static JsonHub nodeOrNull(JsonHub node) {
        return node == null ? JsonHub.getBuilder().nullValue() : node;
    }

    /* ---------------- 具体替换 ---------------- */

    private static JsonHub patch(JsonHub root, ConfigSection section, JsonHub value, JsonHubBuilder b) {
        switch (section) {
            case SESSION:
                return withField(root, "session", requireObject(value, "session"), b);
            case STRUCTURE:
                return patchStructure(root, requireObject(value, "structure"), b);
            case SVIDS:
                return patchUnits(root, requireObject(value, "svids"), "svids", true, b);
            case REPORTS:
                return patchUnits(root, requireObject(value, "reports"), "reports", false, b);
            case CEIDS:
                return patchUnits(root, requireObject(value, "ceids"), "ceids", false, b);
            case CONSTANTS:
                return patchEquipment(root, "constants", value, b);
            case CONTEXT:
                return value == null || value.isNull() ? withoutField(root, "context", b) : withField(root, "context", value, b);
            case CYCLE:
                return value == null || value.isNull() ? withoutField(root, "cycle", b) : withField(root, "cycle", value, b);
            case PLANS:
                return patchEvents(root, "plans", requireArray(value, "plans"), b);
            case THROTTLE:
                return patchThrottle(root, requireObject(value, "throttle"), b);
            default:
                throw new IllegalArgumentException("不支持的配置段: " + section.wire());
        }
    }

    private static JsonHub patchStructure(JsonHub root, JsonHub payload, JsonHubBuilder b) {
        JsonHub equipment = root.get("equipment");
        if (equipment == null || !equipment.isObject()) {
            throw new IllegalArgumentException("配置没有 equipment 段，无法修改结构");
        }
        JsonHub next = equipment;
        if (payload.containsKey("name")) {
            next = withField(next, "name", payload.get("name"), b);
        }
        if (payload.containsKey("idSpace")) {
            next = withField(next, "idSpace", payload.get("idSpace"), b);
        }
        if (payload.containsKey("units")) {
            next = withField(next, "units", rebuildUnits(equipment, requireArray(payload.get("units"), "structure.units"), b), b);
        }
        return withField(root, "equipment", next, b);
    }

    /**
     * 结构段里的单元表：只换 unitId/名称/injectUnitId。
     *
     * <p>已存在的单元沿用原来的 svids/reports/ceids（那些段单独保存）；
     * 新单元给空表；不在提交表里的单元被删除。</p>
     */
    private static JsonHub rebuildUnits(JsonHub equipment, JsonHub payloadUnits, JsonHubBuilder b) {
        JsonHub oldUnits = equipment.get("units");
        Map<Integer, JsonHub> byId = new LinkedHashMap<>();
        if (oldUnits != null && oldUnits.isArray()) {
            for (int i = 0; i < oldUnits.length(); i += 1) {
                JsonHub unit = oldUnits.get(i);
                Integer id = intOf(unit, "unitId");
                if (id != null) {
                    byId.put(id, unit);
                }
            }
        }
        List<JsonHub> out = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (int i = 0; i < payloadUnits.length(); i += 1) {
            JsonHub item = payloadUnits.get(i);
            if (item == null || !item.isObject()) {
                throw new IllegalArgumentException("structure.units[" + i + "] 必须是对象");
            }
            Integer id = intOf(item, "unitId");
            if (id == null) {
                throw new IllegalArgumentException("structure.units[" + i + "] 缺少 unitId");
            }
            if (!seen.add(id)) {
                throw new IllegalArgumentException("structure.units 里 unitId 重复: " + id);
            }
            JsonHub base = byId.get(id);
            if (base == null) {
                JsonHub unit = b.object(
                        b.pair("unitId", id),
                        b.pair("name", nodeOrNull(item.get("name"))),
                        b.pair("injectUnitId", item.containsKey("injectUnitId") ? item.get("injectUnitId") : b.build(false)),
                        b.pair("svids", b.array()),
                        b.pair("reports", b.array()),
                        b.pair("ceids", b.array()));
                out.add(unit);
                continue;
            }
            JsonHub unit = base;
            if (item.containsKey("name")) {
                unit = withField(unit, "name", item.get("name"), b);
            }
            if (item.containsKey("injectUnitId")) {
                unit = withField(unit, "injectUnitId", item.get("injectUnitId"), b);
            }
            out.add(unit);
        }
        return b.array(out);
    }

    /** svids / reports / ceids：按 unitId 换字段；可带 globalSvids（仅 svids 段）。 */
    private static JsonHub patchUnits(JsonHub root, JsonHub payload, String field, boolean allowGlobal, JsonHubBuilder b) {
        JsonHub equipment = root.get("equipment");
        if (equipment == null || !equipment.isObject()) {
            throw new IllegalArgumentException("配置没有 equipment 段，无法修改 " + field);
        }
        JsonHub units = equipment.get("units");
        if (units == null || !units.isArray()) {
            throw new IllegalArgumentException("配置没有 equipment.units，无法修改 " + field);
        }
        JsonHub payloadUnits = payload.get("units");
        Map<Integer, JsonHub> replacements = new LinkedHashMap<>();
        if (payloadUnits != null) {
            if (!payloadUnits.isArray()) {
                throw new IllegalArgumentException(field + ".units 必须是数组");
            }
            for (int i = 0; i < payloadUnits.length(); i += 1) {
                JsonHub item = payloadUnits.get(i);
                if (item == null || !item.isObject()) {
                    throw new IllegalArgumentException(field + ".units[" + i + "] 必须是对象");
                }
                Integer id = intOf(item, "unitId");
                if (id == null) {
                    throw new IllegalArgumentException(field + ".units[" + i + "] 缺少 unitId");
                }
                JsonHub value = item.get(field);
                if (value == null || !value.isArray()) {
                    throw new IllegalArgumentException(field + ".units[" + i + "]." + field + " 必须是数组");
                }
                replacements.put(id, value);
            }
        }
        List<JsonHub> out = new ArrayList<>();
        Set<Integer> consumed = new LinkedHashSet<>();
        for (int i = 0; i < units.length(); i += 1) {
            JsonHub unit = units.get(i);
            Integer id = intOf(unit, "unitId");
            JsonHub value = id == null ? null : replacements.get(id);
            if (value == null) {
                out.add(unit);
            } else {
                consumed.add(id);
                out.add(withField(unit, field, value, b));
            }
        }
        if (consumed.size() != replacements.size()) {
            for (Integer id : replacements.keySet()) {
                if (!consumed.contains(id)) {
                    throw new IllegalArgumentException("单元 " + id + " 不存在，无法修改 " + field);
                }
            }
        }
        JsonHub nextEquipment = withField(equipment, "units", b.array(out), b);
        if (allowGlobal && payload.containsKey("globalSvids")) {
            JsonHub value = payload.get("globalSvids");
            nextEquipment = value == null || value.isNull()
                    ? withoutField(nextEquipment, "globalSvids", b)
                    : withField(nextEquipment, "globalSvids", value, b);
        }
        return withField(root, "equipment", nextEquipment, b);
    }

    private static JsonHub patchEquipment(JsonHub root, String field, JsonHub value, JsonHubBuilder b) {
        JsonHub equipment = root.get("equipment");
        if (equipment == null || !equipment.isObject()) {
            throw new IllegalArgumentException("配置没有 equipment 段，无法修改 " + field);
        }
        JsonHub next = value == null || value.isNull()
                ? withoutField(equipment, field, b)
                : withField(equipment, field, value, b);
        return withField(root, "equipment", next, b);
    }

    private static JsonHub patchEvents(JsonHub root, String field, JsonHub value, JsonHubBuilder b) {
        JsonHub events = root.get("events");
        if (events == null || !events.isObject()) {
            events = b.object();
        }
        return withField(root, "events", withField(events, field, value, b), b);
    }

    private static JsonHub patchThrottle(JsonHub root, JsonHub payload, JsonHubBuilder b) {
        JsonHub events = root.get("events");
        if (events == null || !events.isObject()) {
            events = b.object();
        }
        JsonHub next = events;
        for (String field : List.of("maxPerSec", "s6f11Wbit", "s6f1Wbit")) {
            if (payload.containsKey(field)) {
                next = withField(next, field, payload.get(field), b);
            }
        }
        return withField(root, "events", next, b);
    }

    /* ---------------- JSON 小工具 ---------------- */

    private static JsonHub withField(JsonHub obj, String key, JsonHub value, JsonHubBuilder b) {
        List<JsonObjectPair> pairs = new ArrayList<>();
        boolean replaced = false;
        for (JsonString name : obj.keySet()) {
            String field = name.unescaped();
            if (field.equals(key)) {
                pairs.add(b.pair(field, value));
                replaced = true;
            } else {
                pairs.add(b.pair(field, obj.get(field)));
            }
        }
        if (!replaced) {
            pairs.add(b.pair(key, value));
        }
        return b.object(pairs);
    }

    private static JsonHub withoutField(JsonHub obj, String key, JsonHubBuilder b) {
        List<JsonObjectPair> pairs = new ArrayList<>();
        for (JsonString name : obj.keySet()) {
            String field = name.unescaped();
            if (!field.equals(key)) {
                pairs.add(b.pair(field, obj.get(field)));
            }
        }
        return b.object(pairs);
    }

    private static Integer intOf(JsonHub obj, String field) {
        if (obj == null || !obj.isObject() || !obj.containsKey(field)) {
            return null;
        }
        try {
            return Integer.valueOf(obj.get(field).toJson());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonHub requireObject(JsonHub value, String field) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(field + " 段必须是对象");
        }
        return value;
    }

    private static JsonHub requireArray(JsonHub value, String field) {
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(field + " 必须是数组");
        }
        return value;
    }

    private static JsonHub parseRoot(String text) {
        JsonHub root;
        try {
            root = JsonHub.fromJson(stripBom(text));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("配置原文不是合法 JSON: " + e.getMessage(), e);
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("配置根必须是 JSON 对象");
        }
        return root;
    }

    private static String stripBom(String text) {
        return text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }

    /** 规范 JSON：键排序、数组保序 —— 用来判定"这一段到底变没变"。 */
    static String canonical(JsonHub node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        appendCanonical(sb, node);
        return sb.toString();
    }

    private static void appendCanonical(StringBuilder sb, JsonHub node) {
        if (node == null || node.isNull()) {
            sb.append("null");
            return;
        }
        if (node.isArray()) {
            sb.append('[');
            for (int i = 0; i < node.length(); i += 1) {
                if (i > 0) {
                    sb.append(',');
                }
                appendCanonical(sb, node.get(i));
            }
            sb.append(']');
            return;
        }
        if (node.isObject()) {
            List<String> keys = new ArrayList<>();
            for (JsonString key : node.keySet()) {
                keys.add(key.unescaped());
            }
            java.util.Collections.sort(keys);
            sb.append('{');
            boolean first = true;
            for (String key : keys) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(key).append("\":");
                appendCanonical(sb, node.get(key));
            }
            sb.append('}');
            return;
        }
        sb.append(node.toJson());
    }
}
