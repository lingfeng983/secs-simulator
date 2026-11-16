package com.shimizukenta.secssim.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.core.curve.Rng;

/**
 * 带锚点的触发求值（2026-11-16 计划生命周期重设计 D2）。
 *
 * <p>锚点 = 计划启动时刻：At 在 {@code anchor + atMs} 触发、Periodic 第 k 次
 * 在 {@code anchor + k × period}（启动即发的那条由运行时直接触发，不进区间求值）。
 * {@code anchor = 0} 时与旧语义完全一致。</p>
 */
class TriggerAnchorTest {

    private static List<Long> fires(Trigger trigger, long from, long to, long anchor) {
        return trigger.fireTimes(from, to, new Rng(7L), "p", anchor);
    }

    @Test
    void atFiresRelativeToAnchor() {
        Trigger.At at = new Trigger.At(5_000L);
        /* 启动后 5s 触发：锚 10_000 → 触发点 15_000 */
        assertEquals(List.of(15_000L), fires(at, 14_999L, 15_000L, 10_000L));
        assertTrue(fires(at, 0L, 14_999L, 10_000L).isEmpty(), "窗口内未到不触发");
        assertTrue(fires(at, 15_000L, 99_999L, 10_000L).isEmpty(), "过窗不补");
    }

    @Test
    void periodicKthFireIsAnchorPlusKPeriod() {
        Trigger.Periodic periodic = new Trigger.Periodic(1_000L, 0L);
        /* k≥1 在 anchor + k×period；k=0（启动即发）不走区间求值 */
        assertEquals(List.of(11_000L, 12_000L),
                fires(periodic, 10_001L, 12_000L, 10_000L));
        assertTrue(fires(periodic, 10_000L, 10_999L, 10_000L).isEmpty(),
                "anchor 本身（k=0）不由区间求值触发");
        /* anchor = 0 时与旧语义一致 */
        assertEquals(List.of(1_000L, 2_000L), fires(periodic, 1L, 2_000L, 0L));
    }

    @Test
    void anchorZeroMatchesLegacyWindow() {
        Trigger.At at = new Trigger.At(3_000L);
        assertEquals(at.fireTimes(1L, 5_000L, new Rng(7L), "p"),
                fires(at, 1L, 5_000L, 0L), "anchor=0 与旧三参一致");
    }

    @Test
    void conditionAndChainIgnoreAnchor() {
        assertTrue(fires(new Trigger.AfterEvent("a", 100L), 0L, 9_999L, 5_000L).isEmpty());
    }
}
