package com.shimizukenta.secssim.runtime.session;

import java.util.Map;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.jsonhub.JsonObjectPair;

/**
 * 会话的计数与概览视图。
 *
 * <p>计数散落在十几个协作者里（事件、报警、Trace、主机侧报告），
 * 概览要把它们摊成一张表给工作台看。放在一处，
 * "界面上显示的数字"与"实际计数的来源"不会走散。</p>
 */
final class SessionMetrics {

    /** 已发 S6F11 条数。 */
    private final java.util.function.LongSupplier emitted;

    /** 被限流拒收的事件数。 */
    private final java.util.function.LongSupplier rejected;

    /** 被禁用而丢弃的事件数。 */
    private final java.util.function.LongSupplier skipped;

    /** S6F11 因 ACK 队列满而丢弃的条数。 */
    private final java.util.function.LongSupplier wbitDropped;

    /** S6F1 因 ACK 队列满而丢弃的条数。 */
    private final java.util.function.LongSupplier traceWbitDropped;

    /** 收到的终端消息条数。 */
    private final java.util.function.LongSupplier terminalMessages;

    /** 已发报警条数。 */
    private final java.util.function.LongSupplier alarmsSent;

    /** 因队列满而丢弃的报警条数。 */
    private final java.util.function.LongSupplier alarmDropped;

    /** 当前活动报警数。 */
    private final java.util.function.LongSupplier activeAlarms;

    /** 已发 Trace 帧数。 */
    private final java.util.function.LongSupplier traceFrames;

    /** 进行中的 Trace 数。 */
    private final java.util.function.LongSupplier activeTraces;

    /** 主机侧定义的报告数。 */
    private final java.util.function.LongSupplier hostReports;

    /** 主机侧的事件-报告链接数。 */
    private final java.util.function.LongSupplier hostLinks;

    /** S9Fx 回了几次。 */
    private final java.util.function.LongSupplier s9Replies;

    /** 会话 id。 */
    private final java.util.function.Supplier<String> sessionId;

    /** 会话状态名。 */
    private final java.util.function.Supplier<String> stateName;

    /** 控制状态名。 */
    private final java.util.function.Supplier<String> controlStateName;

    /** 是否已与主机建立通信。 */
    private final java.util.function.BooleanSupplier hasPeer;

    /** 设备名。 */
    private final java.util.function.Supplier<String> deviceName;

    /** 单元数。 */
    private final java.util.function.Supplier<Integer> unitCount;

    /** 端口。 */
    private final java.util.function.Supplier<Integer> port;

    /** 每分钟报文数。 */
    private final java.util.function.LongSupplier msgsPerMin;

    /** 每分钟事件数。 */
    private final java.util.function.LongSupplier eventsPerMin;

    /** 最近一次事件时刻。 */
    private final java.util.function.LongSupplier lastEventAt;

    /** 打开时刻。 */
    private final java.util.function.LongSupplier openedAt;

