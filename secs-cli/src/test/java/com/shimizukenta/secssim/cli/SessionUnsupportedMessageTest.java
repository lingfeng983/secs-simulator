package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
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
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.ManualEngineClock;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.runtime.session.DemoEquipment;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;

/**
 * 不支持的 Stream/Function 必须回 S9Fx，而不是让主机等到 T3。
 */
class SessionUnsupportedMessageTest {

    @Test
    void unknownFunctionReturnsS9F5() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.send(1, 99, false, Secs2.getBuilder().list());

            SecsMessage s9 = f.received.poll(3L, TimeUnit.SECONDS);
            assertNotNull(s9, "S1F99 应触发 S9F5");
            assertEquals(9, s9.getStream());
            assertEquals(5, s9.getFunction());
        }
    }

    @Test
    void unknownStreamReturnsS9F3() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.send(99, 1, false, Secs2.getBuilder().list());

            SecsMessage s9 = f.received.poll(3L, TimeUnit.SECONDS);
            assertNotNull(s9, "S99F1 应触发 S9F3");
            assertEquals(9, s9.getStream());
            assertEquals(3, s9.getFunction());
        }
    }

    @Test
    void s9IsNotAnsweredWithAnotherS9() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.send(9, 5, false, Secs2.binary(new byte[10]));

            assertNull(f.received.poll(700L, TimeUnit.MILLISECONDS),
                    "S9 不能再回 S9，否则两端会互打");
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static final class Fixture implements AutoCloseable {

        private final HsmsSessionRuntime session;
        private final SecsCommunicator client;
        private final BlockingQueue<SecsMessage> received = new LinkedBlockingQueue<>();

        Fixture() throws Exception {
            int port = freePort();
            EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
            CompilationResult compiled = EquipmentCompiler.compile(demo);

            SessionSpec spec = new SessionSpec("unsupported", true, "127.0.0.1", port, 10, 1L, demo);
            this.session = new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));
            this.session.open();

            HsmsSsCommunicatorConfig cfg = new HsmsSsCommunicatorConfig();
            cfg.protocol(HsmsSsProtocol.ACTIVE);
            cfg.socketAddress(new InetSocketAddress("127.0.0.1", port));
            cfg.deviceId(10);
            this.client = HsmsSsCommunicator.open(cfg);
            this.client.addSecsMessageReceiveListener(this.received::add);
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
    }
}
