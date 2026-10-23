package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.hsmsss.HsmsSsProtocol;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Builder;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.ManualEngineClock;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.runtime.session.DemoEquipment;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;
import com.shimizukenta.secssim.runtime.session.Secs2Read;

/**
 * 主机动态定义报告：S2F33 / S2F35 / S2F37 真链路。
 *
 * <p>验证 ADR 0006 的两条关键语义：
 * 主机改过的报告以主机为准，且**不注入 UNIT_ID**；主机禁用事件后不再上报、也不缓存。
 * </p>
 */
class SessionDynamicReportTest {

    @Test
    void hostDefinedReportOverridesLocalAndSkipsUnitIdInjection() throws Exception {
        try (Fixture f = new Fixture()) {
            /* 本地 3001 = [UNIT_ID, TEMP_PV, PRESS]（3 个值）；主机改成只有 TEMP_PV */
            assertEquals(0, f.defineReport(3001, List.of(1001)), "DRACK 应为 OK");

            f.session.emitCeid(2001);
            Secs2 report = f.nextS6F11().get(2).get(0);

            assertEquals(3001, Secs2Read.asInt(report.get(0)));
            assertEquals(1, report.get(1).size(), "主机定义优先：既没有 PRESS，也不注入 UNIT_ID");
        }
    }

    @Test
    void hostDefinedLinkChangesWhichReportsAreSent() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(0, f.defineReport(3100, List.of(1090)), "DRACK=0");
            assertEquals(0, f.linkEvent(2001, List.of(3100)), "LRACK=0");

            f.session.emitCeid(2001);
            Secs2 reports = f.nextS6F11().get(2);

