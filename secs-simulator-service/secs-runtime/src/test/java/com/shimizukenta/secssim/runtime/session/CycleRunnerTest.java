package com.shimizukenta.secssim.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.app.config.CycleConfig;
import com.shimizukenta.secssim.core.cycle.LifecycleCycleClock;

/**
 * 工艺周期运行时（2026-11-16 设计 M3）：锚点 = 启动时刻、片推进、跑满
 * FINISHED、全停回 STOPPED、片内事件走共享限流与 cycle 台账身份。
 */
class CycleRunnerTest {

    /** 10s/片、无抖动、3 片；每片 2 条片内事件（3s → CEID 2001，7s → 2002）。 */
    private static CycleConfig config() {
        /* parse 收的是整份配置文档：包一层 "cycle" 键 */
        return CycleConfig.parse(com.shimizukenta.jsonhub.JsonHub.fromJson("""
                {"cycle":{"durationMs":10000,"jitterMs":0,"count":3,
                 "events":[{"atMs":3000,"ceid":2001},{"atMs":7000,"ceid":2002}]}}"""),
                "$");
    }

    private static final class Rig {
        final AtomicLong now = new AtomicLong(0L);
        final AtomicBoolean online = new AtomicBoolean(true);
        final SessionRateGate gate = new SessionRateGate();
        final List<long[]> fires = new ArrayList<>();
        final CycleRunner runner;

        Rig() {
            this.gate.configure(1_000);
            this.runner = new CycleRunner(this.now::get, this.gate, this.online::get,
                    (ceid, atMs) -> this.fires.add(new long[] { atMs, ceid }));
            this.runner.reconfigure(config(), new com.shimizukenta.secssim.core.cycle.LifecycleCycleClock(
                    com.shimizukenta.secssim.core.cycle.CycleClock.of(10_000L, 0L, 3, 7L),
                    com.shimizukenta.secssim.core.cycle.LifecycleCycleClock.State.STOPPED));
        }

        void pump() {
            this.runner.pump();
        }
    }

    @Test
    void anchorIsStartTimeAndWafersFireInOrder() {
        Rig rig = new Rig();
        rig.now.set(50_000L);
        assertTrue(rig.runner.start(50_000L), "有 cycle 段：可启动");

        rig.now.set(53_000L);
        rig.pump();
        assertEquals(1, rig.fires.size(), "第 0 片 3s 事件");
        assertEquals(53_000L, rig.fires.get(0)[0], "排定时刻 = 启动锚点 + 片起点 + atMs");
        assertEquals(2001, rig.fires.get(0)[1]);

        rig.now.set(57_500L);
        rig.pump();
        assertEquals(2, rig.fires.size(), "7s 事件（同一片，不重复）");
        rig.pump();
        assertEquals(2, rig.fires.size(), "已发的片内事件不重发");

        rig.now.set(63_000L);
        rig.pump();
        assertEquals(3, rig.fires.size(), "进入第 1 片：3s 事件再发（第 2 片）");
        assertEquals(63_000L, rig.fires.get(2)[0], "锚点 + 第 1 片起点 + 3s");
    }

    @Test
    void finishAfterCountWafersAndFreezeValues() {
        Rig rig = new Rig();
        LifecycleCycleClock clock = new LifecycleCycleClock(
                com.shimizukenta.secssim.core.cycle.CycleClock.of(10_000L, 0L, 3, 7L),
                com.shimizukenta.secssim.core.cycle.LifecycleCycleClock.State.STOPPED);
        rig.runner.reconfigure(config(), clock);

        rig.now.set(0L);
        rig.runner.start(0L);
        for (long t = 3_000L; t <= 30_000L; t += 3_500L) {
            rig.now.set(t);
            rig.pump();
        }
        assertEquals(6, rig.fires.size(), "3 片 × 2 条全部发完");

        rig.now.set(30_500L);
        rig.pump();
        assertEquals(CycleRunner.State.FINISHED, rig.runner.state(), "跑满 3 片自动 FINISHED");
        assertEquals(10_000L, clock.phaseAt(99_999L), "值冻结在末相位 = 末片整长（审计 P1-1）");
        rig.pump();
        assertEquals(6, rig.fires.size(), "FINISHED 后不再发");
    }

