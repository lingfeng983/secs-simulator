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
 * Trace 真链路：S2F23 点播、S6F1 周期上报。
 *
 * <p>用手动时钟 + {@code pumpNow()} 精确控制推进节奏，
 * 因此断言的是"第 k 个样本发生在 t0 + k × DSPER"，而不是靠 sleep 赌出来的时序。
 * </p>
 */
class SessionTraceTest {

    private static final long DSPER_MS = 1_000L;

    @Test
    void traceSamplesEveryCycleAndCompletes() throws Exception {
        try (Fixture f = new Fixture()) {
            int ack = f.startTrace(1, 3, 1, 1001, 1002);
            assertEquals(0, ack, "TIAACK 应为 OK");

            f.session.pumpNow();
            assertTrue(f.drain().isEmpty(), "t=0 还没有样本（第一个样本在 t0+1×DSPER）");

            f.clock.advanceMs(DSPER_MS);
            f.session.pumpNow();
            List<SecsMessage> frames = f.drain();
            assertEquals(1, frames.size(), "第一个周期应发一条 S6F1");

            Secs2 body = frames.get(0).secs2();
            assertEquals(1, Secs2Read.asInt(body.get(0)), "TRID");
            assertEquals(1, Secs2Read.asInt(body.get(1)), "SMPLN 是样本号");
            assertEquals(16, body.get(2).getAscii().length(), "STIME 是 16 位时间戳");
            assertEquals(2, body.get(3).size(), "1 个样本 × 2 个 SVID");

            assertEquals(f.session.valueStore().valueAt(1001, DSPER_MS).orElseThrow(),
                    Secs2Read.asDouble(body.get(3).get(0)), 1e-3, "取值必须是采样时刻的值");
            assertEquals(f.session.valueStore().valueAt(1002, DSPER_MS).orElseThrow(),
                    Secs2Read.asDouble(body.get(3).get(1)), 1e-3);

            f.clock.advanceMs(DSPER_MS);
            f.session.pumpNow();
            f.clock.advanceMs(DSPER_MS);
            f.session.pumpNow();

            List<SecsMessage> rest = f.drain();
            assertEquals(2, rest.size());
            assertEquals(2, Secs2Read.asInt(rest.get(0).secs2().get(1)));
            assertEquals(3, Secs2Read.asInt(rest.get(1).secs2().get(1)));

            assertEquals(0, f.session.activeTraces(), "样本采满后 Trace 应自动结束");
            assertEquals(3L, f.session.traceFramesSent());
        }
    }

    @Test
    void repgszGroupsMultipleSamplesIntoOneMessage() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(0, f.startTrace(2, 3, 2, 1001), "REPGSZ=2");

            f.clock.advanceMs(DSPER_MS);
            f.session.pumpNow();
            assertTrue(f.drain().isEmpty(), "只采到 1 个样本，未凑满一组");

            f.clock.advanceMs(DSPER_MS);
            f.session.pumpNow();
            List<SecsMessage> frames = f.drain();
            assertEquals(1, frames.size());

            Secs2 body = frames.get(0).secs2();
            assertEquals(2, Secs2Read.asInt(body.get(1)), "SMPLN 是本组最后一个样本号");
            assertEquals(2, body.get(3).size(), "2 个样本 × 1 个 SVID");

            f.clock.advanceMs(DSPER_MS);
            f.session.pumpNow();
            List<SecsMessage> last = f.drain();
            assertEquals(1, last.size(), "最后一条只含剩余 1 个样本");
            assertEquals(3, Secs2Read.asInt(last.get(0).secs2().get(1)));
            assertEquals(1, last.get(0).secs2().get(3).size());
        }
    }

    @Test
    void totsmpZeroTerminatesTrace() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(0, f.startTrace(3, 10, 1, 1001));
            assertEquals(1, f.session.activeTraces());

            assertEquals(0, f.startTrace(3, 0, 1, 1001), "TOTSMP=0 表示终止");
            assertEquals(0, f.session.activeTraces());

            f.clock.advanceMs(DSPER_MS * 3);
            f.session.pumpNow();
            assertEquals(0, f.drain().size(), "终止后不应再上报");
        }
    }

    @Test
    void invalidRequestsGetSemiAckCodes() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(4, f.startTrace(4, 5, 1, 9999), "未定义的 SVID -> TIAACK 4");

            assertEquals(0, f.startTrace(5, 100, 1, 1001));
            assertEquals(2, f.startTrace(6, 100, 1, 1001), "已有一条 Trace -> TIAACK 2");

            assertEquals(3, f.startTraceWithDsper("0000"), "非法 DSPER -> TIAACK 3");
        }
    }

    /** 一套会话 + 客户端 + 捕获 S6F1 的夹具。 */
    private static final class Fixture implements AutoCloseable {

        private final ManualEngineClock clock;
        private final HsmsSessionRuntime session;
        private final SecsCommunicator client;
        private final BlockingQueue<SecsMessage> s6f1 = new LinkedBlockingQueue<>();

        Fixture() throws Exception {
            int port = freePort();
            EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
            CompilationResult compiled = EquipmentCompiler.compile(demo);

            this.clock = new ManualEngineClock(0L);
            SessionSpec spec = new SessionSpec("tr", true, "127.0.0.1", port, 10, 424242L, demo);
            this.session = new HsmsSessionRuntime(spec, compiled.equipment(), this.clock);
            this.session.open();

            HsmsSsCommunicatorConfig cfg = new HsmsSsCommunicatorConfig();
            cfg.protocol(HsmsSsProtocol.ACTIVE);
            cfg.socketAddress(new InetSocketAddress("127.0.0.1", port));
            cfg.deviceId(10);
            this.client = HsmsSsCommunicator.open(cfg);
            this.client.addSecsMessageReceiveListener(message -> {
                if (message.getStream() == 6 && message.getFunction() == 1) {
                    this.s6f1.add(message);
                }
            });
            this.client.openAndWaitUntilCommunicating();
        }

        int startTrace(int trid, int totsmp, int repgsz, int... svids) throws Exception {
            return this.sendS2F23(trid, "000001", totsmp, repgsz, svids);
        }

        int startTraceWithDsper(String dsper) throws Exception {
            return this.sendS2F23(8, dsper, 3, 1, 1001);
        }

        private int sendS2F23(int trid, String dsper, int totsmp, int repgsz, int... svids) throws Exception {
            Secs2Builder b = Secs2.getBuilder();
            List<Secs2> list = new ArrayList<>();
            for (int svid : svids) {
                list.add(b.uint4(svid));
            }

            Optional<SecsMessage> reply = this.client.send(2, 23, true,
                    b.list(b.uint4(trid), b.ascii(dsper), b.uint4(totsmp), b.uint4(repgsz), b.list(list)));

            assertTrue(reply.isPresent(), "S2F23 没有应答");
            assertEquals(2, reply.get().getStream());
            assertEquals(24, reply.get().getFunction());
            /* S2F24 的报文体是标量 <B TIAACK>，不是列表 */
            return Secs2Read.asByte(reply.get().secs2());
        }

        List<SecsMessage> drain() throws InterruptedException {
            List<SecsMessage> frames = new ArrayList<>();
            SecsMessage message;
            /* 时钟是手动精确推进的，唯一的异步环节是 socket 投递，300ms 静默
             * 即视为收干。曾因误把 activeCircuit 回归（连接 +1000ms 猝死丢帧）
             * 当成负载抖动而放宽到 2s，根因修复后恢复原值。 */
            while ((message = this.s6f1.poll(300L, TimeUnit.MILLISECONDS)) != null) {
                frames.add(message);
            }
            return frames;
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
