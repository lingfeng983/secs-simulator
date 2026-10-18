package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.shimizukenta.secssim.runtime.session.Secs2Read;

/**
 * S5F1 报警：置位/清除，主机回 S5F2 后才计成功。
 */
class SessionAlarmTest {

    @Test
    void setAndClearAlarmAreAcknowledged() throws Exception {
        try (Fixture f = new Fixture()) {
            f.session.emitAlarm(1001, true, "TEMP HIGH", 1);
            awaitAlarms(f.session, 1L);

            SecsMessage set = f.alarms.poll(5L, TimeUnit.SECONDS);
            assertNotNull(set, "主机应收到置位 S5F1");
            assertTrue(set.wbit(), "S5F1 应带 W 位");
            Secs2 setBody = set.secs2();
            assertEquals(3, setBody.size(), "S5F1 是 <L[3] ALCD ALID ALTX>");
            int setAlcd = Secs2Read.asByte(setBody.get(0)) & 0xFF;
            assertEquals(0x80 | 1, setAlcd, "ALCD 高位置位 + 类别 1");
            assertEquals(1001, Secs2Read.asInt(setBody.get(1)));
            assertEquals("TEMP HIGH", setBody.get(2).getAscii());
            assertEquals(1, f.session.alarms().size(), "置位后应有一条活动报警");
            assertTrue(f.session.alarmsJson().contains("\"alid\":1001"), f.session.alarmsJson());

            f.session.emitAlarm(1001, false, "TEMP HIGH", 1);
            awaitAlarms(f.session, 2L);

            SecsMessage clear = f.alarms.poll(5L, TimeUnit.SECONDS);
            assertNotNull(clear, "主机应收到清除 S5F1");
            int clearAlcd = Secs2Read.asByte(clear.secs2().get(0)) & 0xFF;
            assertEquals(0x01, clearAlcd, "ALCD 高位清零 + 类别 1");
            assertTrue(f.session.alarms().isEmpty(), "清除后活动报警应为空");
            assertEquals("[]", f.session.alarmsJson());
            assertEquals(0L, f.session.counters().get("alarmDropped"));
        }
    }

    private static void awaitAlarms(HsmsSessionRuntime session, long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline && session.counters().get("alarmsSent") < expected) {
            Thread.sleep(20L);
        }
        assertEquals(expected, session.counters().get("alarmsSent"),
                "收到 S5F2 后才算报警发送成功");
    }

    private static final class Fixture implements AutoCloseable {

        private final HsmsSessionRuntime session;
        private final SecsCommunicator client;
        private final BlockingQueue<SecsMessage> alarms = new LinkedBlockingQueue<>();

        Fixture() throws Exception {
            int port = freePort();
            EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
            CompilationResult compiled = EquipmentCompiler.compile(demo);

            SessionSpec spec = new SessionSpec("alarm", true, "127.0.0.1", port, 10, 1L, demo);
            this.session = new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));
            this.session.open();

            HsmsSsCommunicatorConfig cfg = new HsmsSsCommunicatorConfig();
            cfg.protocol(HsmsSsProtocol.ACTIVE);
            cfg.socketAddress(new InetSocketAddress("127.0.0.1", port));
            cfg.deviceId(10);
            this.client = HsmsSsCommunicator.open(cfg);
            this.client.addSecsMessageReceiveListener(message -> {
                if (message.getStream() == 5 && message.getFunction() == 1) {
                    this.alarms.add(message);
                    try {
                        this.client.send(message, 5, 2, false,
                                Secs2.getBuilder().binary((byte) 0));
                    } catch (Exception ignored) {
                        /* 测试由 awaitAlarms 的断言兜底 */
                    }
                }
            });
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
