package com.shimizukenta.secssim.app.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.jsonhub.JsonObjectPair;
import com.shimizukenta.jsonhub.JsonString;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.event.PlanAction;
import com.shimizukenta.secssim.core.event.Trigger;
import com.shimizukenta.secssim.core.template.Expression;
import com.shimizukenta.secssim.core.template.ExpressionException;

/**
 * {@code events} 段（计划、触发、动作、限流、W 位）的读写。
 */
final class EventSectionJson {

    private static final String ROOT = "$";

    private EventSectionJson() {
    }

    /* ---------- 解析 ---------- */

    static EventSchedule parseEvents(JsonHub root) {
        if (!root.containsKey("events")) {
            return EventSchedule.empty();
        }

        String path = ROOT + ".events";
        JsonHub events = JsonAccess.object(root, "events", ROOT);

        if (!events.containsKey("plans")) {
            return EventSchedule.empty();
        }

        List<JsonHub> items = JsonAccess.elements(JsonAccess.array(events, "plans", path), path + ".plans");
        List<EventPlan> plans = new ArrayList<>(items.size());

        for (int i = 0; i < items.size(); i++) {
            String itemPath = path + ".plans[" + i + "]";
            JsonHub item = items.get(i);

            String offlinePolicy = JsonAccess.optionalString(item, "offlinePolicy", itemPath)
                    .orElse(EventPlan.OFFLINE_SKIP);
            if (!List.of(EventPlan.OFFLINE_SKIP, EventPlan.OFFLINE_QUEUE,
                    EventPlan.OFFLINE_FAIL).contains(offlinePolicy)) {
                throw new ConfigException(itemPath + ".offlinePolicy",
                        "未知离线策略: " + offlinePolicy + "（可选 skip / queue / fail）");
            }
            String onSendFailure = JsonAccess.optionalString(item, "onSendFailure", itemPath)
                    .orElse(EventPlan.FAILURE_KEEP);
            if (!List.of(EventPlan.FAILURE_KEEP, EventPlan.FAILURE_ROLLBACK).contains(onSendFailure)) {
                throw new ConfigException(itemPath + ".onSendFailure",
                        "未知发送失败策略: " + onSendFailure + "（可选 keep / rollback）");
            }
            try {
                plans.add(new EventPlan(
                        JsonAccess.string(item, "id", itemPath),
                        JsonAccess.integer(item, "ceid", itemPath),
                        parseTrigger(JsonAccess.object(item, "trigger", itemPath), itemPath + ".trigger"),
                        parseActions(item, itemPath),
                        offlinePolicy, onSendFailure));
            } catch (IllegalArgumentException e) {
                throw new ConfigException(itemPath, e.getMessage());
            }
        }

        validateChains(plans, path);
        return new EventSchedule(plans);
    }