    SessionMetrics(java.util.function.LongSupplier emitted,
                   java.util.function.LongSupplier rejected,
                   java.util.function.LongSupplier skipped,
                   java.util.function.LongSupplier wbitDropped,
                   java.util.function.LongSupplier traceWbitDropped,
                   java.util.function.LongSupplier terminalMessages,
                   java.util.function.LongSupplier alarmsSent,
                   java.util.function.LongSupplier alarmDropped,
                   java.util.function.LongSupplier activeAlarms,
                   java.util.function.LongSupplier traceFrames,
                   java.util.function.LongSupplier activeTraces,
                   java.util.function.LongSupplier hostReports,
                   java.util.function.LongSupplier hostLinks,
                   java.util.function.LongSupplier s9Replies,
                   java.util.function.Supplier<String> sessionId,
                   java.util.function.Supplier<String> stateName,
                   java.util.function.Supplier<String> controlStateName,
                   java.util.function.BooleanSupplier hasPeer,
                   java.util.function.Supplier<String> deviceName,
                   java.util.function.Supplier<Integer> unitCount,
                   java.util.function.Supplier<Integer> port,
                   java.util.function.LongSupplier msgsPerMin,
                   java.util.function.LongSupplier eventsPerMin,
                   java.util.function.LongSupplier lastEventAt,
                   java.util.function.LongSupplier openedAt) {
        this.emitted = emitted;
        this.rejected = rejected;
        this.skipped = skipped;
        this.wbitDropped = wbitDropped;
        this.traceWbitDropped = traceWbitDropped;
        this.terminalMessages = terminalMessages;
        this.alarmsSent = alarmsSent;
        this.alarmDropped = alarmDropped;
        this.activeAlarms = activeAlarms;
        this.traceFrames = traceFrames;
        this.activeTraces = activeTraces;
        this.hostReports = hostReports;
        this.hostLinks = hostLinks;
        this.s9Replies = s9Replies;
        this.sessionId = sessionId;
        this.stateName = stateName;
        this.controlStateName = controlStateName;
        this.hasPeer = hasPeer;
        this.deviceName = deviceName;
        this.unitCount = unitCount;
        this.port = port;
        this.msgsPerMin = msgsPerMin;
        this.eventsPerMin = eventsPerMin;
        this.lastEventAt = lastEventAt;
        this.openedAt = openedAt;
    }

    /**
     * 计数快照：事件、报警、Trace、主机侧报告等的累计值。
     *
     * @return 计数表
     */
    Map<String, Long> counters() {
        return Map.ofEntries(
                Map.entry("emitted", this.emitted.getAsLong()),
                Map.entry("rejected", this.rejected.getAsLong()),
                Map.entry("skipped", this.skipped.getAsLong()),
                Map.entry("wbitDropped", this.wbitDropped.getAsLong()),
                Map.entry("traceWbitDropped", this.traceWbitDropped.getAsLong()),
                Map.entry("terminalMessages", this.terminalMessages.getAsLong()),
                Map.entry("alarmsSent", this.alarmsSent.getAsLong()),
                Map.entry("alarmDropped", this.alarmDropped.getAsLong()),
                Map.entry("activeAlarms", this.activeAlarms.getAsLong()),
                Map.entry("traceFrames", this.traceFrames.getAsLong()),
                Map.entry("activeTraces", this.activeTraces.getAsLong()),
                Map.entry("hostReports", this.hostReports.getAsLong()),
                Map.entry("hostLinks", this.hostLinks.getAsLong()),
                Map.entry("s9Replies", this.s9Replies.getAsLong()));
    }

    /**
     * 工作台聚合用的单会话片段（schema v6 的 overview 端点）。
     *
     * @return JSON 对象文本(不带外层花括号也不带逗号——由调用方拼数组)
     */
    String overviewJson() {
        long now = System.currentTimeMillis();
        JsonHubBuilder b = JsonHub.getBuilder();

        java.util.List<JsonObjectPair> counterPairs = new java.util.ArrayList<>();
        for (Map.Entry<String, Long> entry : this.counters().entrySet()) {
            counterPairs.add(b.pair(entry.getKey(), entry.getValue()));
        }

        return b.object(
                b.pair("id", this.sessionId.get()),
                b.pair("state", this.stateName.get()),
                b.pair("controlState", this.controlStateName.get()),
                b.pair("hasPeer", this.hasPeer.getAsBoolean()),
                b.pair("device", this.deviceName.get()),
                b.pair("units", this.unitCount.get()),
                b.pair("port", this.port.get()),
                b.pair("counters", b.object(counterPairs)),
                b.pair("msgsPerMin", this.msgsPerMin.getAsLong()),
                b.pair("s6f11PerMin", this.eventsPerMin.getAsLong()),
                b.pair("lastEventAt", this.lastEventAt.getAsLong()),
                b.pair("uptimeMs", now - this.openedAt.getAsLong()))
                .toJson();
    }
}
