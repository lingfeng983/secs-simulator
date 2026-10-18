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
import com.shimizukenta.secssim.app.session.SessionRuntime;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.app.session.SessionState;
import com.shimizukenta.secssim.core.ManualEngineClock;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.curve.Rng;
import com.shimizukenta.secssim.core.value.GraphValueSource;
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

    private static final String TEMPLATE_CONFIG = """
            {
              "schemaVersion": 5,
              "session": { "id": "tpl-1", "passive": true, "bindHost": "127.0.0.1",
                           "port": 0, "deviceId": 44, "seed": 99 },
              "equipment": {
                "name": "TPL-EQ", "idSpace": {},
                "units": [ { "unitId": 1, "name": "CH1", "injectUnitId": false,
                  "svids": [
                    { "offset": 1, "slot": "TEMP", "type": "F4" },
                    { "offset": 2, "slot": "DERIVED", "type": "F4" }
                  ],
                  "reports": [], "ceids": [] } ]
              },
              "templates": {
                "curves": [ { "id": "temp", "type": "sine", "min": 100.0, "max": 200.0, "periodMs": 1000 } ],
                "composites": [ { "id": "derived", "outputs": { "V": "in.temp.V * 0.5" } } ],
                "instances": [
                  { "id": "temp", "template": "temp", "svids": { "V": 1001 } },
                  { "id": "derived", "template": "derived", "svids": { "V": 1002 } }
                ]
              }
            }
            """;

    @Test
    void schemaV5TemplatesDriveS1F3(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("模板-设备.json");
        Files.writeString(file, TEMPLATE_CONFIG, StandardCharsets.UTF_8);

        SessionConfig config = SessionConfigJson.read(file);
        CompilationResult compiled = config.compile();
        assertTrue(compiled.ok(), () -> "配置应能编译: " + compiled.errors());

        int port = freePort();
        SessionSpec configured = config.spec();
        SessionSpec spec = new SessionSpec(configured.id(), configured.passive(), configured.bindHost(),
                port, configured.deviceId(), configured.seed(), configured.equipment());

        GraphValueSource values = new GraphValueSource(
                config.templates().build(compiled.equipment()),
                new Rng(config.spec().seed()).derive("graph"));
        HsmsSessionRuntime session = new HsmsSessionRuntime(
                spec, compiled.equipment(), new ManualEngineClock(0L), values);
        session.open();

        try (SecsCommunicator client = connect(port, configured.deviceId())) {
            client.openAndWaitUntilCommunicating();

            Secs2Builder b = Secs2.getBuilder();
            Optional<SecsMessage> reply = client.send(1, 3, true, b.list(b.uint4(1001L), b.uint4(1002L)));
            assertTrue(reply.isPresent(), "S1F3 没有应答");

            Secs2 body = reply.get().secs2();
            assertEquals(150.0, Secs2Read.asDouble(body.get(0)), 1e-3, "sine t=0 应为中点");
            assertEquals(75.0, Secs2Read.asDouble(body.get(1)), 1e-3, "组合模板应派生");
        } finally {
            session.close();
        }
    }

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

    /**
     * 设备目录必须与 S6F11 实际发送的内容一致。
     *
     * <p>报告里的 SVID 顺序是断言的重点：{@code injectUnitId} 会把 {@code UNIT_ID}
     * 顶到首位，而 S6F11 也按这个顺序组包。如果目录按 ID 升序排，
     * 界面上看到的报告会与主机收到的不一致——那就正好复制了"猜"的痛苦。
     * </p>
     */
    @Test
    void catalogExposesAbsoluteIdsWithSemantics(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("目录-设备.json");
        Files.writeString(file, CONFIG, StandardCharsets.UTF_8);

        SessionConfig config = SessionConfigJson.read(file);
        CompilationResult compiled = config.compile();
        assertTrue(compiled.ok(), () -> "配置应能编译: " + compiled.errors());

        SessionSpec configured = config.spec();
        SessionSpec spec = new SessionSpec(configured.id(), configured.passive(), configured.bindHost(),
                0, configured.deviceId(), configured.seed(), configured.equipment());
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));

        String json = session.catalogJson();

        assertTrue(json.contains("{\"ceid\":2001,\"owner\":\"CH5\",\"slot\":\"PROCESS_START\","
                + "\"reports\":[3001],\"source\":\"local\",\"enabled\":true}"), json);
        assertTrue(json.contains("{\"rptid\":3001,\"owner\":\"CH5\",\"slot\":\"STATUS\","
                + "\"svids\":[1090,1001],\"source\":\"local\"}"),
                "报告内 SVID 顺序应与 S6F11 组包一致（UNIT_ID 在前）: " + json);
        assertTrue(json.contains("{\"svid\":1001,\"owner\":\"CH5\",\"slot\":\"TEMP_PV\",\"type\":\"F4\",\"unit\":\"C\"}"),
                json);
        assertTrue(json.contains("{\"svid\":1090,\"owner\":\"CH5\",\"slot\":\"UNIT_ID\",\"type\":\"U4\""),
                json);
    }

    /** 目录按 ID 升序：两次刷新之间的差异才能一眼看出是配置变了，而不是顺序抖了。 */
    @Test
    void catalogIsSortedById(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("排序-设备.json");
        Files.writeString(file, CONFIG, StandardCharsets.UTF_8);

        SessionConfig config = SessionConfigJson.read(file);
        CompilationResult compiled = config.compile();

        SessionSpec configured = config.spec();
        HsmsSessionRuntime session = new HsmsSessionRuntime(
                new SessionSpec(configured.id(), true, configured.bindHost(),
                        0, configured.deviceId(), configured.seed(), configured.equipment()),
                compiled.equipment(), new ManualEngineClock(0L));

        String json = session.catalogJson();
        assertTrue(json.indexOf("\"svid\":1001") < json.indexOf("\"svid\":1090"), json);
    }

    /** 默认实现返回三个空数组：探针这类会话没有目录，但也不是错误。 */
    @Test
    void sessionsWithoutCatalogReportEmptyArrays() {
        SessionRuntime bare = new SessionRuntime() {
            @Override
            public String id() {
                return "bare";
            }

            @Override
            public void open() {
                /* 桩 */
            }

            @Override
            public void close() {
                /* 桩 */
            }

            @Override
            public SessionState state() {
                return SessionState.CLOSED;
            }

            @Override
            public String lastError() {
                return "";
            }
        };

        assertEquals("{\"ceids\":[],\"reports\":[],\"svids\":[]}", bare.catalogJson());
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
