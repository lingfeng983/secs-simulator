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
 * v2 设备链路端点的 HTTP 契约（{@code /api/v2/devices/{id}/link/**}）。
 *
 * <p>设计 §2.5 要求设备有独立的「连接 / 断开 / 重载」，语义是<b>只动链路不动设备</b>：
 * 断开后设备还在、配置与运行态编辑都保留，再连能恢复。这一层只证明
 * "路径 → 上下文方法 → 状态码"这条链是对的；链路本身的行为在 runtime 层。</p>
 */
class DeviceLinkApiTest {

    private HttpApiServer server;
    private String base;
    private final List<String> calls = new ArrayList<>();
    private SessionOutcome connectResult = SessionOutcome.OK;
    private SessionOutcome reloadResult = SessionOutcome.OK;
    private String linkJson = "{\"deviceId\":\"dev_a\",\"phase\":\"LISTENING\"}";

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
                /* 这个测试只打链路端点，不停机 */
            }

            @Override
            public String deviceLinkV2Json(String deviceId) {
                calls.add("state:" + deviceId);
                return "dev_missing".equals(deviceId) ? null : linkJson;
            }

            @Override
            public SessionOutcome deviceConnectV2(String deviceId) {
                calls.add("connect:" + deviceId);
                return "nope".equals(deviceId) ? SessionOutcome.UNKNOWN_SESSION : connectResult;
            }

            @Override
            public SessionOutcome deviceDisconnectV2(String deviceId) {
                calls.add("disconnect:" + deviceId);
                return SessionOutcome.OK;
            }

            @Override
            public SessionOutcome deviceReloadV2(String deviceId) {
                calls.add("reload:" + deviceId);
                return reloadResult;
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
    void linkActionsReachContext() throws Exception {
        assertEquals(200, send("POST", "/api/v2/devices/dev_a/link/connect").statusCode());
        assertEquals(200, send("POST", "/api/v2/devices/dev_a/link/disconnect").statusCode());
        assertEquals(200, send("POST", "/api/v2/devices/dev_a/link/reload").statusCode());
        assertEquals(List.of("connect:dev_a", "disconnect:dev_a", "reload:dev_a"), this.calls);
    }

    @Test
    void connectFailureIsReportedAsBadGateway() throws Exception {
        this.connectResult = SessionOutcome.FAILED;
        HttpResponse<String> response = send("POST", "/api/v2/devices/dev_a/link/connect");
        assertEquals(502, response.statusCode(), response.body());
        assertTrue(response.body().contains("link-connect-failed"), response.body());
    }

    @Test
    void unknownDeviceIs404() throws Exception {
        assertEquals(404, send("POST", "/api/v2/devices/nope/link/connect").statusCode());
        assertEquals(404, send("GET", "/api/v2/devices/dev_missing/link").statusCode());
    }

    @Test
    void reloadOnNonReloadableSessionIs409() throws Exception {
        this.reloadResult = SessionOutcome.NOT_RELOADABLE;
        assertEquals(409, send("POST", "/api/v2/devices/dev_a/link/reload").statusCode());
    }

    @Test
    void linkStateIsReadableAndUnknownActionIs404() throws Exception {
        HttpResponse<String> state = send("GET", "/api/v2/devices/dev_a/link");
        assertEquals(200, state.statusCode(), state.body());
        assertTrue(state.body().contains("\"LISTENING\""), state.body());
        assertEquals(404, send("POST", "/api/v2/devices/dev_a/link/teleport").statusCode());
        assertEquals(405, send("GET", "/api/v2/devices/dev_a/link/connect").statusCode());
    }

    /* ---------- helpers ---------- */

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(this.base + path))
                .method(method, HttpRequest.BodyPublishers.ofString(""))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
