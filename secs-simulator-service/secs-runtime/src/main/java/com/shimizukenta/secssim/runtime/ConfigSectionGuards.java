package com.shimizukenta.secssim.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.shimizukenta.jsonhub.JsonHub;

/**
 * 分段保存的前置检查（2026-11-18 设计 §3.1）。
 *
 * <p>三条守卫里，A（结构变更要求下线）由调用方看会话状态即可，
 * B（运行中周期引用的项禁改）与 C（常量覆盖提示）要读配置里的引用链，
 * 放在这里做成纯函数，便于单测。</p>
 *
 * <p>引用链来自配置本身：</p>
 * <pre>
 *   svid 名 / ctx.&lt;ID&gt;  →  报告 reports[].svids[]  →  CEID ceids[].reports[]  →  cycle.events[].ceid
 * </pre>
 */
public final class ConfigSectionGuards {

    private ConfigSectionGuards() {
    }

    /** 一个被运行中周期引用的改动项。 */
    public record Conflict(String kind, String name, List<Integer> ceids) {
    }

    /** 一条「配置值会覆盖运行时值」的提示。 */
    public record ConstantOverwrite(int ecid, double runtime, double config) {
    }

    /**
     * 守卫 B：被运行中周期引用的 SVID / 晶圆属性是否被改动。
     *
     * @param oldText 磁盘上的旧正文
     * @param payload 本次提交的片段
     * @param changed 实际变化的段
     * @return 冲突列表；空表示可以保存
     */
    public static List<Conflict> cycleRefConflicts(String oldText,
                                                   Map<ConfigSection, JsonHub> payload,
                                                   List<ConfigSection> changed) {
        if (!changed.contains(ConfigSection.SVIDS) && !changed.contains(ConfigSection.CONTEXT)) {
            return List.of();
        }
        JsonHub root = JsonHub.fromJson(stripBom(oldText));
        Set<Integer> cycleCeids = cycleCeids(root);
        if (cycleCeids.isEmpty()) {
            return List.of();
        }
        Map<String, Set<Integer>> svidRefs = references(root, cycleCeids, false);
        Map<String, Set<Integer>> ctxRefs = references(root, cycleCeids, true);

        Map<String, LinkedHashSet<Integer>> hits = new LinkedHashMap<>();
        Map<String, String[]> meta = new LinkedHashMap<>();
        if (changed.contains(ConfigSection.SVIDS)) {
            for (String name : changedSvidNames(root, payload.get(ConfigSection.SVIDS))) {
                Set<Integer> ceids = svidRefs.get(name);
                if (ceids != null && !ceids.isEmpty()) {
                    String key = "svid\u0000" + name;
                    meta.putIfAbsent(key, new String[] {"svid", name});
                    hits.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).addAll(ceids);
                }
            }
        }
        if (changed.contains(ConfigSection.CONTEXT)) {
            for (String name : changedContextNames(root, payload.get(ConfigSection.CONTEXT))) {
                Set<Integer> ceids = ctxRefs.get(name);
                if (ceids != null && !ceids.isEmpty()) {
                    String key = "context\u0000" + name;
                    meta.putIfAbsent(key, new String[] {"context", name});
                    hits.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).addAll(ceids);
                }
            }
        }

        List<Conflict> out = new ArrayList<>();
        for (Map.Entry<String, LinkedHashSet<Integer>> entry : hits.entrySet()) {
            String[] kindAndName = meta.get(entry.getKey());
            out.add(new Conflict(kindAndName[0], kindAndName[1], new ArrayList<>(entry.getValue())));
        }
        return out;
    }

    /**
     * 守卫 C：配置里的常量值会覆盖运行时值（S2F15 写过）。
     *
     * @param oldText        旧正文（用来确认这段确实变了；值比较只看提交值）
     * @param payload        本次提交
     * @param runtimeValues  ECID → 运行时值（当前会话）
     * @return 有差异的常量列表
     */
    public static List<ConstantOverwrite> constantOverwrites(String oldText,
                                                             Map<ConfigSection, JsonHub> payload,
                                                             Map<Integer, Double> runtimeValues) {
        JsonHub constants = payload.get(ConfigSection.CONSTANTS);
        if (constants == null || !constants.isArray() || runtimeValues.isEmpty()) {
            return List.of();
        }
        List<ConstantOverwrite> out = new ArrayList<>();
        for (int i = 0; i < constants.length(); i += 1) {
            JsonHub item = constants.get(i);
            if (item == null || !item.isObject()) {
                continue;
            }
            Integer id = intOf(item, "id");
            if (id == null) {
                continue;
            }
            JsonHub value = item.get("value");
            if (value == null || value.isNull()) {
                continue;
            }
            double config;
            try {
                config = Double.parseDouble(value.toJson());
            } catch (RuntimeException e) {
                continue;
            }
            Double runtime = runtimeValues.get(id);
            if (runtime != null && Math.abs(runtime - config) > 1e-9) {
                out.add(new ConstantOverwrite(id, runtime, config));
            }
        }
        return out;
    }

    /* ---------------- 引用链 ---------------- */

    /** 周期片内事件用到的 CEID 集合。 */
    private static Set<Integer> cycleCeids(JsonHub root) {
        Set<Integer> out = new LinkedHashSet<>();
        JsonHub cycle = root.get("cycle");
        if (cycle == null || !cycle.isObject()) {
            return out;
        }
        JsonHub events = cycle.get("events");
        if (events == null || !events.isArray()) {
            return out;
        }
        for (int i = 0; i < events.length(); i += 1) {
            Integer ceid = intOf(events.get(i), "ceid");
            if (ceid != null) {
                out.add(ceid);
            }
        }
        return out;
    }

    /**
     * 只统计「被周期事件 CEID 引用」的报告条目。
     *
     * @param context true = 取 {@code ctx.*} 属性；false = 取 SVID 名
     */
    private static Map<String, Set<Integer>> references(JsonHub root, Set<Integer> cycleCeids, boolean context) {
        Map<String, Set<Integer>> out = new LinkedHashMap<>();
        JsonHub units = units(root);
        if (units == null) {
            return out;
        }
        int[] space = ceidSpace(root);
        for (int i = 0; i < units.length(); i += 1) {
            JsonHub unit = units.get(i);
            if (unit == null || !unit.isObject()) {
                continue;
            }
            int unitBase = space[0] + i * space[1];
            Map<String, List<String>> reportItems = new LinkedHashMap<>();
            JsonHub reports = unit.get("reports");
            if (reports != null && reports.isArray()) {
                for (int r = 0; r < reports.length(); r += 1) {
                    JsonHub report = reports.get(r);
                    String slot = stringOf(report, "slot");
                    if (slot != null) {
                        reportItems.put(slot, stringList(report, "svids"));
                    }
                }
            }
            JsonHub ceids = unit.get("ceids");
            if (ceids == null || !ceids.isArray()) {
                continue;
            }
            for (int c = 0; c < ceids.length(); c += 1) {
                JsonHub ceid = ceids.get(c);
                /* 配置里 CEID 写的是 offset，绝对号 = 段起点 + (单元位置-1)*步长 + offset */
                Integer offset = intOf(ceid, "offset");
                if (offset == null) {
                    offset = intOf(ceid, "ceid");
                }
                if (offset == null) {
                    continue;
                }
                int id = unitBase + offset;
                if (!cycleCeids.contains(id)) {
                    continue;
                }
                for (String report : stringList(ceid, "reports")) {
                    for (String name : reportItems.getOrDefault(report, List.of())) {
                        boolean isCtx = name.startsWith("ctx.");
                        if (isCtx != context) {
                            continue;
                        }
                        String key = isCtx ? name.substring("ctx.".length()).trim() : name.trim();
                        out.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(id);
                    }
                }
            }
        }
        return out;
    }

    /** CEID 段起点与步长（缺 idSpace 时用默认 2000 / 100）。 */
    private static int[] ceidSpace(JsonHub root) {
        int base = 2000;
        int stride = 100;
        JsonHub equipment = root.get("equipment");
        JsonHub space = equipment == null ? null : equipment.get("idSpace");
        if (space != null && space.isObject()) {
            Integer b = intOf(space, "ceidBase");
            Integer s = intOf(space, "ceidStride");
            if (b != null && b > 0) {
                base = b;
            }
            if (s != null && s > 0) {
                stride = s;
            }
        }
        return new int[] {base, stride};
    }

    /* ---------------- 改动项 ---------------- */

    /** 被改动的 SVID 名（删除或内容变化；新增不算）。只比提交里出现过的单元。 */
    private static Set<String> changedSvidNames(JsonHub root, JsonHub payload) {
        if (payload == null || !payload.isObject()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        JsonHub units = payload.get("units");
        if (units != null && units.isArray()) {
            for (int i = 0; i < units.length(); i += 1) {
                JsonHub unit = units.get(i);
                Integer unitId = intOf(unit, "unitId");
                if (unitId == null) {
                    continue;
                }
                Map<String, JsonHub> before = unitSvidIndex(root, unitId);
                Map<String, JsonHub> after = listIndex(unit.get("svids"), "u" + unitId);
                collectChanged(before, after, out);
            }
        }
        if (payload.containsKey("globalSvids")) {
            JsonHub equipment = root.get("equipment");
            Map<String, JsonHub> before = listIndex(equipment == null ? null : equipment.get("globalSvids"), "g");
            Map<String, JsonHub> after = listIndex(payload.get("globalSvids"), "g");
            collectChanged(before, after, out);
        }
        return out;
    }

    private static void collectChanged(Map<String, JsonHub> before, Map<String, JsonHub> after, Set<String> out) {
        for (Map.Entry<String, JsonHub> entry : before.entrySet()) {
            JsonHub next = after.get(entry.getKey());
            if (next == null || !ConfigSectionPatcher.canonical(entry.getValue())
                    .equals(ConfigSectionPatcher.canonical(next))) {
                out.add(entry.getKey().substring(entry.getKey().lastIndexOf(':') + 1));
            }
        }
    }

    /** 某个单元的 SVID 索引：键 {@code u<unitId>:<offset>:<name>}。 */
    private static Map<String, JsonHub> unitSvidIndex(JsonHub root, int unitId) {
        JsonHub units = units(root);
        if (units != null) {
            for (int i = 0; i < units.length(); i += 1) {
                JsonHub unit = units.get(i);
                Integer id = intOf(unit, "unitId");
                if (id != null && id == unitId) {
                    return listIndex(unit.get("svids"), "u" + unitId);
                }
            }
        }
        return new LinkedHashMap<>();
    }

    /** SVID 列表索引：键 {@code <scope>:<offset|id>:<名字>}。 */
    private static Map<String, JsonHub> listIndex(JsonHub list, String scope) {
        Map<String, JsonHub> out = new LinkedHashMap<>();
        if (list == null || !list.isArray()) {
            return out;
        }
        for (int i = 0; i < list.length(); i += 1) {
            JsonHub item = list.get(i);
            Integer id = intOf(item, "offset");
            if (id == null) {
                id = intOf(item, "id");
            }
            String name = stringOf(item, "slot");
            if (name == null) {
                name = stringOf(item, "name");
            }
            if (id == null || name == null) {
                continue;
            }
            out.put(scope + ":" + id + ":" + name, item);
        }
        return out;
    }

    /** 被改动的晶圆属性名。 */
    private static Set<String> changedContextNames(JsonHub root, JsonHub payload) {
        if (payload == null || !payload.isArray()) {
            return Set.of();
        }
        Map<String, JsonHub> before = contextIndex(root.get("context"));
        Map<String, JsonHub> after = contextIndex(payload);
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, JsonHub> entry : before.entrySet()) {
            JsonHub next = after.get(entry.getKey());
            if (next == null || !ConfigSectionPatcher.canonical(entry.getValue())
                    .equals(ConfigSectionPatcher.canonical(next))) {
                out.add(entry.getKey());
            }
        }
        return out;
    }

    private static Map<String, JsonHub> contextIndex(JsonHub list) {
        Map<String, JsonHub> out = new LinkedHashMap<>();
        if (list == null || !list.isArray()) {
            return out;
        }
        for (int i = 0; i < list.length(); i += 1) {
            JsonHub item = list.get(i);
            String id = stringOf(item, "id");
            if (id != null) {
                out.put(id, item);
            }
        }
        return out;
    }

    /* ---------------- JSON 小工具 ---------------- */

    private static JsonHub units(JsonHub root) {
        JsonHub equipment = root.get("equipment");
        if (equipment == null || !equipment.isObject()) {
            return null;
        }
        JsonHub units = equipment.get("units");
        return units != null && units.isArray() ? units : null;
    }

    private static List<String> stringList(JsonHub obj, String field) {
        List<String> out = new ArrayList<>();
        if (obj == null || !obj.isObject()) {
            return out;
        }
        JsonHub list = obj.get(field);
        if (list == null || !list.isArray()) {
            return out;
        }
        for (int i = 0; i < list.length(); i += 1) {
            JsonHub item = list.get(i);
            if (item != null && !item.isNull()) {
                String text = item.toJson();
                if (text.length() >= 2 && text.charAt(0) == '"') {
                    text = text.substring(1, text.length() - 1);
                }
                out.add(text);
            }
        }
        return out;
    }

    private static String stringOf(JsonHub obj, String field) {
        if (obj == null || !obj.isObject() || !obj.containsKey(field)) {
            return null;
        }
        JsonHub value = obj.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.toJson();
        if (text.length() >= 2 && text.charAt(0) == '"') {
            text = text.substring(1, text.length() - 1);
        }
        return text;
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

    private static String stripBom(String text) {
        return text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }
}
