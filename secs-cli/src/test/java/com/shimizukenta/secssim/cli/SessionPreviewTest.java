package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;

import org.junit.jupiter.api.Test;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.ManualEngineClock;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.runtime.session.DemoEquipment;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;

/**
 * 曲线预览：分桶 min/max/avg。
 *
 * <p>分桶而不是逐点返回，是因为 400 个 SVID × 几十万点会把浏览器打爆；
 * 而 min/max 双值能保留尖峰（故障注入必须看得见）。
 * </p>
 */
class SessionPreviewTest {

    @Test
    void previewReturnsBucketedSeriesForEachSvid() throws Exception {
        int port = freePort();
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);

        SessionSpec spec = new SessionSpec("pv", true, "127.0.0.1", port, 10, 7L, demo);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));

        JsonHub root = JsonHub.fromJson(session.previewJson(0L, 60_000L, 60));

        assertEquals(60, root.get("buckets").intValue());
        assertEquals(1000L, root.get("bucketMs").longValue(), "60s / 60 桶");

        JsonHub series = root.get("series");
        assertEquals(10, series.length(), "两腔体 × 5 个 SVID");

        JsonHub temperature = null;
        for (int i = 0; i < series.length(); i++) {
            if (series.get(i).get("svid").intValue() == 1001) {
                temperature = series.get(i);
            }
        }

        assertTrue(temperature != null, "缺少 SVID 1001 的序列");
        assertEquals(60, temperature.get("min").length());
        assertEquals(60, temperature.get("max").length());
        assertEquals(60, temperature.get("avg").length());

        for (int i = 0; i < 60; i++) {
            double min = temperature.get("min").get(i).doubleValue();
            double max = temperature.get("max").get(i).doubleValue();
            assertTrue(min <= max, "第 " + i + " 桶 min > max");
            assertTrue(min >= 0.0 && max <= 400.0, "越界: " + min + ".." + max);
        }
    }

    @Test
    void previewRejectsAbsurdBucketCounts() throws Exception {
        int port = freePort();
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);

        SessionSpec spec = new SessionSpec("pv2", true, "127.0.0.1", port, 10, 7L, demo);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));

        JsonHub root = JsonHub.fromJson(session.previewJson(0L, 60_000L, 100_000));
        assertEquals(500, root.get("buckets").intValue(), "桶数上限 500");
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
