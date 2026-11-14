package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * S2F41 / S2F42 远程命令。
 *
 * <p>主机上线流程常会发 S2F41 探测命令支持。落 S9F5 会让主机走"设备不支持"的异常分支，
 * 与真正的能力缺口混淆——所以至少要回结构正确的 S2F42。</p>
 */
class SessionRemoteCommandTest {

    /** ONLINE 才执行：此时回 HCACK=0（接受）。 */
    @Test
    void onlineCommandIsAccepted() throws Exception {
        try (Fixture f = new Fixture()) {
            f.goOnline();

            SecsMessage reply = f.sendCommand("START");

            assertEquals(2, reply.getStream());
            assertEquals(42, reply.getFunction());

            Secs2 body = reply.secs2();
            assertEquals(2, body.size(), "S2F42 是 L[2]{HCACK, L{CPACK}}");
            assertEquals(0, Secs2Read.asByte(body.get(0)), "HCACK=0 接受");
            assertEquals(0, body.get(1).size(), "本实现命令不带参数，CPACK 列表为空");
        }
    }

    /**
     * 通信建立前（COMMUNICATING）应回 HCACK=4「需初始化」。
     *
     * <p>这里不能回 0：假装执行了比拒绝更糟——主机以为 START 生效，后续动作全错位。</p>
     */
    @Test
    void commandBeforeInitializeReturnsNeedInit() throws Exception {
        try (Fixture f = new Fixture()) {
            SecsMessage reply = f.sendCommand("START");
            assertEquals(4, Secs2Read.asByte(reply.secs2().get(0)), "未 S1F13 时 HCACK=4");
        }
    }

    /** S1F13 之后但未 S1F17：仍是「需初始化」。 */
    @Test
    void commandAfterEstablishButBeforeOnlineReturnsNeedInit() throws Exception {
        try (Fixture f = new Fixture()) {
            f.sendRaw(1, 13);

            SecsMessage reply = f.sendCommand("START");
            assertEquals(4, Secs2Read.asByte(reply.secs2().get(0)));
        }
    }

    /** 离线后不得再"接受"命令。 */
    @Test
    void commandAfterOfflineIsRejected() throws Exception {
        try (Fixture f = new Fixture()) {
            f.goOnline();
            f.sendRaw(1, 15);

            SecsMessage reply = f.sendCommand("STOP");
            assertEquals(4, Secs2Read.asByte(reply.secs2().get(0)), "S1F15 之后命令不得被接受");
        }
    }

    /** 形状非法（RCMD 不是 A 或为空）回 HCACK=1，而不是抛出去让主机等 T3。 */
    @Test
    void malformedCommandIsRejectedWithHcackOne() throws Exception {
        try (Fixture f = new Fixture()) {
            f.goOnline();

            Secs2Builder b = Secs2.getBuilder();
            Optional<SecsMessage> reply = f.client.send(2, 41, true, b.list(b.list(), b.list()));

            assertTrue(reply.isPresent(), "S2F41 必须有 S2F42 应答");
            assertEquals(42, reply.get().getFunction());
            assertEquals(1, Secs2Read.asByte(reply.get().secs2().get(0)), "RCMD 不是 A → HCACK=1");
        }
    }

    /** S2F41 不再落 S9F5（这是修复前的主机体验）。 */
    @Test
    void remoteCommandDoesNotFallBackToS9F5() throws Exception {
        try (Fixture f = new Fixture()) {
            f.goOnline();
            SecsMessage reply = f.sendCommand("PP-SELECT");
            assertEquals(42, reply.getFunction(), "必须是 S2F42，而不是 S9F5");
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

            SessionSpec spec = new SessionSpec("rcmd", true, "127.0.0.1", port, 10, 1L, demo);
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

        void goOnline() throws Exception {
            this.sendRaw(1, 13);
            this.sendRaw(1, 17);
        }

        SecsMessage sendRaw(int stream, int function) throws Exception {
            Optional<SecsMessage> reply = this.client.send(stream, function, true, Secs2.getBuilder().list());
            assertTrue(reply.isPresent(), "S" + stream + "F" + function + " 没有应答");
            return reply.get();
        }

        SecsMessage sendCommand(String rcmd) throws Exception {
            Secs2Builder b = Secs2.getBuilder();
            Optional<SecsMessage> reply = this.client.send(2, 41, true,
                    b.list(b.ascii(rcmd), b.list()));

            assertNotNull(reply, "S2F41 应回 S2F42");
            assertTrue(reply.isPresent(), "S2F41 应回 S2F42");
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
