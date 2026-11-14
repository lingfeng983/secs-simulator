package com.shimizukenta.secssim.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.core.value.ValueSource;

/**
 * S2F23 Trace 的两道上界：样本总数与单次 poll 的补齐量。
 *
 * <p>没有上界时，一个极大 TOTSMP（或笔记本休眠后墙钟跳变）会让采样线程在持锁
 * 状态下构造海量 S6F1，向主机突发并长时间占住事件线程。超过补齐上限时应快进
 * 到当前时刻（丢弃过期样本），而不是把积压一次性补齐。</p>
 */
class TraceManagerTest {

    /** 攒够一批才发：让 poll 产生的帧数直接反映"补算了多少批"。 */
    private static final int REPGSZ = 1;

    private static ResolvedEquipment model() {
        EquipmentSpec demo = DemoEquipment.twoChambers("TRACE", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);
        assertTrue(compiled.ok(), () -> "演示设备编译失败: " + compiled.errors());
        return compiled.equipment();
    }

    private static ValueSource constantValues(ResolvedEquipment model) {
        return new ValueSource() {
            @Override
            public OptionalDouble valueAt(int svid, long tMs) {
                return OptionalDouble.of(1.0);
            }

            @Override
            public Set<Integer> svids() {
                return model.svids().keySet();
            }
        };
    }

    private static int firstSvid(ResolvedEquipment model) {
        return model.svids().keySet().iterator().next();
    }

    private static TraceManager manager(ResolvedEquipment model) {
        return new TraceManager(model, constantValues(model), Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void rejectsTotalSamplesBeyondLimit() {
        ResolvedEquipment model = model();
        TraceManager traces = manager(model);

        int code = traces.initialize(1, List.of(firstSvid(model)), 1_000L,
                TraceManager.MAX_TOTAL_SAMPLES + 1, 1, 0L);

        assertEquals(TraceManager.TIAACK_INVALID_PERIOD, code, "过大的 TOTSMP 必须被拒绝");
        assertEquals(0, traces.activeCount(), "被拒绝的 Trace 不能留在表里");
    }

    @Test
    void catchUpIsBoundedPerPoll() {
        ResolvedEquipment model = model();
        TraceManager traces = manager(model);

        assertEquals(TraceManager.TIAACK_OK, traces.initialize(1, List.of(firstSvid(model)),
                1_000L, 1_000_000, REPGSZ, 0L));

        /* 50s 的墙钟跳变 = 50_000 个过期样本，远超单次补齐上限 */
        long nowMs = 50_000_000L;
        List<TraceManager.Frame> first = traces.poll(nowMs);

        assertTrue(first.size() > 0, "快进前仍应产出已补算的部分帧");
        assertTrue(first.size() < 50_000, "积压 5 万个样本不能一次补齐，实际 " + first.size());

        assertTrue(traces.poll(nowMs).isEmpty(), "快进之后同一时刻不应再有积压样本");
        assertEquals(1, traces.activeCount(), "快进不应结束整条 Trace");
    }

    @Test
    void acceptsTotalSamplesAtLimit() {
        ResolvedEquipment model = model();
        TraceManager traces = manager(model);

        int code = traces.initialize(1, List.of(firstSvid(model)), 1_000L,
                TraceManager.MAX_TOTAL_SAMPLES, 1, 0L);

        assertEquals(TraceManager.TIAACK_OK, code, "恰好等于上限的 TOTSMP 必须被接受");
        assertEquals(1, traces.activeCount(), "被接受的 Trace 应留在表里");
    }

    @Test
    void catchUpAtLimitIsNotFastForwarded() {
        ResolvedEquipment model = model();
        TraceManager traces = manager(model);

        assertEquals(TraceManager.TIAACK_OK, traces.initialize(1, List.of(firstSvid(model)),
                1_000L, TraceManager.MAX_TOTAL_SAMPLES, 10_000, 0L));

        /* 恰好 10_000 个到期样本：整批必须真正补算，不能触发快进丢样本 */
        List<TraceManager.Frame> frames = traces.poll(10_000_000L);

        assertEquals(1, frames.size(), "10_000 个样本应恰好组成一条 S6F1");
        assertEquals(10_000, frames.get(0).sampleCount(), "补齐量恰好等于上限时应补齐");
        assertEquals(10_000, frames.get(0).smpln(), "SMPLN 应是最后一个样本号");
        assertTrue(traces.poll(10_000_000L).isEmpty(), "补齐后同一时刻不应再有积压样本");
        assertEquals(1, traces.activeCount(), "未到 TOTSMP 时 Trace 应继续");
    }

    @Test
    void fastForwardPastEndEndsTrace() {
        ResolvedEquipment model = model();
        TraceManager traces = manager(model);

        /* 比补齐上限多一个样本：第 10_001 个样本触发快进，而它恰好越过末尾 */
        int totsmp = 10_001;
        assertEquals(TraceManager.TIAACK_OK, traces.initialize(1, List.of(firstSvid(model)),
                1_000L, totsmp, 1, 0L));

        List<TraceManager.Frame> frames = traces.poll(1_000_000_000L);

        assertEquals(10_000, frames.size(), "快进前只补到单次上限");
        assertEquals(10_000, frames.get(frames.size() - 1).smpln(),
                "快进越过的末尾样本不能再发出");
        assertEquals(0, traces.activeCount(), "快进越过末尾后 Trace 应结束");
        assertTrue(traces.poll(1_000_000_000L).isEmpty(), "已结束的 Trace 不再产出");
    }

    @Test
    void fastForwardPastEndKeepsUnfinishedGroup() {
        ResolvedEquipment model = model();
        TraceManager traces = manager(model);

        /* repgsz=300：第 10_001 个样本触发快进时，pending 里还压着 9_901..10_000 一组。
           越过末尾时必须把这一组当末帧发出，否则主机永远等不到末条 S6F1 */
        int totsmp = 10_001;
        assertEquals(TraceManager.TIAACK_OK, traces.initialize(1, List.of(firstSvid(model)),
                1_000L, totsmp, 300, 0L));

        List<TraceManager.Frame> frames = traces.poll(1_000_000_000L);

        assertEquals(34, frames.size(), "33 条整组 + 快进前补发的末尾帧");
        TraceManager.Frame last = frames.get(frames.size() - 1);
        assertEquals(100, last.sampleCount(), "末尾帧是 pending 里那 100 个已补算样本");
        assertEquals(10_000, last.smpln(), "末帧样本号是最后算出的样本（越过末尾的那个不补）");
        assertEquals(0, traces.activeCount(), "越尾后 Trace 结束");
    }

    @Test
    void normalPollStillEmitsSamples() {
        ResolvedEquipment model = model();
        TraceManager traces = manager(model);

        assertEquals(TraceManager.TIAACK_OK, traces.initialize(1, List.of(firstSvid(model)),
                1_000L, 2, 1, 0L));

        List<TraceManager.Frame> frames = traces.poll(10_000L);

        assertEquals(2, frames.size(), "两个样本各成一帧");
        assertEquals(1, frames.get(0).sampleCount());
        assertEquals(2, frames.get(1).smpln(), "SMPLN 是最后一个样本号");
        assertEquals(0, traces.activeCount(), "TOTSMP 用尽后 Trace 自动结束");
    }
}
