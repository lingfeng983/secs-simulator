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
 * 控制状态类报文：真实主机会话建立后第一件事就是发这些。
 *
 * <p>如果 S1F13 没有 S1F14 应答，主机侧直接判定"设备不理我"，
 * 后面再多的数据能力都用不上——所以这组报文是"能连上"的门槛。
 * </p>
 */
class SessionControlStateTest {

    @Test
    void establishCommunicationsIsAcknowledged() throws Exception {
        try (Fixture f = new Fixture()) {
            SecsMessage reply = f.send(1, 13, Secs2.getBuilder().list());
            assertEquals(14, reply.getFunction(), "S1F13 必须回 S1F14");

            /* E30 的 S1F14 是 <L[2] <B COMMACK> <L[2] MDLN SOFTREV>>，不是裸的字节 */
            Secs2 body = reply.secs2();
            assertEquals(2, body.size(), "S1F14 报文体是 L[2]");
            assertEquals(0, Secs2Read.asByte(body.get(0)), "COMMACK=0 表示接受");
            assertEquals(2, body.get(1).size(), "设备信息是 MDLN + SOFTREV");
        }
    }

    @Test
    void onlineRequestIsAcknowledged() throws Exception {
        try (Fixture f = new Fixture()) {
            SecsMessage reply = f.send(1, 17, Secs2.getBuilder().list());
            assertEquals(18, reply.getFunction());
            assertEquals(0, Secs2Read.asByte(reply.secs2()), "ONLACK=0");
        }
    }

    @Test
    void offlineRequestIsAcknowledged() throws Exception {
        try (Fixture f = new Fixture()) {
            SecsMessage reply = f.send(1, 15, Secs2.getBuilder().list());
            assertEquals(16, reply.getFunction());
            assertEquals(0, Secs2Read.asByte(reply.secs2()), "OFLACK=0");
        }
    }

    @Test
    void s2f17ReturnsTime() throws Exception {
        try (Fixture f = new Fixture()) {
            SecsMessage reply = f.send(2, 17, Secs2.getBuilder().list());
            assertEquals(18, reply.getFunction());

            String time = reply.secs2().getAscii();
            assertTrue(time.length() >= 12, "时间应是 yyyyMMddHHmmssXX 形式，实际: " + time);
            assertTrue(time.chars().allMatch(Character::isDigit), "时间应全为数字，实际: " + time);
        }
    }

    @Test
    void timeSetIsAcknowledged() throws Exception {
        try (Fixture f = new Fixture()) {
            Secs2 clock = Secs2.getBuilder().ascii("2024060112300055");
            SecsMessage reply = f.send(2, 31, clock);
            assertEquals(32, reply.getFunction());
            assertEquals(0, Secs2Read.asByte(reply.secs2()), "TIACK=0");
        }
    }

    @Test
    void linktestStillWorksAfterControlFlow() throws Exception {
        try (Fixture f = new Fixture()) {
            f.send(1, 13, Secs2.getBuilder().list());
            f.send(1, 17, Secs2.getBuilder().list());

            SecsMessage reply = f.send(1, 1, Secs2.getBuilder().list());
            assertEquals(2, reply.getFunction(), "控制流程之后链路仍然可用");
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

            SessionSpec spec = new SessionSpec("cs", true, "127.0.0.1", port, 10, 1L, demo);
            this.session = new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));
            this.session.open();

            HsmsSsCommunicatorConfig cfg = new HsmsSsCommunicatorConfig();
            cfg.protocol(HsmsSsProtocol.ACTIVE);
            cfg.socketAddress(new InetSocketAddress("127.0.0.1", port));
            cfg.deviceId(10);
            this.client = HsmsSsCommunicator.open(cfg);
            this.client.openAndWaitUntilCommunicating();
        }

        SecsMessage send(int stream, int function, Secs2 body) throws Exception {
            Secs2Builder b = Secs2.getBuilder();
            Optional<SecsMessage> reply = this.client.send(stream, function, true, body);

            assertTrue(reply.isPresent(), "S" + stream + "F" + function + " 没有应答");
            return reply.get();
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
