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
            Secs2 report = f.nextS6F11().get(1).get(0);

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
            Secs2 reports = f.nextS6F11().get(1);

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
            assertEquals(1, f.nextS6F11().get(1).get(0).get(1).size(), "主机定义生效");

            assertEquals(0, f.defineReport(-1, List.of()), "用空报告列表清空全部定义");

            f.session.emitCeid(2001);
            assertEquals(3, f.nextS6F11().get(1).get(0).get(1).size(), "清空后回落到本地配置（含 UNIT_ID）");
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
        }

        int defineReport(int rptid, List<Integer> vids) throws Exception {
            Secs2Builder b = Secs2.getBuilder();
            List<Secs2> reports = new ArrayList<>();

            if (rptid > 0) {
                reports.add(b.list(b.uint4(rptid), b.list(uints(vids, b))));
            }

            return this.ack(b.list(b.list(reports), b.list()), 2, 33, 34);
        }

        int linkEvent(int ceid, List<Integer> rptids) throws Exception {
            Secs2Builder b = Secs2.getBuilder();
            List<Secs2> links = new ArrayList<>();

            if (ceid > 0) {
                links.add(b.list(b.uint4(ceid), b.list(uints(rptids, b))));
            }

            return this.ack(b.list(b.list(), b.list(links)), 2, 35, 36);
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
