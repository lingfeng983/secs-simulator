package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Optional;

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
 * S1F11 / S1F12：主机枚举状态变量的名字与单位。
 *
 * <p>自动发现型主机的流程是"先问有哪些变量，再决定采哪些"，
 * 所以这两个报文通了，主机才可能自己配置采集项。
 * </p>
 */
class SessionSvNamelistTest {

    @Test
    void namelistReturnsNamesAndUnits() throws Exception {
        try (Fixture f = new Fixture()) {
            Secs2Builder b = Secs2.getBuilder();
            Secs2 rows = f.request(b.list(b.uint4(1001L), b.uint4(1090L), b.uint4(9999L)));

            assertEquals(3, rows.size(), "返回行数必须与请求一致");

            assertEquals(1001, Secs2Read.asInt(rows.get(0).get(0)));
            assertEquals("CH1_TEMP_PV", rows.get(0).get(1).getAscii(), "单元名要前缀进变量名");
            assertEquals("C", rows.get(0).get(2).getAscii(), "单位取 SVID 定义");

            assertEquals(1090, Secs2Read.asInt(rows.get(1).get(0)));
            assertEquals("CH1_UNIT_ID", rows.get(1).get(1).getAscii());
            assertEquals("", rows.get(1).get(2).getAscii(), "没有单位就是空串");

            assertEquals(9999, Secs2Read.asInt(rows.get(2).get(0)));
            assertEquals("UNKNOWN", rows.get(2).get(1).getAscii(),
                    "未知 SVID 用 UNKNOWN 占位，行不能少——空名会让现场无法区分"
                            + "'变量没命名'和'设备不认识这个 SVID'");
        }
    }

    @Test
    void emptyRequestEnumeratesEverything() throws Exception {
        try (Fixture f = new Fixture()) {
            Secs2 rows = f.request(Secs2.getBuilder().list());

            assertEquals(10, rows.size(), "两腔体 × 5 个 SVID");
            assertEquals(1001, Secs2Read.asInt(rows.get(0).get(0)), "按 SVID 升序");
            assertEquals(1190, Secs2Read.asInt(rows.get(rows.size() - 1).get(0)));
        }
    }

    /** 会话 + 客户端夹具。 */
    private static final class Fixture implements AutoCloseable {

        private final HsmsSessionRuntime session;
        private final SecsCommunicator client;

        Fixture() throws Exception {
            int port = freePort();
            EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
            CompilationResult compiled = EquipmentCompiler.compile(demo);

            SessionSpec spec = new SessionSpec("sv", true, "127.0.0.1", port, 10, 1L, demo);
            this.session = new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));
            this.session.open();

            HsmsSsCommunicatorConfig cfg = new HsmsSsCommunicatorConfig();
            cfg.protocol(HsmsSsProtocol.ACTIVE);
            cfg.socketAddress(new InetSocketAddress("127.0.0.1", port));
            cfg.deviceId(10);
            this.client = HsmsSsCommunicator.open(cfg);
            this.client.openAndWaitUntilCommunicating();
            SessionTestSupport.awaitPeer(this.session);
        }

        Secs2 request(Secs2 body) throws Exception {
            Optional<SecsMessage> reply = this.client.send(1, 11, true, body);

            assertTrue(reply.isPresent(), "S1F11 没有应答");
            assertEquals(12, reply.get().getFunction(), "S1F11 必须回 S1F12");
            return reply.get().secs2();
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
