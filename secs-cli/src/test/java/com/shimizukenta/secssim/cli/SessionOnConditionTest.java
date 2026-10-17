package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
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
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.event.Trigger;
import com.shimizukenta.secssim.core.template.Expression;
import com.shimizukenta.secssim.core.value.ValueSource;
import com.shimizukenta.secssim.runtime.session.DemoEquipment;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;
import com.shimizukenta.secssim.runtime.session.Secs2Read;

/**
 * OnCondition：条件连续成立 debounceMs 才发一次 S6F11。
 */
class SessionOnConditionTest {

    @Test
    void conditionMustHoldForDebounceBeforeFiring() throws Exception {
        int port = freePort();
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);

        ManualEngineClock clock = new ManualEngineClock(0L);
        MutableValueSource values = new MutableValueSource(180.0);

        SessionSpec spec = new SessionSpec("cond", true, "127.0.0.1", port, 10, 1L, demo);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), clock, values);
        session.enableSchedule(new EventSchedule(List.of(
                new EventPlan("hot", 2001,
                        new Trigger.OnCondition(Expression.parse("svid.1001 > 190"), 1_000L)))), 20);
        session.open();

        try (SecsCommunicator client = connect(port)) {
            BlockingQueue<SecsMessage> events = new LinkedBlockingQueue<>();
            client.addSecsMessageReceiveListener(message -> {
                if (message.getStream() == 6 && message.getFunction() == 11) {
                    events.add(message);
                }
            });
            client.openAndWaitUntilCommunicating();
            awaitPeer(session);

            values.value = 200.0;
            session.pumpNow();
            assertTrue(events.isEmpty(), "t=0 刚成立，还没到 1s 去抖");

            clock.advanceMs(500L);
            session.pumpNow();
            assertTrue(events.isEmpty(), "t=0.5s 仍未满 1s");

            clock.advanceMs(500L);
            session.pumpNow();
            SecsMessage first = events.poll(3L, TimeUnit.SECONDS);
            assertNotNull(first, "t=1s 应触发一次 S6F11");
            assertEquals(2001, Secs2Read.asInt(first.secs2().get(0)));

            values.value = 100.0;
            session.pumpNow();
            values.value = 200.0;
            session.pumpNow();
            clock.advanceMs(500L);
            session.pumpNow();
            assertTrue(events.isEmpty(), "条件重新成立后应重新计 1s");

            clock.advanceMs(500L);
            session.pumpNow();
            assertNotNull(events.poll(3L, TimeUnit.SECONDS), "第二个条件周期应再次触发");
            assertEquals(2L, session.emittedCount());
        } finally {
            session.close();
        }
    }

    private static void awaitPeer(HsmsSessionRuntime session) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (!session.hasPeer() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(session.hasPeer(), "设备侧应看到主机上线");
    }

    private static SecsCommunicator connect(int port) throws Exception {
        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.protocol(HsmsSsProtocol.ACTIVE);
        config.socketAddress(new InetSocketAddress("127.0.0.1", port));
        config.deviceId(10);
        return HsmsSsCommunicator.open(config);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static final class MutableValueSource implements ValueSource {

        private volatile double value;

        MutableValueSource(double value) {
            this.value = value;
        }

        @Override
        public OptionalDouble valueAt(int svid, long tMs) {
            return svid == 1001 ? OptionalDouble.of(this.value) : OptionalDouble.empty();
        }

        @Override
        public Set<Integer> svids() {
            return Set.of(1001);
        }
    }
}
