package com.shimizukenta.secssim.runtime.session;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.shimizukenta.secssim.app.config.CycleConfig;
import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.core.cycle.CycleClock;
import com.shimizukenta.secssim.core.cycle.LifecycleCycleClock;

/**
 * 工艺周期运行时（2026-11-16 计划生命周期重设计 §2.4）。
 *
 * <p>cycle 不再按片展开成 {@code cyc<w>-<i>} 的 At 计划混进事件编排——
 * 它有自己的状态机：</p>
 *
 * <pre>
 * STOPPED（待启动 / 被全停）→ RUNNING（startedAt = 点击时刻）→ 跑满 count 片 → FINISHED
 * </pre>
 *
 * <p>三个要点：</p>
 * <ul>
 *   <li><b>锚点 = 启动时刻</b>：启动晚了不再"前几片全部错过"，从第 0 片完整跑
 *       （修正旧展开语义）；停止后再启动从第 0 片重来；</li>
 *   <li><b>片内事件</b>：每 tick 推进片边界，片内到期的 {@code (atMs → CEID)}
 *       逐条 {@code emit(ceid, "cycle")}——过同一道设备级限流（{@link SessionRateGate}）
 *       与 S2F37 门控，落台账（plan_id = {@code cycle}，D5）；</li>
 *   <li><b>值随周期启停</b>：{@link LifecycleCycleClock} 与值求值共用——启动 /
 *       停止 / 跑满同时驱动片内事件与 SVID / 常量 / 晶圆属性曲线（§5.2）。</li>
 * </ul>
 *
 * <p>全停事件（§2.1 清单）作用到这里就是 {@link #stop()}：回 STOPPED 待手动启动。
 * 离线 / 限流丢弃不产生台账行（D8），可观测性由 {@code skipped} / {@code rejected}
 * 计数与运行态 JSON 承担。</p>
 */
public final class CycleRunner {

    /** 运行状态（界面展示与 JSON 同名）。 */
    enum State {
        /** 待启动（或被全停）：值冻结在进入停止时的表现。 */
        STOPPED,
        /** 运行中：startedAt = 点击时刻。 */
        RUNNING,
        /** 已完成：跑满 count 片，冻结在末相位。 */
        FINISHED,
    }

    /** 一条片内事件的出口：ceid + 计划内排定的触发时刻（台账 scheduled_at 用）。 */
    interface FireSink {
        void fire(int ceid, long scheduledAtMs) throws Exception;
    }

    private final java.util.function.LongSupplier clockNow;
    private final SessionRateGate rateGate;
    private final java.util.function.BooleanSupplier canSend;
    private final FireSink fireSink;

    private java.util.function.Consumer<Exception> errorSink = ignored -> { };
    private String sessionId = "";

    /** 当前 cycle 配置；{@code null} = 配置里没有 cycle 段（start 会被拒）。 */
    private volatile CycleConfig config;
    /** 与值求值共享的生命周期时钟。 */
    private volatile LifecycleCycleClock lifecycleClock;

    private volatile State state = State.STOPPED;
    private volatile long startedAt;
    private volatile int waferIndex;
    private volatile long firedCount;
    /** 当前片内哪些事件已经发过（随片重置）。 */
    private volatile boolean[] firedFlags = new boolean[0];

    CycleRunner(java.util.function.LongSupplier clockNow,
                SessionRateGate rateGate,
                java.util.function.BooleanSupplier canSend,
                FireSink fireSink) {
        this.clockNow = clockNow;
        this.rateGate = rateGate;
        this.canSend = canSend;
        this.fireSink = fireSink;
    }

    void errorSink(java.util.function.Consumer<Exception> sink) {
        this.errorSink = sink == null ? ignored -> { } : sink;
    }

    void sessionId(String id) {
        this.sessionId = id;
    }