    @Test
    void stopAllSemanticsStopAndRestartFromWaferZero() {
        Rig rig = new Rig();
        rig.now.set(10_000L);
        rig.runner.start(10_000L);
        rig.now.set(13_500L);
        rig.pump();
        assertEquals(1, rig.fires.size());

        /* 全停（§2.1）：回 STOPPED 待手动启动 */
        rig.runner.stop();
        assertEquals(CycleRunner.State.STOPPED, rig.runner.state());
        rig.now.set(20_000L);
        rig.pump();
        assertEquals(1, rig.fires.size(), "停止后片内事件不再发");

        /* 重启从第 0 片重来：锚点 = 新启动时刻 */
        assertTrue(rig.runner.start(20_000L));
        rig.now.set(23_500L);
        rig.pump();
        assertEquals(2, rig.fires.size());
        assertEquals(23_000L, rig.fires.get(1)[0], "新锚点 + 第 0 片 + 3s");
    }

    @Test
    void rateGateRejectsSilentlyWithoutLedger() {
        Rig rig = new Rig();
        rig.gate.configure(1);  /* 每秒 1 条：同窗口内的第二条被拒 */
        /* 每片两条事件挤在 500ms 内（3s / 3.5s）：第一条约到、第二条被限流 */
        rig.runner.reconfigure(CycleConfig.parse(com.shimizukenta.jsonhub.JsonHub.fromJson(
                """
                {"cycle":{"durationMs":10000,"jitterMs":0,"count":3,
                 "events":[{"atMs":3000,"ceid":2001},{"atMs":3500,"ceid":2002}]}}
                """), "$"),
                new com.shimizukenta.secssim.core.cycle.LifecycleCycleClock(
                        com.shimizukenta.secssim.core.cycle.CycleClock.of(10_000L, 0L, 3, 7L),
                        com.shimizukenta.secssim.core.cycle.LifecycleCycleClock.State.STOPPED));
        rig.now.set(0L);
        rig.runner.start(0L);
        rig.now.set(3_500L);
        rig.pump();
        assertEquals(1, rig.fires.size(), "同窗口第二条被限流：丢弃不产生台账行（D8）");
    }

    @Test
    void tailEventAtCycleEndIsNotSwallowedByFinish() {
        /* 评审 HIGH-1：末片最后 ~100ms 里的事件曾被 FINISHED 直接吞掉——
           先发完「排定时刻 <= elapsed」的事件再转 FINISHED */
        CycleConfig tailConfig = CycleConfig.parse(com.shimizukenta.jsonhub.JsonHub.fromJson("""
                {"cycle":{"durationMs":2000,"jitterMs":0,"count":1,
                 "events":[{"atMs":1999,"ceid":2001}]}}"""), "$");
        LifecycleCycleClock clock = new LifecycleCycleClock(
                com.shimizukenta.secssim.core.cycle.CycleClock.of(2_000L, 0L, 1, 7L),
                LifecycleCycleClock.State.STOPPED);
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(0L);
        List<long[]> fires = new ArrayList<>();
        SessionRateGate gate = new SessionRateGate();
        gate.configure(1_000);
        CycleRunner runner = new CycleRunner(now::get, gate, () -> true,
                (ceid, atMs) -> fires.add(new long[] { atMs, ceid }));
        runner.reconfigure(tailConfig, clock);

        runner.start(0L);
        now.set(1_900L);
        runner.pump();  // elapsed=1900：未到 1999
        assertEquals(0, fires.size());

        now.set(2_000L);
        runner.pump();  // elapsed 已 >= endMs：先发尾巴事件，再转 FINISHED
        assertEquals(1, fires.size(), "末片 1999ms 的事件必须发出");
        assertEquals(2001, fires.get(0)[1]);
        assertEquals(CycleRunner.State.FINISHED, runner.state());
        assertEquals(2_000L, clock.phaseAt(999_999L), "冻结在末相位");
    }

