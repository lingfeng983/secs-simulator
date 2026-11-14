package com.shimizukenta.secssim.core.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class TracePlanTest {

    @Test
    void dsperParsesHhmmss() {
        assertEquals(10_000L, TracePlan.parseDsperMs("000010"));
        assertEquals(60_000L, TracePlan.parseDsperMs("000100"));
        assertEquals(3_600_000L, TracePlan.parseDsperMs("010000"));
        assertEquals(86_400_000L, TracePlan.parseDsperMs("240000"), "小时允许超过 23");
    }

    @Test
    void dsperRejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> TracePlan.parseDsperMs("0000"));
        assertThrows(IllegalArgumentException.class, () -> TracePlan.parseDsperMs("00000a"));
        assertThrows(IllegalArgumentException.class, () -> TracePlan.parseDsperMs("000000"), "全零非法");
        assertThrows(IllegalArgumentException.class, () -> TracePlan.parseDsperMs("000160"), "秒必须 <= 59");
        assertThrows(IllegalArgumentException.class, () -> TracePlan.parseDsperMs(null));
    }

    @Test
    void sampleTimesFollowDsper() {
        assertEquals(1_000L, TracePlan.sampleTimeMs(0L, 1_000L, 1));
        assertEquals(5_000L, TracePlan.sampleTimeMs(0L, 1_000L, 5));
        assertEquals(2_000L, TracePlan.sampleTimeMs(1_000L, 1_000L, 1), "起始时刻要参与计算");
    }

    @Test
    void framesGroupSamplesByRepgsz() {
        assertEquals(3, TracePlan.frameCount(9, 3));
        assertEquals(3, TracePlan.frameCount(7, 3), "7 个样本、每组 3 个 -> 3 条");
        assertEquals(5, TracePlan.frameCount(5, 1));

        assertEquals(3, TracePlan.samplesInFrame(0, 9, 3));
        assertEquals(3, TracePlan.samplesInFrame(2, 9, 3));

        assertEquals(1, TracePlan.samplesInFrame(2, 7, 3), "最后一条只剩 1 个样本");
        assertThrows(IllegalArgumentException.class, () -> TracePlan.samplesInFrame(3, 7, 3));
    }

    @Test
    void specValidatesBounds() {
        assertThrows(IllegalArgumentException.class, () -> new TraceSpec(1, List.of(), 1_000L, 5, 1));
        assertThrows(IllegalArgumentException.class, () -> new TraceSpec(1, List.of(1001), 500L, 5, 1),
                "DSPER 最小 1 秒");
        assertThrows(IllegalArgumentException.class, () -> new TraceSpec(1, List.of(1001), 1_000L, 5, 6),
                "REPGSZ 不能大于 TOTSMP");
        assertThrows(IllegalArgumentException.class, () -> new TraceSpec(1, List.of(1001), 1_000L, 0, 1));

        TraceSpec ok = new TraceSpec(1, List.of(1001, 1002), 1_000L, 9, 3);
        assertEquals(3, ok.frameCount());
        assertEquals(9, ok.sampleCount());
    }
}