            assertEquals(1, reports.size(), "主机链接只挂了一个报告");
            assertEquals(3100, Secs2Read.asInt(reports.get(0).get(0)));
            assertEquals(1, Secs2Read.asInt(reports.get(0).get(1).get(0)), "取到的是 UNIT_ID=1");
        }
    }

    @Test
    void disablingEventStopsReportingWithoutBacklog() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(0, f.setEnabled(false, List.of(2001)), "ERACK=0");

            f.session.emitCeid(2001);
            assertTrue(f.drain().isEmpty(), "被禁用的事件不得上报");
            assertEquals(1L, f.session.counters().get("skipped"), "应计入 skipped，而不是静默丢弃");

            f.session.emitCeid(2101);
            assertEquals(1, f.drain().size(), "同一设备的另一个单元不受影响，仍然上报");

            assertEquals(0, f.setEnabled(true, List.of(2001)), "重新启用");
            f.session.emitCeid(2001);
            assertTrue(!f.drain().isEmpty(), "重新启用后应恢复上报");
        }
    }

    @Test
    void clearingHostDefinitionsFallsBackToLocalConfig() throws Exception {
        try (Fixture f = new Fixture()) {
            f.defineReport(3001, List.of(1001));
            f.session.emitCeid(2001);
            assertEquals(1, f.nextS6F11().get(2).get(0).get(1).size(), "主机定义生效");

            assertEquals(0, f.defineReport(-1, List.of()), "用空报告列表清空全部定义");

            f.session.emitCeid(2001);
            assertEquals(3, f.nextS6F11().get(2).get(0).get(1).size(), "清空后回落到本地配置（含 UNIT_ID）");
        }
    }

    /**
     * 目录必须反映<b>实际发出去的那一份</b>，而不是只把本地配置抄一遍。
     *
     * <p>这是 ADR-0006 留在"后果"里的那条：界面要能看出报告来自本地还是主机。
     * 关键在主机<b>新建</b>本地没有的 RPTID —— 它一样会被发出去，
     * 只列本地配置的话，现场只能对着报文猜设备"哪来的这个报告"。
     * </p>
     */
    @Test
    void catalogReflectsHostOverlayRatherThanJustLocalConfig() throws Exception {
        try (Fixture f = new Fixture()) {
            String before = f.session.catalogJson();
            assertTrue(before.contains("\"hostReports\":0"), before);
            assertTrue(before.contains("{\"rptid\":3001,\"owner\":\"CH1\",\"slot\":\"STATUS\","
                    + "\"svids\":[1090,1001,1003],\"source\":\"local\"}"),
                    "未下发时 3001 是本地定义: " + before);

            /* 主机新建一个本地没有的 RPTID，并把它挂到 2001 */
            assertEquals(0, f.defineReport(5000, List.of(1001)), "DRACK=0");
            assertEquals(0, f.linkEvent(2001, List.of(5000)), "LRACK=0");

            String after = f.session.catalogJson();
            assertTrue(after.contains("{\"rptid\":5000,\"owner\":\"\",\"slot\":\"\","
                    + "\"svids\":[1001],\"source\":\"host\"}"),
                    "主机新建的报告必须出现在目录里: " + after);
            assertTrue(after.contains("{\"ceid\":2001,\"owner\":\"CH1\",\"slot\":\"PROCESS_START\","
                    + "\"reports\":[5000],\"source\":\"host\",\"enabled\":true}"),
                    "CEID 的有效报告应被主机链接覆盖: " + after);
            assertTrue(after.contains("\"hostReports\":1"), after);
            assertTrue(after.contains("\"hostLinks\":1"), after);

            /* 清空后要回落到本地，否则目录会一直说谎 */
            assertEquals(0, f.defineReport(-1, List.of()), "清空报告定义");
            assertEquals(0, f.linkEvent(-1, List.of()), "清空事件链接");

            String cleared = f.session.catalogJson();
            assertTrue(cleared.contains("\"reports\":[3001],\"source\":\"local\""), cleared);
            assertTrue(cleared.contains("\"hostReports\":0"), cleared);
        }
    }

    /** S2F37 禁用事件后，目录要标出来——否则界面上的"可上报"是假的。 */
    @Test
    void catalogMarksDisabledEvents() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(0, f.setEnabled(false, List.of(2001)), "ERACK=0");

            String json = f.session.catalogJson();
            assertTrue(json.contains("{\"ceid\":2001,\"owner\":\"CH1\",\"slot\":\"PROCESS_START\","
                    + "\"reports\":[3001],\"source\":\"local\",\"enabled\":false}"), json);
            assertTrue(json.contains("{\"ceid\":2101,\"owner\":\"CH2\",\"slot\":\"PROCESS_START\","
                    + "\"reports\":[3101],\"source\":\"local\",\"enabled\":true}"),
                    "只禁用了 2001，另一个单元不受影响: " + json);
        }
    }

    /**
     * S2F37 的 CEED 按 {@code B[1]}（0/1）发也要收。
     *
     * <p>E30 定义是 BOOLEAN，但现场确有主机用 B。严格实现会抛类型异常 → ERACK=1，
     * 主机以为设备不支持事件开关。</p>
     */
    @Test
    void acceptsBinaryCeedInS2F37() throws Exception {
        try (Fixture f = new Fixture()) {
            Secs2Builder b = Secs2.getBuilder();
            assertEquals(0, f.ackRaw(b.list(b.binary((byte) 0), b.list(b.uint4(2001))), 2, 37, 38),
                    "B[1] 形式的 CEED 应 ERACK=0");

            f.session.emitCeid(2001);
            assertTrue(f.drain().isEmpty(), "CEED=0 应禁用该事件");
        }
    }

    /** 标准形状与省略 DATAID 的非标形状都要能吃下（P0-1 回归）。 */
    @Test
    void acceptsBothStandardAndLegacyS2F33Shapes() throws Exception {
        try (Fixture f = new Fixture()) {
            int legacyCode = f.defineReportLegacyShape(3001, List.of(1001));
            assertEquals(0, legacyCode, "非标形状仍应 DRACK=0, lastError=" + f.session.lastError());

            f.session.emitCeid(2001);
            assertEquals(1, f.nextS6F11().get(2).get(0).get(1).size(), "非标形状的定义已生效");

            assertEquals(0, f.defineReport(3001, List.of(1001, 1003)), "标准形状 DRACK=0");

            f.session.emitCeid(2001);
            assertEquals(2, f.nextS6F11().get(2).get(0).get(1).size(), "标准形状的定义已覆盖旧定义");
        }
    }

    /** 报文体是垃圾时回 DRACK=1，而不是抛出去让主机等到 T3。 */
    @Test
    void malformedS2F33IsRejectedWithDrackOne() throws Exception {
        try (Fixture f = new Fixture()) {
            Secs2Builder b = Secs2.getBuilder();
            assertEquals(1, f.ackRaw(b.list(b.uint4(0), b.uint4(1)), 2, 33, 34),
                    "第二个元素不是列表 → DRACK=1");
            assertEquals(1, f.ackRaw(b.uint4(7), 2, 33, 34), "整体不是列表 → DRACK=1");
        }
    }

    /** 会话 + 客户端夹具。 */
    private static final class Fixture implements AutoCloseable {

        private final HsmsSessionRuntime session;
        private final SecsCommunicator client;
        private final BlockingQueue<SecsMessage> s6f11 = new LinkedBlockingQueue<>();

        Fixture() throws Exception {
            int port = freePort();
            EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
            CompilationResult compiled = EquipmentCompiler.compile(demo);

            SessionSpec spec = new SessionSpec("dyn", true, "127.0.0.1", port, 10, 1L, demo);
            this.session = new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));
            this.session.open();

            HsmsSsCommunicatorConfig cfg = new HsmsSsCommunicatorConfig();
            cfg.protocol(HsmsSsProtocol.ACTIVE);
            cfg.socketAddress(new InetSocketAddress("127.0.0.1", port));
            cfg.deviceId(10);
            this.client = HsmsSsCommunicator.open(cfg);
            this.client.addSecsMessageReceiveListener(message -> {
                if (message.getStream() == 6 && message.getFunction() == 11) {
                    this.s6f11.add(message);
                }
            });
            this.client.openAndWaitUntilCommunicating();
            SessionTestSupport.awaitPeer(this.session);
        }

        int defineReport(int rptid, List<Integer> vids) throws Exception {
            Secs2Builder b = Secs2.getBuilder();
            List<Secs2> reports = new ArrayList<>();

            if (rptid > 0) {
                reports.add(b.list(b.uint4(rptid), b.list(uints(vids, b))));
            }

            /* E5 标准形状：L[2]{DATAID, L[n]{L[2]{RPTID, L[m] VID}}} */
            return this.ack(b.list(b.uint4(0), b.list(reports)), 2, 33, 34);
        }

        /** 省略 DATAID 的非标形状 {@code L{L{...}, L{}}}：现场有主机这么发，必须继续支持。 */
        int defineReportLegacyShape(int rptid, List<Integer> vids) throws Exception {
            Secs2Builder b = Secs2.getBuilder();
            List<Secs2> reports = new ArrayList<>();
            reports.add(b.list(b.uint4(rptid), b.list(uints(vids, b))));

            return this.ack(b.list(b.list(reports), b.list()), 2, 33, 34);
        }

        int linkEvent(int ceid, List<Integer> rptids) throws Exception {
            Secs2Builder b = Secs2.getBuilder();
            List<Secs2> links = new ArrayList<>();

            if (ceid > 0) {
                links.add(b.list(b.uint4(ceid), b.list(uints(rptids, b))));
            }

            return this.ack(b.list(b.uint4(0), b.list(links)), 2, 35, 36);
        }

        int setEnabled(boolean enabled, List<Integer> ceids) throws Exception {
            Secs2Builder b = Secs2.getBuilder();
            List<Secs2> list = uints(ceids, b);

            return this.ack(b.list(b.bool(enabled), b.list(list)), 2, 37, 38);
        }

        private static List<Secs2> uints(List<Integer> values, Secs2Builder b) {
            List<Secs2> list = new ArrayList<>(values.size());
            for (int value : values) {
                list.add(b.uint4(value));
            }
            return list;
        }

        int ackRaw(Secs2 body, int stream, int function, int replyFunction) throws Exception {
            return this.ack(body, stream, function, replyFunction);
        }

        private int ack(Secs2 body, int stream, int function, int replyFunction) throws Exception {
            Optional<SecsMessage> reply = this.client.send(stream, function, true, body);
            assertTrue(reply.isPresent(), "S" + stream + "F" + function + " 没有应答");
            assertEquals(replyFunction, reply.get().getFunction());
            return Secs2Read.asByte(reply.get().secs2());
        }

        Secs2 nextS6F11() throws Exception {
            SecsMessage message = this.s6f11.poll(2L, TimeUnit.SECONDS);
            assertTrue(message != null, "没有收到 S6F11");
            return message.secs2();
        }

        List<SecsMessage> drain() throws InterruptedException {
            List<SecsMessage> messages = new ArrayList<>();
            SecsMessage message;
            while ((message = this.s6f11.poll(200L, TimeUnit.MILLISECONDS)) != null) {
                messages.add(message);
            }
            return messages;
        }

        @Override
        public void close() {
            try {
                this.client.close();
            } catch (Exception ignore) {
                /* 测试收尾 */
            }
            this.session.close();
        }

        private static int freePort() throws Exception {
            try (ServerSocket socket = new ServerSocket(0)) {
                socket.setReuseAddress(true);
                return socket.getLocalPort();
            }
        }
    }
}