    @Test
    void noCyclePassthroughClockFreezesAfter24h() {
        /* 评审 LOW-7（D12）：无 cycle 段的 passthrough 时钟 24h 后 FINISHED 冻结，
           修掉相位无界——runner 停止态下 pump 也要推进这个检查 */
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(0L);
        List<long[]> fires = new ArrayList<>();
        CycleRunner runner = new CycleRunner(now::get, new SessionRateGate(), () -> true,
                (ceid, atMs) -> fires.add(new long[] { atMs, ceid }));
        LifecycleCycleClock clock = new LifecycleCycleClock(
                com.shimizukenta.secssim.core.cycle.CycleClock.passthrough(),
                LifecycleCycleClock.State.RUNNING);
        runner.reconfigure(null, clock);

        runner.pump();  // t=0：仍在跑
        assertEquals(LifecycleCycleClock.State.RUNNING, clock.state());
        assertEquals(86_400_000L, clock.phaseAt(86_400_000L), "24h 内相位无界增长（3.1 行为）");

        now.set(86_400_001L);
        runner.pump();  // t 已过 24h：时钟冻结
        assertEquals(LifecycleCycleClock.State.FINISHED, clock.state());
        assertEquals(86_400_000L, clock.phaseAt(86_400_001L), "冻结在 24h（修掉相位无界）");
        assertTrue(fires.isEmpty(), "没有 cycle 段：不产生片内事件");
    }

    @Test
    void offlineTickDropsSilentlyUntilStopAllArrives() {
        Rig rig = new Rig();
        rig.online.set(false);
        rig.now.set(0L);
        rig.runner.start(0L);
        rig.now.set(53_000L);
        rig.pump();
        assertEquals(0, rig.fires.size(), "链路脱离 SELECTED 的间隙里静默丢一拍（钩子随后全停）");
    }

    @Test
    void noCycleConfigCannotStart() {
        CycleRunner runner = new CycleRunner(() -> 0L, new SessionRateGate(), () -> true,
                (ceid, atMs) -> { });
        runner.reconfigure(null, new com.shimizukenta.secssim.core.cycle.LifecycleCycleClock(
                com.shimizukenta.secssim.core.cycle.CycleClock.passthrough(),
                com.shimizukenta.secssim.core.cycle.LifecycleCycleClock.State.RUNNING));
        assertFalse(runner.start(0L), "没有 cycle 段：无从启动");
        assertFalse(runner.hasCycle());
        assertTrue(runner.stateJson().contains("\"hasCycle\":false"));
    }

    @Test
    void stateJsonCarriesRuntimeFields() {
        Rig rig = new Rig();
        rig.now.set(0L);
        rig.runner.start(0L);
        rig.now.set(17_500L);
        rig.pump();

        String json = rig.runner.stateJson();
        assertTrue(json.contains("\"state\":\"RUNNING\""), json);
        assertTrue(json.contains("\"startedAt\":0"), json);
        assertTrue(json.contains("\"waferIndex\":1"), json);
        assertTrue(json.contains("\"phaseMs\":7500"), json);
        assertTrue(json.contains("\"totalWafers\":3"), json);
        assertTrue(json.contains("\"firedCount\":4"), json);

        AtomicReference<String> captured = new AtomicReference<>();
        // stateJson 是纯读：再确认 FINISHED 形态
        rig.now.set(99_999L);
        rig.pump();
        captured.set(rig.runner.stateJson());
        assertTrue(captured.get().contains("\"state\":\"FINISHED\""), captured.get());
    }
}
