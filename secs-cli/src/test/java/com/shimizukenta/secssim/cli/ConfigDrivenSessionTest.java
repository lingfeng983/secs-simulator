package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.hsmsss.HsmsSsProtocol;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Builder;
import com.shimizukenta.secssim.app.config.SessionConfig;
import com.shimizukenta.secssim.app.config.SessionConfigJson;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.ManualEngineClock;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;
import com.shimizukenta.secssim.runtime.session.Secs2Read;

/**
 * 配置驱动的会话：从 JSON 文件装载设备定义，编译成模型，起一个会话，用 S1F3 读数据。
 *
 * <p>这条链路是 `--config` 的全部内容（Main 里只是把它接起来），
 * 因此在这里验证比在 Main 里验证更直接。
 * </p>
 */
class ConfigDrivenSessionTest {

    private static final String CONFIG = """
            {
              "schemaVersion": 4,
              "session": { "id": "cfg-1", "passive": true, "bindHost": "127.0.0.1",
                           "port": 0, "deviceId": 33, "seed": 99 },
              "equipment": {
                "name": "CFG-EQ",
                "idSpace": {},
                "units": [
                  { "unitId": 5, "name": "CH5", "injectUnitId": true,
                    "svids": [
                      { "offset": 1, "slot": "TEMP_PV", "type": "F4", "unit": "C", "decimals": 2, "min": 0, "max": 400 },
                      { "offset": 90, "slot": "UNIT_ID", "type": "U4" }
                    ],
                    "reports": [ { "offset": 1, "slot": "STATUS", "svids": ["UNIT_ID", "TEMP_PV"] } ],
                    "ceids": [ { "offset": 1, "slot": "PROCESS_START", "reports": ["STATUS"] } ] }
                ]
              },
              "events": { "maxPerSec": 10, "plans": [] }
            }
            """;

    @Test
    void sessionBuiltFromJsonAnswersS1F3(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("配置-设备.json");
        Files.writeString(file, CONFIG, StandardCharsets.UTF_8);

        SessionConfig config = SessionConfigJson.read(file);
        CompilationResult compiled = config.compile();
        assertTrue(compiled.ok(), () -> "配置应能编译: " + compiled.errors());

        int port = freePort();
        SessionSpec configured = config.spec();
        SessionSpec spec = new SessionSpec(configured.id(), configured.passive(), configured.bindHost(),
                port, configured.deviceId(), configured.seed(), configured.equipment());

        ManualEngineClock clock = new ManualEngineClock(0L);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), clock);
        session.enableSchedule(config.schedule(), config.maxEventsPerSec());
        session.open();

        try (SecsCommunicator client = connect(port, configured.deviceId())) {
            client.openAndWaitUntilCommunicating();

            Secs2Builder b = Secs2.getBuilder();
            Optional<SecsMessage> reply = client.send(1, 3, true, b.list(b.uint4(1001L), b.uint4(1090L)));
            assertTrue(reply.isPresent(), "S1F3 没有应答");

            Secs2 body = reply.get().secs2();
            assertEquals(2, body.size());
            assertEquals(session.valueStore().valueAt(1001, 0L).orElseThrow(),
                    Secs2Read.asDouble(body.get(0)), 1e-3);
            assertEquals(5, Secs2Read.asInt(body.get(1)), "UNIT_ID 必须是配置里的 unitId");
        } finally {
            session.close();
        }
    }

    private static SecsCommunicator connect(int port, int deviceId) throws Exception {
        HsmsSsCommunicatorConfig cfg = new HsmsSsCommunicatorConfig();
        cfg.protocol(HsmsSsProtocol.ACTIVE);
        cfg.socketAddress(new InetSocketAddress("127.0.0.1", port));
        cfg.deviceId(deviceId);
        return HsmsSsCommunicator.open(cfg);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
