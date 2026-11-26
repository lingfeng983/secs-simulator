package com.shimizukenta.secssim.runtime.session;

/**
 * 会话计数/概览指标的装配（从 HsmsSessionRuntime 构造器抽出，评审 2026-11-24 A1 千行拆分）。
 *
 * <p>全部是 supplier 接线，无自有状态；求值时才取会话当前部件，
 * 与拆分前构造器内联装配逐行等价。</p>
 */
final class SessionMetricsWiring {

    private SessionMetricsWiring() {
        /* 纯静态工具 */
    }

    static SessionMetrics create(HsmsSessionRuntime host) {
        return new SessionMetrics(
                () -> host.eventReporter().emitted(),
                () -> host.scheduler.rejectedCount(),
                () -> host.scheduler.skippedCount(),
                () -> host.eventReporter().wbitDropped(),
                () -> host.tracePump.wbitDropped(),
                () -> host.terminalInbox() == null ? 0L : host.terminalInbox().received(),
                () -> host.alarmReporter().alarmsSent(),
                () -> host.alarmReporter().alarmDropped(),
                () -> (long) host.alarmReporter().alarms().size(),
                () -> host.tracePump.framesSent(),
                () -> host.tracePump.activeCount(),
                () -> (long) host.hostReports.reportCount(),
                () -> (long) host.hostReports.linkCount(),
                () -> host.dispatcher() == null ? 0L : host.dispatcher().s9Replies(),
                () -> host.spec.id(),
                () -> host.state().name(),
                () -> host.control.state().name(),
                () -> host.control.communicatable(),
                () -> host.model.name(),
                () -> host.spec.equipment().units().size(),
                () -> host.spec.port(),
                () -> host.msgRate().countInWindow(System.currentTimeMillis()),
                () -> host.diagnostics().eventRate().countInWindow(System.currentTimeMillis()),
                () -> host.diagnostics().lastEventAtMs(),
                () -> host.openedAtMs());
    }
}