    /**
     * 装载 cycle 配置与生命周期时钟（open / 热重载共用）。
     *
     * <p>全停语义：装载即回 STOPPED 待手动启动（热重载重建值来源时时钟也换新，
     * 两者是同一个实例）。</p>
     *
     * @param config  cycle 段（{@code null} = 没有 cycle 段）
     * @param clock   生命周期时钟（与三套值对象共享）
     */
    void reconfigure(CycleConfig config, LifecycleCycleClock clock) {
        this.config = config;
        this.lifecycleClock = clock;
        this.state = State.STOPPED;
        this.startedAt = 0L;
        this.waferIndex = 0;
        this.firedCount = 0L;
        this.firedFlags = new boolean[config == null ? 0 : config.events().size()];
    }

    /** 配置里是否有 cycle 段。 */
    boolean hasCycle() {
        return this.config != null;
    }

    State state() {
        return this.state;
    }

    long firedCount() {
        return this.firedCount;
    }

    int waferIndex() {
        return this.waferIndex;
    }

    long startedAt() {
        return this.startedAt;
    }

    /**
     * 启动周期：锚点 = 点击时刻；从第 0 片重来。
     *
     * @param nowMs 当前时钟读数
     * @return {@code false} = 配置里没有 cycle 段，无从启动
     */
    boolean start(long nowMs) {
        CycleConfig cfg = this.config;
        if (cfg == null) {
            return false;
        }
        this.startedAt = nowMs;
        this.waferIndex = 0;
        this.firedCount = 0L;
        this.firedFlags = new boolean[cfg.events().size()];
        this.lifecycleClock.start(nowMs);
        this.state = State.RUNNING;
        return true;
    }

    /** 停止周期：值冻结在进入停止时的表现（D10）；回 STOPPED 待手动启动。 */
    void stop() {
        if (this.state == State.RUNNING) {
            this.lifecycleClock.stop(this.clockNow.getAsLong());
        }
        this.state = State.STOPPED;
    }

    /** 每 tick 推进：片边界、片内到期事件、跑满转 FINISHED。 */
    void pump() {
        if (this.state != State.RUNNING) {
            return;
        }
        CycleConfig cfg = this.config;
        LifecycleCycleClock clock = this.lifecycleClock;
        if (cfg == null || clock == null) {
            return;
        }
        if (!this.canSend.getAsBoolean()) {
            /* 链路/控制态刚离开 ONLINE：全停钩子马上会到，这里先静默丢一拍 */
            return;
        }

        long now = this.clockNow.getAsLong();
        long elapsed = now - this.startedAt;
        if (elapsed >= clock.endMs()) {
            /* 跑满 count 片：FINISHED，值冻结在末相位（审计 P1-1） */
            clock.finish(now);
            this.state = State.FINISHED;
            return;
        }

        /* 逐片补齐：从当前片起把「排定时刻 <= now」的未发事件全部发掉。
           tick 间隔跨片（或粗粒度测试时钟）时，上一片没发完的事件在这里补发，
           不因换片重置而漏发。 */
        int wafer = clock.indexAt(now);
        while (this.state == State.RUNNING && this.waferIndex < wafer) {
            this.fireWaferDue(cfg, clock, this.waferIndex, elapsed);
            this.waferIndex += 1;
            this.firedFlags = new boolean[cfg.events().size()];
        }
        this.fireWaferDue(cfg, clock, wafer, elapsed);
        this.waferIndex = wafer;
    }

    /** 发一片内「排定时刻 <= elapsed」的未发事件（跨片补漏共用）。 */
    private void fireWaferDue(CycleConfig cfg, LifecycleCycleClock clock, int wafer, long elapsed) {
        List<CycleConfig.Event> events = cfg.events();
        boolean[] fired = this.firedFlags;
        long waferStart = clock.startOf(wafer);
        for (int i = 0; i < events.size(); i++) {
            if (fired[i]) {
                continue;
            }
            long fireAt = waferStart + events.get(i).atMs();
            if (fireAt > elapsed) {
                continue;
            }
            fired[i] = true;
            this.fire(events.get(i).ceid(), this.startedAt + fireAt);
        }
    }

