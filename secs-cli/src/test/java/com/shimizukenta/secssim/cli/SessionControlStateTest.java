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
import com.shimizukenta.secssim.app.session.EquipmentControlState;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.ManualEngineClock;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.event.Trigger;
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

    /**
     * S2F31 时间格式非法必须回 TIACK=1。
     *
     * <p>恒回 0 等于告诉主机"已同步"，而设备时钟根本没被改——主机的对时流程
     * 带着这个假成功继续往下走，后面所有时间戳都不可信。</p>
     */
    @Test
    void invalidTimeSetIsRejected() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(1, Secs2Read.asByte(f.send(2, 31, Secs2.getBuilder().ascii("2024-06-01")).secs2()),
                    "带分隔符 → TIACK=1");
            assertEquals(1, Secs2Read.asByte(f.send(2, 31, Secs2.getBuilder().ascii("2024060112300")).secs2()),
                    "13 位 → TIACK=1");
            assertEquals(1, Secs2Read.asByte(f.send(2, 31, Secs2.getBuilder().ascii("2024060112300X")).secs2()),
                    "含非数字 → TIACK=1");
        }
    }

    /** A12（12 位）也是合法 TIME，不能误判。 */
    @Test
    void a12TimeSetIsAccepted() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(0, Secs2Read.asByte(f.send(2, 31, Secs2.getBuilder().ascii("240601123000")).secs2()),
                    "A12 应接受");
        }
    }

    @Test
    void tracksCommunicatingOnlineAndOffline() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(EquipmentControlState.COMMUNICATING, f.session.controlState(),
                    "HSMS selected 后应进入 COMMUNICATING");

            f.send(1, 17, Secs2.getBuilder().list());
            assertEquals(EquipmentControlState.ONLINE, f.session.controlState(),
                    "S1F17 接受后应 ONLINE");

            f.send(1, 15, Secs2.getBuilder().list());
            assertEquals(EquipmentControlState.OFFLINE, f.session.controlState(),
                    "S1F15 后应回到 OFFLINE");

            f.send(1, 13, Secs2.getBuilder().list());
            assertEquals(EquipmentControlState.COMMUNICATING, f.session.controlState(),
                    "重新建立通信后应回到 COMMUNICATING");

            f.send(1, 17, Secs2.getBuilder().list());
            assertEquals(EquipmentControlState.ONLINE, f.session.controlState());
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

    /**
     * E30：<b>计划驱动的</b>主动上报（S6F11 / S6F1）只在 ONLINE 之后允许。
     *
     * <p>修复前 {@code controlState} 只用于界面显示：链路一选上就发事件，
     * 主机还没 S1F13/S1F17 就先收到一堆 S6F11，S1F15 下线后事件照发。
     * 严格的 GEM 主机会按违规处理。</p>
     *
     * <p>注意手动 {@code emitCeid} 是操作员的显式动作，不受此限（见方法注释）——
     * 这里用计划驱动，才能验证"设备自己决定什么时候发"的那条路径。</p>
     */
    @Test
    void scheduledReportsWaitForOnline() throws Exception {
        try (Fixture f = new Fixture()) {
            f.session.enableSchedule(new EventSchedule(List.of(
                    new EventPlan("beat", 2001, new Trigger.Periodic(1_000L, 0L)))), 10);

            assertEquals(EquipmentControlState.COMMUNICATING, f.session.controlState(),
                    "刚选上链路，主机还没建立通信");

            f.tick();
            assertEquals(0L, f.session.counters().get("emitted"), "未 ONLINE 时不得发出计划驱动的 S6F11");

            f.send(1, 13, Secs2.getBuilder().list());
            assertEquals(EquipmentControlState.COMMUNICATING, f.session.controlState(),
                    "S1F13 只建立通信，不等于 ONLINE");

            f.tick();
            assertEquals(0L, f.session.counters().get("emitted"), "仅 COMMUNICATING 仍然不得上报");

            f.send(1, 17, Secs2.getBuilder().list());
            assertEquals(EquipmentControlState.ONLINE, f.session.controlState());

            f.tick();
            assertEquals(1L, f.session.counters().get("emitted"), "ONLINE 后计划应恢复上报");

            f.send(1, 15, Secs2.getBuilder().list());
            assertEquals(EquipmentControlState.OFFLINE, f.session.controlState());

            f.tick();
            assertEquals(1L, f.session.counters().get("emitted"), "S1F15 下线后不得继续上报");
        }
    }

    /** 会话 + 客户端夹具。 */
    private static final class Fixture implements AutoCloseable {

        private final HsmsSessionRuntime session;
        private final SecsCommunicator client;
        private final ManualEngineClock clock;

        Fixture() throws Exception {
            int port = freePort();
            EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
            CompilationResult compiled = EquipmentCompiler.compile(demo);

            SessionSpec spec = new SessionSpec("cs", true, "127.0.0.1", port, 10, 1L, demo);
            this.clock = new ManualEngineClock(0L);
            this.session = new HsmsSessionRuntime(spec, compiled.equipment(), this.clock);
            this.session.open();

            HsmsSsCommunicatorConfig cfg = new HsmsSsCommunicatorConfig();
            cfg.protocol(HsmsSsProtocol.ACTIVE);
            cfg.socketAddress(new InetSocketAddress("127.0.0.1", port));
            cfg.deviceId(10);
            this.client = HsmsSsCommunicator.open(cfg);
            this.client.openAndWaitUntilCommunicating();
            SessionTestSupport.awaitPeer(this.session);
        }

        /** 推进 1 秒并跑一次心跳：让计划到期的 S6F11 有机会发出。 */
        void tick() {
            this.clock.advanceMs(1_000L);
            this.session.pumpNow();
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
