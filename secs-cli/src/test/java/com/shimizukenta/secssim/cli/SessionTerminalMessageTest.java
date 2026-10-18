package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
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
 * S10F3 终端消息：主机发文本，设备保存并回 S10F4。
 */
class SessionTerminalMessageTest {

    @Test
    void terminalMessageIsStoredAndAcknowledged() throws Exception {
        try (Fixture f = new Fixture()) {
            Secs2Builder b = Secs2.getBuilder();
            Optional<SecsMessage> reply = f.client.send(10, 3, true,
                    b.list(b.binary((byte) 1), b.ascii("LINE READY")));

            assertTrue(reply.isPresent(), "S10F3 没有应答");
            assertEquals(10, reply.get().getStream());
            assertEquals(4, reply.get().getFunction());
            assertEquals(0, Secs2Read.asByte(reply.get().secs2()), "ACKC10 = AcceptedForDisplay");

            List<HsmsSessionRuntime.TerminalMessage> messages = f.session.terminalMessages();
            assertEquals(1, messages.size());
            assertEquals(1, messages.get(0).tid());
            assertEquals("LINE READY", messages.get(0).text());
            assertTrue(f.session.terminalMessagesJson().contains("LINE READY"),
                    f.session.terminalMessagesJson());
            assertEquals(1L, f.session.counters().get("terminalMessages"));
        }
    }

    @Test
    void malformedTerminalMessageIsRejectedButLinkStaysAlive() throws Exception {
        try (Fixture f = new Fixture()) {
            Optional<SecsMessage> reply = f.client.send(10, 3, true, Secs2.getBuilder().list());

            assertTrue(reply.isPresent(), "格式非法也要回 S10F4");
            assertEquals(1, Secs2Read.asByte(reply.get().secs2()),
                    "ACKC10 = MessageWillNotBeDisplayed");
            assertTrue(f.session.terminalMessages().isEmpty());

            Optional<SecsMessage> linktest = f.client.send(1, 1, true);
            assertTrue(linktest.isPresent(), "收到非法 S10F3 后链路仍可用");
        }
    }

    private static final class Fixture implements AutoCloseable {

        private final HsmsSessionRuntime session;
        private final SecsCommunicator client;

        Fixture() throws Exception {
            int port = freePort();
            EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
            CompilationResult compiled = EquipmentCompiler.compile(demo);

            SessionSpec spec = new SessionSpec("term", true, "127.0.0.1", port, 10, 1L, demo);
            this.session = new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));
            this.session.open();

            HsmsSsCommunicatorConfig cfg = new HsmsSsCommunicatorConfig();
            cfg.protocol(HsmsSsProtocol.ACTIVE);
            cfg.socketAddress(new InetSocketAddress("127.0.0.1", port));
            cfg.deviceId(10);
            this.client = HsmsSsCommunicator.open(cfg);
            this.client.openAndWaitUntilCommunicating();
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