    /** 发一条片内事件：过设备级限流 → reporter（S2F37 / 台账 / SSE 都在那里）。 */
    private void fire(int ceid, long scheduledAtMs) {
        if (!this.rateGate.tryAcquire(this.clockNow.getAsLong())) {
            /* 限流丢弃不记台账（D8）——可观测性走设备的 rejected 计数 */
            return;
        }
        try {
            this.fireSink.fire(ceid, scheduledAtMs);
            this.firedCount++;
        } catch (Exception e) {
            this.errorSink.accept(e);
            Log.error("会话 " + this.sessionId + " 片内事件发送失败: ceid " + ceid, e);
        }
    }

    /**
     * 预览用：按当前状态展开窗口内的片内事件时刻。
     *
     * <p>RUNNING 用真实 startedAt 作锚（窗口里画的就是实跑会发的时刻）；
     * 非 RUNNING 返回空（cycle 页的预览走草稿通道，锚 = 窗口起点）。</p>
     *
     * @param fromMs 窗口起点（不含）
     * @param toMs   窗口终点（含）
     * @return {@code [atMs, ceid]} 列表，升序
     */
    List<long[]> markFires(long fromMs, long toMs) {
        if (this.state != State.RUNNING || this.config == null || this.lifecycleClock == null) {
            return List.of();
        }
        CycleClock clock = this.lifecycleClock.compiled();
        List<long[]> marks = new ArrayList<>();
        for (int wafer = 0; wafer < this.config.count(); wafer++) {
            long waferStart = this.startedAt + clock.startOf(wafer);
            for (CycleConfig.Event event : this.config.events()) {
                long at = waferStart + event.atMs();
                if (fromMs < at && at <= toMs) {
                    marks.add(new long[] { at, event.ceid() });
                }
            }
        }
        marks.sort(Comparator.comparingLong(mark -> mark[0]));
        return marks;
    }

    /**
     * 运行态 JSON（cycle 页运行卡）。
     *
     * @return {@code {"state":...,"startedAt":...,"waferIndex":...,"phaseMs":...,
     *         "totalWafers":...,"firedCount":...}}
     */
    String stateJson() {
        LifecycleCycleClock clock = this.lifecycleClock;
        long phase = 0L;
        if (this.state == State.RUNNING && clock != null) {
            phase = clock.phaseAt(this.clockNow.getAsLong());
        } else if (clock != null && this.state == State.FINISHED) {
            phase = clock.durationOf(clock.count() - 1);
        }
        int total = clock == null ? 0 : clock.count();
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"state\":\"").append(this.state.name()).append('"')
                .append(",\"startedAt\":").append(this.startedAt)
                .append(",\"waferIndex\":").append(this.waferIndex)
                .append(",\"phaseMs\":").append(phase)
                .append(",\"totalWafers\":").append(total)
                .append(",\"firedCount\":").append(this.firedCount)
                .append(",\"hasCycle\":").append(this.config != null)
                .append('}');
        return sb.toString();
    }

    /**
     * 预览用：按「如果现在启动」（锚 = {@code anchorMs}）展开窗口内的片内事件时刻。
     *
     * <p>与实跑同一套片表（同一个 seed → 同一份抖动）：预览看到的竖线就是
     * 实跑会发的时刻（M3 追加 3，预览对齐）。</p>
     *
     * @param config   cycle 段
     * @param seed     配置种子
     * @param anchorMs 假想的启动时刻（草稿预览 = 窗口起点；运行预览 = 实际 startedAt）
     * @param fromMs   窗口起点（不含）
     * @param toMs     窗口终点（含）
     * @return {@code [atMs, ceid]} 列表，升序
     */
    public static List<long[]> simulateFires(CycleConfig config, long seed,
                                      long anchorMs, long fromMs, long toMs) {
        List<long[]> marks = new ArrayList<>();
        if (config == null) {
            return marks;
        }
        CycleClock clock = config.clock(seed);
        for (int wafer = 0; wafer < config.count(); wafer++) {
            long waferStart = anchorMs + clock.startOf(wafer);
            for (CycleConfig.Event event : config.events()) {
                long at = waferStart + event.atMs();
                if (fromMs < at && at <= toMs) {
                    marks.add(new long[] { at, event.ceid() });
                }
            }
        }
        marks.sort(Comparator.comparingLong(mark -> mark[0]));
        return marks;
    }
}