    /**
     * 解析计划动作数组（schema v7 / 3.0 需求 5，可选）。
     *
     * <p>缺省 = 一条默认 S6F11，保证旧配置行为不变；字段完整性（kind=sml
     * 要 sml 原文）由 {@link PlanAction} 的构造器把关。</p>
     */
    private static List<PlanAction> parseActions(JsonHub item, String itemPath) {
        if (!item.containsKey("actions")) {
            return List.of();
        }
        String path = itemPath + ".actions";
        List<JsonHub> items = JsonAccess.elements(JsonAccess.array(item, "actions", itemPath), path);
        List<PlanAction> actions = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            String at = path + "[" + i + "]";
            JsonHub action = items.get(i);
            OptionalInt stream = JsonAccess.optionalInt(action, "stream", at);
            OptionalInt function = JsonAccess.optionalInt(action, "function", at);
            Boolean wbit = action.containsKey("wbit")
                    ? JsonAccess.bool(action, "wbit", false, at) : null;
            Map<String, String> params = new LinkedHashMap<>();
            if (action.containsKey("params")) {
                JsonHub paramsJson = JsonAccess.object(action, "params", at + ".params");
                for (JsonString key : paramsJson.keySet()) {
                    String name = key.unescaped();
                    String value = paramsJson.get(name).optionalString().orElseThrow(
                            () -> new ConfigException(at + ".params." + name, "应为字符串"));
                    params.put(name, value);
                }
            }
            try {
                actions.add(new PlanAction(
                        JsonAccess.optionalString(action, "kind", at).orElse(PlanAction.KIND_S6F11),
                        stream.isPresent() ? stream.getAsInt() : null,
                        function.isPresent() ? function.getAsInt() : null,
                        wbit,
                        JsonAccess.optionalString(action, "sml", at).orElse(null),
                        params));
            } catch (IllegalArgumentException e) {
                throw new ConfigException(at, e.getMessage());
            }
        }
        return actions;
    }

    /** 校验 AfterEvent 引用存在且无环；区间/条件触发不参与图。 */
    private static void validateChains(List<EventPlan> plans, String path) {
        Map<String, EventPlan> byId = new LinkedHashMap<>();
        for (int i = 0; i < plans.size(); i++) {
            EventPlan plan = plans.get(i);
            if (byId.putIfAbsent(plan.id(), plan) != null) {
                throw new ConfigException(path + ".plans[" + i + "].id", "计划 id 重复: " + plan.id());
            }
        }

        Map<String, Integer> state = new HashMap<>();
        for (String id : byId.keySet()) {
            visitChain(id, byId, state, path);
        }
    }

    private static void visitChain(String id, Map<String, EventPlan> byId, Map<String, Integer> state, String path) {
        Integer status = state.get(id);
        if (status != null && status == 1) {
            throw new ConfigException(path + ".plans", "AfterEvent 存在环: " + id);
        }
        if (status != null && status == 2) {
            return;
        }

        state.put(id, 1);
        EventPlan plan = byId.get(id);
        if (plan.trigger() instanceof Trigger.AfterEvent after) {
            if (!byId.containsKey(after.sourcePlan())) {
                throw new ConfigException(path + ".plans", "AfterEvent 引用了不存在的计划: " + after.sourcePlan());
            }
            visitChain(after.sourcePlan(), byId, state, path);
        }
        state.put(id, 2);
    }

    private static Trigger parseTrigger(JsonHub jh, String path) {
        String type = JsonAccess.string(jh, "type", path);

        return switch (type) {
            case "at" -> new Trigger.At(JsonAccess.longValue(jh, "atMs", path));
            case "periodic" -> new Trigger.Periodic(
                    JsonAccess.longValue(jh, "periodMs", path),
                    jh.containsKey("jitterMs") ? JsonAccess.longValue(jh, "jitterMs", path) : 0L);
            case "onCondition" -> {
                String source = JsonAccess.string(jh, "expression", path);
                Expression expression;
                try {
                    expression = Expression.parse(source);
                } catch (ExpressionException e) {
                    throw new ConfigException(path + ".expression", e.getMessage());
                }
                long debounceMs = jh.containsKey("debounceMs")
                        ? JsonAccess.longValue(jh, "debounceMs", path) : 0L;
                if (debounceMs < 0L) {
                    throw new ConfigException(path + ".debounceMs", "debounceMs 不能为负数");
                }
                yield new Trigger.OnCondition(expression, debounceMs);
            }
            case "afterEvent" -> {
                String sourcePlan = JsonAccess.string(jh, "sourcePlan", path);
                long delayMs = jh.containsKey("delayMs")
                        ? JsonAccess.longValue(jh, "delayMs", path) : 0L;
                if (delayMs < 0L) {
                    throw new ConfigException(path + ".delayMs", "delayMs 不能为负数");
                }
                yield new Trigger.AfterEvent(sourcePlan, delayMs);
            }
            default -> throw new ConfigException(path + ".type", "未知触发类型: " + type
                    + "（可选 at / periodic / onCondition / afterEvent）");
        };
    }

    static boolean parseS6f11Wbit(JsonHub root) {
        if (!root.containsKey("events")) {
            return false;
        }
        JsonHub events = JsonAccess.object(root, "events", ROOT);
        return JsonAccess.bool(events, "s6f11Wbit", true, ROOT + ".events");
    }

    static boolean parseS6f1Wbit(JsonHub root) {
        if (!root.containsKey("events")) {
            return false;
        }
        JsonHub events = JsonAccess.object(root, "events", ROOT);
        return JsonAccess.bool(events, "s6f1Wbit", true, ROOT + ".events");
    }

    static int parseMaxPerSec(JsonHub root) {
        if (!root.containsKey("events")) {
            return 50;
        }
        JsonHub events = JsonAccess.object(root, "events", ROOT);
        OptionalInt value = JsonAccess.optionalInt(events, "maxPerSec", ROOT + ".events");
        return value.orElse(50);
    }

    /** OptionalDouble → 装箱可空（JSON 字段缺失时为 {@code null}）。 */
    private static Double boxedOptionalDouble(JsonHub jh, String key) {
        return JsonAccess.optionalDouble(jh, key, ROOT + "." + key)
                .stream()
                .mapToObj(value -> value)
                .findFirst()
                .orElse(null);
    }

    /* ---------- 写回 ---------- */

    static JsonHub toJson(SessionConfig config, JsonHubBuilder b) {
        List<JsonHub> plans = new ArrayList<>();

        for (EventPlan plan : config.schedule().plans()) {
            List<JsonObjectPair> planPairs = new ArrayList<>();
            planPairs.add(b.pair("id", plan.id()));
            planPairs.add(b.pair("ceid", plan.ceid()));
            planPairs.add(b.pair("trigger", triggerJson(plan.trigger(), b)));
            /* 3.0 需求 5：动作与策略按原样回写，配置保存往返不丢字段。
               全缺省的计划不写这些键，保证 v4/v5/v6 旧配置零改动。 */
            if (plan.hasCustomActions()) {
                planPairs.add(b.pair("actions", actionsJson(plan.actions(), b)));
            }
            if (!EventPlan.OFFLINE_SKIP.equals(plan.offlinePolicy())) {
                planPairs.add(b.pair("offlinePolicy", plan.offlinePolicy()));
            }
            if (!EventPlan.FAILURE_KEEP.equals(plan.onSendFailure())) {
                planPairs.add(b.pair("onSendFailure", plan.onSendFailure()));
            }
            plans.add(b.object(planPairs));
        }

        List<JsonObjectPair> pairs = new ArrayList<>();
        pairs.add(b.pair("maxPerSec", config.maxEventsPerSec()));
        pairs.add(b.pair("plans", b.array(plans)));
        /* W 位默认 true：两个字段都显式写回，显式配 false 的配置不会在往返时被默认值翻回去 */
        pairs.add(b.pair("s6f11Wbit", config.s6f11Wbit()));
        pairs.add(b.pair("s6f1Wbit", config.s6f1Wbit()));

        return b.object(pairs);
    }

    /** 计划动作数组（kind/stream/function/wbit/sml/params，全可选的键不写）。 */
    private static JsonHub actionsJson(List<PlanAction> actions, JsonHubBuilder b) {
        List<JsonHub> items = new ArrayList<>(actions.size());
        for (PlanAction action : actions) {
            List<JsonObjectPair> pairs = new ArrayList<>();
            pairs.add(b.pair("kind", action.kind()));
            if (action.stream() != null) {
                pairs.add(b.pair("stream", action.stream()));
            }
            if (action.function() != null) {
                pairs.add(b.pair("function", action.function()));
            }
            if (action.wbit() != null) {
                pairs.add(b.pair("wbit", action.wbit()));
            }
            if (action.sml() != null) {
                pairs.add(b.pair("sml", action.sml()));
            }
            if (!action.params().isEmpty()) {
                List<JsonObjectPair> params = new ArrayList<>();
                for (Map.Entry<String, String> entry : action.params().entrySet()) {
                    params.add(b.pair(entry.getKey(), entry.getValue()));
                }
                pairs.add(b.pair("params", b.object(params)));
            }
            items.add(b.object(pairs));
        }
        return b.array(items);
    }

    private static JsonHub triggerJson(Trigger trigger, JsonHubBuilder b) {
        if (trigger instanceof Trigger.At at) {
            return b.object(
                    b.pair("type", "at"),
                    b.pair("atMs", at.atMs()));
        }

        if (trigger instanceof Trigger.OnCondition condition) {
            return b.object(
                    b.pair("type", "onCondition"),
                    b.pair("expression", condition.expression().source()),
                    b.pair("debounceMs", condition.debounceMs()));
        }

        if (trigger instanceof Trigger.AfterEvent after) {
            return b.object(
                    b.pair("type", "afterEvent"),
                    b.pair("sourcePlan", after.sourcePlan()),
                    b.pair("delayMs", after.delayMs()));
        }

        if (trigger instanceof Trigger.Periodic periodic) {
            return b.object(
                    b.pair("type", "periodic"),
                    b.pair("periodMs", periodic.periodMs()),
                    b.pair("jitterMs", periodic.jitterMs()));
        }

        /* 新增 Trigger 子类型时这里是编译可见的兜底，而不是对 Periodic 的盲转（CCE） */
        throw new IllegalStateException("未支持的触发类型: " + trigger.getClass().getName());
    }
}