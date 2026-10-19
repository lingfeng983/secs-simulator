package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.ManualEngineClock;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.core.domain.IdSpacePolicy;
import com.shimizukenta.secssim.core.domain.SvidDef;
import com.shimizukenta.secssim.core.domain.UnitId;
import com.shimizukenta.secssim.core.domain.UnitSpec;
import com.shimizukenta.secssim.core.domain.ValueType;
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
        assertEquals(10, root.get("svidTotal").intValue(), "预览要报告总 SVID 数，截断不能悄悄发生");
        assertFalse(root.get("truncated").booleanValue(), "10 条没有超过 32 条上限");

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
    void binaryPreviewMatchesJsonHeaderAndSeries() throws Exception {
        int port = freePort();
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);
        SessionSpec spec = new SessionSpec("pvb", true, "127.0.0.1", port, 10, 7L, demo);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));

        JsonHub root = JsonHub.fromJson(session.previewJson(0L, 60_000L, 60));
        JsonHub temperature = null;
        for (int i = 0; i < root.get("series").length(); i++) {
            if (root.get("series").get(i).get("svid").intValue() == 1001) {
                temperature = root.get("series").get(i);
            }
        }
        assertTrue(temperature != null);

        byte[] binary = session.previewBinary(0L, 60_000L, 60);
        assertEquals(48 + 10 * (4 + 3 * 60 * 8), binary.length, "固定头 + 10 条序列");

        ByteBuffer buffer = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals((byte) 'S', buffer.get());
        assertEquals((byte) 'C', buffer.get());
        assertEquals((byte) 'S', buffer.get());
        assertEquals((byte) 'P', buffer.get());
        assertEquals(1, buffer.getShort(), "格式版本");
        assertEquals(0, buffer.getShort(), "flags 保留");
        assertEquals(0L, buffer.getLong());
        assertEquals(60_000L, buffer.getLong());
        assertEquals(60, buffer.getInt());
        assertEquals(1_000L, buffer.getLong(), "bucketMs");
        assertEquals(10, buffer.getInt(), "svidTotal");
        assertEquals(10, buffer.getInt(), "seriesCount");
        assertEquals(0, buffer.getInt(), "reserved");

        assertEquals(1001, buffer.getInt(), "按 SVID 升序");
        for (int i = 0; i < 60; i++) {
            assertEquals(temperature.get("min").get(i).doubleValue(), buffer.getDouble(), 1e-3,
                    "二进制 min 与 JSON 一致（JSON 保留三位小数）");
        }
        for (int i = 0; i < 60; i++) {
            assertEquals(temperature.get("max").get(i).doubleValue(), buffer.getDouble(), 1e-3);
        }
        for (int i = 0; i < 60; i++) {
            assertEquals(temperature.get("avg").get(i).doubleValue(), buffer.getDouble(), 1e-3);
        }
    }

    @Test
    void previewReportsTruncationInsteadOfSilentlyDroppingSeries() throws Exception {
        int port = freePort();
        List<UnitSpec> units = new ArrayList<>();
        for (int unit = 1; unit <= 7; unit++) {
            units.add(new UnitSpec(UnitId.of(unit), "CH" + unit,
                    List.of(
                            new SvidDef(1, "TEMP_PV", ValueType.F4, "C", 2, 0.0, 400.0),
                            new SvidDef(2, "TEMP_SV", ValueType.F4, "C", 2, 0.0, 400.0),
                            new SvidDef(3, "PRESS", ValueType.F4, "Torr", 3, 0.0, 10.0),
                            new SvidDef(4, "HEATER", ValueType.BOOLEAN, "", 0, 0.0, 1.0),
                            new SvidDef(5, "FLOW", ValueType.F4, "sccm", 2, 0.0, 100.0)),
                    List.of(), List.of(), false));
        }
        EquipmentSpec equipment = new EquipmentSpec("BIG-01", 11, units,
                List.of(), List.of(), IdSpacePolicy.defaults());
        CompilationResult compiled = EquipmentCompiler.compile(equipment);

        SessionSpec spec = new SessionSpec("pv3", true, "127.0.0.1", port, 11, 7L, equipment);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));

        JsonHub root = JsonHub.fromJson(session.previewJson(0L, 60_000L, 60));

        assertEquals(35, root.get("svidTotal").intValue(), "7 个单元 × 5 个 SVID");
        assertTrue(root.get("truncated").booleanValue(), "超过 32 条必须显式标截断");
        assertEquals(32, root.get("series").length(), "预览最多返回 32 条序列");

        ByteBuffer binary = ByteBuffer.wrap(session.previewBinary(0L, 60_000L, 60))
                .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(35, binary.getInt(36), "二进制头也要报告 svidTotal");
        assertEquals(32, binary.getInt(40), "二进制头只含有 32 条序列");
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
