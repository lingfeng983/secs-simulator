package com.shimizukenta.secssim.runtime.session;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.Trigger;
import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.jsonhub.JsonObjectPair;

/**
 * 会话状态序列化辅助：事件编排 / 活动报警的 JSON 视图。
 *
 * <p>从 HsmsSessionRuntime 拆出的纯序列化块：输入是编排表与禁用集合，
 * 输出是界面契约——不碰协议状态。</p>
 */
final class SessionJsonViews {

    private SessionJsonViews() {
    }

    /** 事件效果数组(v6):结构与配置文件一致,前端编辑器可直接回填。 */
    private static JsonHub effectsJson(Iterable<com.shimizukenta.secssim.core.event.EventEffect> effects,
                                       JsonHubBuilder b) {
        List<JsonHub> items = new ArrayList<>();
        for (com.shimizukenta.secssim.core.event.EventEffect effect : effects) {
            List<JsonObjectPair> pairs = new ArrayList<>();
            pairs.add(b.pair("svid", effect.svid()));
            pairs.add(b.pair("op", effect.op().name().toLowerCase(java.util.Locale.ROOT)));
            if (effect.value() != null) {
                pairs.add(b.pair("value", effect.value()));
            }
            if (effect.delta() != null) {
                pairs.add(b.pair("delta", effect.delta()));
            }
            if (effect.holdMs() != null) {
                pairs.add(b.pair("holdMs", effect.holdMs()));
            }
            if (effect.decayMs() != null) {
                pairs.add(b.pair("decayMs", effect.decayMs()));
            }
            items.add(b.object(pairs));
        }
        return b.array(items);
    }

    /**
     * 事件编排列表（含每条计划的启用状态与触发规则;v6 起带效果数组）。
     *
     * @param plans         编排表里的全部计划
     * @param disabledPlans 运行时禁用的计划 id 集合
     * @return JSON 文本
     */
    static String eventsJson(Iterable<EventPlan> plans, Set<String> disabledPlans) {
        JsonHubBuilder b = JsonHub.getBuilder();
        List<JsonHub> events = new ArrayList<>();

        for (EventPlan plan : plans) {
            List<JsonObjectPair> pairs = new ArrayList<>();
            pairs.add(b.pair("id", plan.id()));
            pairs.add(b.pair("ceid", plan.ceid()));
            pairs.add(b.pair("enabled", !disabledPlans.contains(plan.id())));
            triggerJson(plan.trigger(), pairs, b);
            if (!plan.effects().isEmpty()) {
                pairs.add(b.pair("effects", effectsJson(plan.effects(), b)));
            }
            events.add(b.object(pairs));
        }

        return b.object(b.pair("events", b.array(events))).toJson();
    }

    /**
     * 活动报警列表（按 ALID 升序）。
     *
     * @param alarms 活动报警
     * @return JSON 数组文本
     */
    static String alarmsJson(List<HsmsSessionRuntime.AlarmState> alarms) {
        JsonHubBuilder b = JsonHub.getBuilder();
        List<HsmsSessionRuntime.AlarmState> sorted = new ArrayList<>(alarms);
        sorted.sort(Comparator.comparingInt(HsmsSessionRuntime.AlarmState::alid));

        List<JsonHub> items = new ArrayList<>(sorted.size());
        for (HsmsSessionRuntime.AlarmState alarm : sorted) {
            items.add(b.object(
                    b.pair("alid", alarm.alid()),
                    b.pair("text", alarm.text()),
                    b.pair("category", alarm.category()),
                    b.pair("atMillis", alarm.atMillis())));
        }
        return b.array(items).toJson();
    }

    private static void triggerJson(Trigger trigger, List<JsonObjectPair> pairs, JsonHubBuilder b) {
        if (trigger instanceof Trigger.At at) {
            pairs.add(b.pair("triggerType", "at"));
            pairs.add(b.pair("atMs", at.atMs()));
        } else if (trigger instanceof Trigger.Periodic periodic) {
            pairs.add(b.pair("triggerType", "periodic"));
            pairs.add(b.pair("periodMs", periodic.periodMs()));
            pairs.add(b.pair("jitterMs", periodic.jitterMs()));
        } else if (trigger instanceof Trigger.OnCondition condition) {
            pairs.add(b.pair("triggerType", "onCondition"));
            pairs.add(b.pair("expression", condition.expression().source()));
            pairs.add(b.pair("debounceMs", condition.debounceMs()));
        } else if (trigger instanceof Trigger.AfterEvent after) {
            pairs.add(b.pair("triggerType", "afterEvent"));
            pairs.add(b.pair("sourcePlan", after.sourcePlan()));
            pairs.add(b.pair("delayMs", after.delayMs()));
        }
    }
}
