package com.shimizukenta.secssim.runtime.session;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;

import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.Trigger;
import com.shimizukenta.secssim.app.session.HostReportTable;
import com.shimizukenta.secssim.core.compile.ResolvedCeid;
import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.core.compile.ResolvedReport;
import com.shimizukenta.secssim.core.compile.ResolvedSvid;
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
     * 事件编排列表（含每条计划的启用状态、触发规则与归属;v6 起带效果数组）。
     *
     * <p>归属不单独存储，而是按计划引用的 CEID 反查：计划属于哪个单元，
     * 取决于它上报的那个事件属于哪个单元——两处分开存必然漂移。</p>
     *
     * @param plans         编排表里的全部计划
     * @param disabledPlans 运行时禁用的计划 id 集合
     * @param ceidOwner     CEID → 归属单元名（查不到时返回空串）
     * @return JSON 文本
     */
    static String eventsJson(Iterable<EventPlan> plans, Set<String> disabledPlans,
                             java.util.function.IntFunction<String> ceidOwner) {
        return eventsJson(plans, disabledPlans, ceidOwner, null);
    }

    /**
     * 同 {@link #eventsJson(Iterable, Set, java.util.function.IntFunction)}，
     * 额外带上每条计划的运行统计（v2 计划页用）。
     *
     * <p>统计来自 {@code runtime_plan_state}，与计划正文分属两处；只有界面
     * 需要它们时才传，v1 的编排视图保持原样（多出的字段会让契约对不上）。</p>
     *
     * @param plans         编排表
     * @param disabledPlans 运行时禁用的计划 id
     * @param ceidOwner     CEID → 归属单元名
     * @param stats         planId → 运行统计；null = 不带统计
     * @return JSON 文本
     */
    static String eventsJson(Iterable<EventPlan> plans, Set<String> disabledPlans,
                             java.util.function.IntFunction<String> ceidOwner,
                             Map<String, PlanRuntimeStat> stats) {
        JsonHubBuilder b = JsonHub.getBuilder();
        List<JsonHub> events = new ArrayList<>();

        for (EventPlan plan : plans) {
            List<JsonObjectPair> pairs = new ArrayList<>();
            pairs.add(b.pair("id", plan.id()));
            pairs.add(b.pair("ceid", plan.ceid()));
            String owner = ceidOwner == null ? "" : ceidOwner.apply(plan.ceid());
            pairs.add(b.pair("owner", owner == null ? "" : owner));
            pairs.add(b.pair("enabled", !disabledPlans.contains(plan.id())));
            if (stats != null) {
                PlanRuntimeStat stat = stats.getOrDefault(plan.id(), PlanRuntimeStat.EMPTY);
                pairs.add(b.pair("fireCount", stat.fireCount()));
                pairs.add(b.pair("skippedCount", stat.skippedCount()));
                pairs.add(b.pair("lastFireAt", stat.lastFireAt()));
                pairs.add(b.pair("nextFireAt", stat.nextFireAt()));
                pairs.add(b.pair("lastError", stat.lastError() == null ? "" : stat.lastError()));
            }
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
    /**
     * SVID / RPTID / CEID 目录，<b>反映实际发出去的那一份</b>，不只是本地配置。
     *
     * <p>取的是 {@link HostReportTable} 叠加之后的<b>有效</b>定义：主机用
     * S2F33/S2F35 下发过的条目以主机为准（ADR-0006），没碰过的仍是本地配置。
     * </p>
     *
     * <p>只列本地配置是不够的：主机完全新建一个本地没有的 RPTID 时，它一样会被发出去。
     * 漏掉这种条目，现场就只能对着报文猜设备"哪来的这个报告"。
     * </p>
     *
     * <p>按 ID 升序：这是给人看的清单，稳定顺序才能让界面上两次刷新之间的
     * 差异一眼看出是配置变了，而不是"顺序抖了"。
     * </p>
     *
     * @param model          本地设备模型
     * @param hostReports    主机侧下发的有效定义
     * @param isPinned       某个 SVID 是否被「设定」
     * @return JSON 文本
     */
    static String catalogJson(ResolvedEquipment model,
                              HostReportTable hostReports,
                              java.util.function.IntPredicate isPinned) {
        JsonHubBuilder b = JsonHub.getBuilder();

        /* 本地配置 + 主机新建的条目，键是绝对 ID */
        Map<Integer, ResolvedReport> reports = new TreeMap<>(model.reports());
        Map<Integer, ResolvedCeid> ceids = new TreeMap<>(model.ceids());

        List<Integer> hostCeids = new ArrayList<>(hostReports.definedCeids());
        for (int ceidId : hostCeids) {
            ceids.computeIfAbsent(ceidId, id -> new ResolvedCeid(id, "", "", List.of()));
        }
        List<Integer> hostRptids = new ArrayList<>(hostReports.definedRptids());
        for (int rptid : hostRptids) {
            reports.computeIfAbsent(rptid, id -> new ResolvedReport(id, "", "", List.of()));
        }

        List<JsonHub> reportItems = new ArrayList<>();
        for (ResolvedReport report : reports.values()) {
            /* 主机定义过就以主机为准，否则回落到本地配置 */
            List<Integer> effective = hostReports.svidsOf(report.id()).orElse(report.svidIds());
            reportItems.add(b.object(
                    b.pair("rptid", report.id()),
                    b.pair("owner", report.owner()),
                    b.pair("slot", report.slot()),
                    b.pair("svids", b.array(numbers(b, effective))),
                    b.pair("source", hostReports.svidsOf(report.id()).isPresent() ? "host" : "local")));
        }

        List<JsonHub> ceidItems = new ArrayList<>();
        for (ResolvedCeid ceid : ceids.values()) {
            List<Integer> effective = hostReports.reportsFor(ceid.id()).orElse(ceid.reportIds());
            ceidItems.add(b.object(
                    b.pair("ceid", ceid.id()),
                    b.pair("owner", ceid.owner()),
                    b.pair("slot", ceid.slot()),
                    b.pair("reports", b.array(numbers(b, effective))),
                    b.pair("source", hostReports.reportsFor(ceid.id()).isPresent() ? "host" : "local"),
                    b.pair("enabled", hostReports.isEnabled(ceid.id()))));
        }

        List<JsonHub> svids = new ArrayList<>();
        model.svids().values().stream()
                .sorted(Comparator.comparingInt(ResolvedSvid::id))
                .forEach(svid -> svids.add(b.object(
                        b.pair("svid", svid.id()),
                        b.pair("owner", svid.owner()),
                        b.pair("slot", svid.slot()),
                        b.pair("type", svid.type().name()),
                        b.pair("unit", svid.unitSymbol()),
                        b.pair("pinned", isPinned.test(svid.id())))));

        return b.object(
                b.pair("ceids", b.array(ceidItems)),
                b.pair("reports", b.array(reportItems)),
                b.pair("svids", b.array(svids)),
                b.pair("hostReports", hostReports.reportCount()),
                b.pair("hostLinks", hostReports.linkCount()),
                b.pair("hostAllDisabled", hostReports.isAllDisabled())).toJson();
    }

    /** 整数列表转 JSON 数组。 */
    private static List<JsonHub> numbers(JsonHubBuilder b, List<Integer> values) {
        return values.stream().map(b::number).map(n -> (JsonHub) n).toList();
    }

}
