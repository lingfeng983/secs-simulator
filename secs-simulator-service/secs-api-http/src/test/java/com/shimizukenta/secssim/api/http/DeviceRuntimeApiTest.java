package com.shimizukenta.secssim.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * v2 设备运行时端点的 HTTP 契约（{@code /api/v2/devices/{id}/runtime/**}）。
 *
 * <p>设计 §2.5 把运行态拆成计划 / 目录 / 钉值 / 手动报文 / 报警 / 手动触发六组。
 * 这些端点只动运行态，不回写配置；这一层证明「路径 → 上下文方法 → 状态码」
 * 是通的，且参数校验发生在入口（坏 kind、坏 category、非 ASCII 文本都该是 400，
 * 不能漏成 500）。</p>
 */
class DeviceRuntimeApiTest {

    private HttpApiServer server;
    private String base;
    private final List<String> calls = new ArrayList<>();
    private int editStatus = 200;
    private int addStatus = 201;
    private String plansJson = "{\"events\":[]}";
    private String messageMode = "ok";
    private TriggerOutcome triggerResult = TriggerOutcome.OK;

    @BeforeEach
    void startServer() throws IOException {
        ApiContext context = new ApiContext() {
            @Override
            public String mode() {
                return "integrated";
            }

            @Override
            public String version() {
                return "test";
            }

            @Override
            public String extraHealthJson() {
                return "";
            }

            @Override
            public void requestShutdown() {
                /* 这个测试只打运行时端点，不停机 */
            }

            @Override
            public String plansV2Json(String deviceId) {
                calls.add("plans:" + deviceId);
                return "missing".equals(deviceId) ? null : plansJson;
            }

            @Override
            public int addPlanV2(String deviceId, String planJson) {
                calls.add("plan-add:" + deviceId + ":" + planJson);
                return addStatus;
            }

            @Override
            public int updatePlanV2(String deviceId, String planId, String planJson) {
                calls.add("plan-update:" + planId + ":" + planJson);
                return editStatus;
            }

            @Override
            public int removePlanV2(String deviceId, String planId) {
                calls.add("plan-remove:" + planId);
                return editStatus;
            }

            @Override
            public int addCatalogV2(String deviceId, String kind, String bodyJson) {
                calls.add("catalog-add:" + kind + ":" + bodyJson);
                return addStatus;
            }

            @Override
            public int updateCatalogV2(String deviceId, String kind, int key, String bodyJson) {
                calls.add("catalog-update:" + kind + ":" + key + ":" + bodyJson);
                return editStatus;
            }

            @Override
            public int removeCatalogV2(String deviceId, String kind, int key) {
                calls.add("catalog-remove:" + kind + ":" + key);
                return editStatus;
            }

            @Override
            public int pinSvidV2(String deviceId, int svid, String valueJson) {
                calls.add("pin:" + svid + ":" + valueJson);
                return editStatus;
            }

            @Override
            public int unpinSvidV2(String deviceId, int svid) {
                calls.add("unpin:" + svid);
                return editStatus;
            }

            @Override
            public String sendSmlV2(String deviceId, String sml) throws IOException {
                calls.add("sml:" + deviceId + ":" + sml);
                if ("null".equals(messageMode)) {
                    return null;
                }
                if ("bad-sml".equals(messageMode)) {
                    throw new IllegalArgumentException("SML 语法错误");
                }
                if ("offline".equals(messageMode)) {
                    throw new IllegalStateException("session closed");
                }
                if ("io".equals(messageMode)) {
                    throw new IOException("socket broken");
                }
                return "{\"ok\":true,\"stream\":1,\"function\":13}";
            }

            @Override
            public TriggerOutcome triggerCeidV2(String deviceId, int ceid) {
                calls.add("ceid:" + ceid);
                return triggerResult;
            }

            @Override
            public TriggerOutcome triggerAlarmV2(String deviceId, int alid, boolean set,
                                                 String text, int category) {
                calls.add("alarm:" + alid + ":" + set + ":" + text + ":" + category);
                return triggerResult;
            }
        };
        this.server = HttpApiServer.start(new InetSocketAddress("127.0.0.1", 0),
                AccessControl.open(), context, null, null, null, null);
        this.base = "http://127.0.0.1:" + this.server.port();
    }

    @AfterEach
    void stopServer() {
        this.server.close();
    }

    @Test
    void plansAreReadableAndEditable() throws Exception {
        HttpResponse<String> list = send("GET", "/api/v2/devices/dev_a/runtime/plans", null);
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("events"), list.body());

        assertEquals(201, send("POST", "/api/v2/devices/dev_a/runtime/plans",
                "{\"id\":\"p1\"}").statusCode());
        assertEquals(200, send("PUT", "/api/v2/devices/dev_a/runtime/plans/p1",
                "{\"id\":\"p1\"}").statusCode());
        assertEquals(200, send("DELETE", "/api/v2/devices/dev_a/runtime/plans/p1", null).statusCode());
        assertEquals(List.of("plans:dev_a", "plan-add:dev_a:{\"id\":\"p1\"}",
                "plan-update:p1:{\"id\":\"p1\"}", "plan-remove:p1"), this.calls);
    }

    @Test
    void plansOfUnknownDeviceIs404AndEmptyBodyIs400() throws Exception {
        assertEquals(404, send("GET", "/api/v2/devices/missing/runtime/plans", null).statusCode());
        assertEquals(400, send("POST", "/api/v2/devices/dev_a/runtime/plans", " ").statusCode());
    }

    @Test
    void planEditStatusIsMappedToHttp() throws Exception {
        this.addStatus = 409;
        HttpResponse<String> conflict = send("POST", "/api/v2/devices/dev_a/runtime/plans",
                "{\"id\":\"p1\"}");
        assertEquals(409, conflict.statusCode(), conflict.body());
        assertTrue(conflict.body().contains("conflict"), conflict.body());

        this.editStatus = 404;
        assertEquals(404, send("DELETE", "/api/v2/devices/dev_a/runtime/plans/p9", null).statusCode());
    }

    @Test
    void catalogEditUsesKindInPath() throws Exception {
        assertEquals(201, send("POST", "/api/v2/devices/dev_a/runtime/catalog/ceid",
                "{\"ceid\":2001}").statusCode());
        assertEquals(200, send("PUT", "/api/v2/devices/dev_a/runtime/catalog/report/1",
                "{\"rptid\":1}").statusCode());
        assertEquals(200, send("DELETE", "/api/v2/devices/dev_a/runtime/catalog/svid/7", null).statusCode());
        assertEquals(List.of("catalog-add:ceid:{\"ceid\":2001}", "catalog-update:report:1:{\"rptid\":1}",
                "catalog-remove:svid:7"), this.calls);
    }

    @Test
    void unknownCatalogKindIs400() throws Exception {
        HttpResponse<String> response = send("POST", "/api/v2/devices/dev_a/runtime/catalog/unit",
                "{}");
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("bad-kind"), response.body());
    }

    @Test
    void svidPinAndUnpin() throws Exception {
        assertEquals(200, send("PUT", "/api/v2/devices/dev_a/runtime/svids/7/pin",
                "{\"value\":12.5}").statusCode());
        assertEquals(200, send("DELETE", "/api/v2/devices/dev_a/runtime/svids/7/pin", null).statusCode());
        assertEquals(List.of("pin:7:{\"value\":12.5}", "unpin:7"), this.calls);
        /* 路径必须带 pin：少了尾段是 404，不能当成「删除 SVID」 */
        assertEquals(404, send("DELETE", "/api/v2/devices/dev_a/runtime/svids/7", null).statusCode());
    }

    @Test
    void manualMessageDistinguishesFailures() throws Exception {
        assertEquals(200, send("POST", "/api/v2/devices/dev_a/runtime/message",
                "S1F1 W\n.").statusCode());

        this.messageMode = "bad-sml";
        HttpResponse<String> bad = send("POST", "/api/v2/devices/dev_a/runtime/message", "??");
        assertEquals(400, bad.statusCode(), bad.body());
        assertTrue(bad.body().contains("bad-sml"), bad.body());

        this.messageMode = "offline";
        assertEquals(409, send("POST", "/api/v2/devices/dev_a/runtime/message",
                "S1F1 W\n.").statusCode());

        this.messageMode = "io";
        assertEquals(502, send("POST", "/api/v2/devices/dev_a/runtime/message",
                "S1F1 W\n.").statusCode());

        this.messageMode = "null";
        assertEquals(404, send("POST", "/api/v2/devices/missing/runtime/message",
                "S1F1 W\n.").statusCode());
    }

    @Test
    void emptyMessageIs400() throws Exception {
        assertEquals(400, send("POST", "/api/v2/devices/dev_a/runtime/message", "  ").statusCode());
    }

    @Test
    void alarmTakesBodyFieldsAndValidatesThem() throws Exception {
        HttpResponse<String> set = send("POST", "/api/v2/devices/dev_a/runtime/alarm/7",
                "{\"text\":\"TEMP HIGH\",\"category\":3}");
        assertEquals(202, set.statusCode(), set.body());
        assertEquals(202, send("DELETE", "/api/v2/devices/dev_a/runtime/alarm/7", null).statusCode());
        assertEquals(List.of("alarm:7:true:TEMP HIGH:3", "alarm:7:false::1"), this.calls);

        /* 类别 0 在 ALCD 里无定义：入口就拒，别等发报文时才失败 */
        assertEquals(400, send("POST", "/api/v2/devices/dev_a/runtime/alarm/7",
                "{\"category\":0}").statusCode());
        /* A 类型只允许 US-ASCII */
        assertEquals(400, send("POST", "/api/v2/devices/dev_a/runtime/alarm/7",
                "{\"text\":\"温度过高\"}").statusCode());
        assertEquals(400, send("POST", "/api/v2/devices/dev_a/runtime/alarm/7",
                "\"not-an-object\"").statusCode());
    }

    @Test
    void manualCeidTriggerMapsOutcomes() throws Exception {
        assertEquals(202, send("POST", "/api/v2/devices/dev_a/runtime/ceid/2001", null).statusCode());

        this.triggerResult = TriggerOutcome.NOT_OPEN;
        HttpResponse<String> offline = send("POST", "/api/v2/devices/dev_a/runtime/ceid/2001", null);
        assertEquals(409, offline.statusCode(), offline.body());
        assertTrue(offline.body().contains("device-offline"), offline.body());

        this.triggerResult = TriggerOutcome.UNKNOWN_SESSION;
        assertEquals(404, send("POST", "/api/v2/devices/dev_a/runtime/ceid/2001", null).statusCode());
        assertEquals(400, send("POST", "/api/v2/devices/dev_a/runtime/ceid/abc", null).statusCode());
    }

    @Test
    void unknownRuntimeResourceIs404AndWrongMethodIs405() throws Exception {
        assertEquals(404, send("GET", "/api/v2/devices/dev_a/runtime/warp", null).statusCode());
        assertEquals(405, send("GET", "/api/v2/devices/dev_a/runtime/message", null).statusCode());
        assertEquals(405, send("PUT", "/api/v2/devices/dev_a/runtime/plans", "{}").statusCode());
        /* 带 key 的目录条目没有 POST：新增走不带 key 的那条路径 */
        assertEquals(405, send("POST", "/api/v2/devices/dev_a/runtime/catalog/ceid/1", "{}").statusCode());
        assertEquals(405, send("POST", "/api/v2/devices/dev_a/runtime/svids/1/pin", "{}").statusCode());
    }

    /* ---------- helpers ---------- */

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(this.base + path))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
