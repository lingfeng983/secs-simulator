package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Map;
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
import com.shimizukenta.secssim.core.curve.ConstantCurve;
import com.shimizukenta.secssim.core.curve.RampCurve;
import com.shimizukenta.secssim.core.curve.Rng;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.core.template.Expression;
import com.shimizukenta.secssim.core.template.CompositeTemplate;
import com.shimizukenta.secssim.core.template.ParameterLayers;
import com.shimizukenta.secssim.core.template.SingleCurveTemplate;
import com.shimizukenta.secssim.core.template.TemplateGraph;
import com.shimizukenta.secssim.core.value.GraphValueSource;
import com.shimizukenta.secssim.runtime.session.DemoEquipment;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;
import com.shimizukenta.secssim.runtime.session.Secs2Read;

/**
 * 用模板 DAG 驱动会话：S1F4 里的两个 SVID 由同一个模板图算出，
 * 且第二个是由第一个<b>派生</b>出来的——这正是"多个 SVID 一起生产"的最小证据。
 */
class SessionGraphS1F3Test {

    @Test
    void s1f4ValuesComeFromTemplateGraph() throws Exception {
        int port = freePort();
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);
        assertTrue(compiled.ok(), () -> compiled.issues().toString());

        TemplateGraph graph = TemplateGraph.builder()
                .add(new TemplateGraph.Instance("temp",
                        new SingleCurveTemplate("temp", RampCurve.of(0.0, 100.0, 100_000L)),
                        Map.of(SingleCurveTemplate.OUTPUT, 1001), ParameterLayers.empty()))
                .add(new TemplateGraph.Instance("sv",
                        new CompositeTemplate("sv", Map.of("V", Expression.parse("in.temp.V * 0.9 + 10"))),
                        Map.of("V", 1002), ParameterLayers.empty()))
                .add(new TemplateGraph.Instance("uid",
                        new SingleCurveTemplate("uid", new ConstantCurve(1.0)),
                        Map.of(SingleCurveTemplate.OUTPUT, 1090), ParameterLayers.empty()))
                .build();

        ManualEngineClock clock = new ManualEngineClock(0L);
        SessionSpec spec = new SessionSpec("g1", true, "127.0.0.1", port, 10, 7L, demo);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), clock,
                new GraphValueSource(graph, new Rng(4242L)));
        session.open();

        try (SecsCommunicator client = connect(port)) {
            client.openAndWaitUntilCommunicating();
            Secs2Builder b = Secs2.getBuilder();

            double[] atZero = query(client, b, 1001, 1002);
            assertEquals(0.0, atZero[0], 1e-3, "斜坡在 t=0 应为起点");
            assertEquals(10.0, atZero[1], 1e-3, "派生值 = 0 * 0.9 + 10");

            clock.advanceMs(50_000L);

            double[] atFifty = query(client, b, 1001, 1002);
            assertEquals(50.0, atFifty[0], 1e-3, "斜坡 50 秒到一半");
            assertEquals(55.0, atFifty[1], 1e-3, "派生值必须跟着动");

            double unitId = query(client, b, 1090)[0];
            assertEquals(1.0, unitId, 1e-9, "常量输出应稳定");
        } finally {
            session.close();
        }
    }

    private static double[] query(SecsCommunicator client, Secs2Builder b, int... svids) throws Exception {
        var list = new java.util.ArrayList<Secs2>();
        for (int svid : svids) {
            list.add(b.uint4(svid));
        }

        Optional<SecsMessage> reply = client.send(1, 3, true, b.list(list));
        assertTrue(reply.isPresent(), "S1F3 没有应答");

        Secs2 body = reply.get().secs2();
        double[] values = new double[svids.length];
        for (int i = 0; i < svids.length; i++) {
            values[i] = Secs2Read.asDouble(body.get(i));
        }
        return values;
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
}
