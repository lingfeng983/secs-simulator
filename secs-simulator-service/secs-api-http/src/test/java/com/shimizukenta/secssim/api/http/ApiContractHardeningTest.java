package com.shimizukenta.secssim.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 全量代码评审收敛出来的一批边界行为。
 *
 * <p>每一条都对应一个**曾经会给出错误状态码**的真实缺陷：合法 JSON 但不是对象时
 * 500、非法 {@code limit} 静默用默认值、失败的主机动作回 200/404、
 * 路径里的非数字 id 被当成"没有这个对象"、静态资源的 {@code %00} 抛异常。
 * 这些都不是"更好看"，而是客户端据此做分支判断时会被误导。</p>
 */
class ApiContractHardeningTest {

    private HttpApiServer server;
    private String base;

    private final List<String> calls = new ArrayList<>();
    private SessionOutcome actionOutcome = SessionOutcome.OK;
    private int messagesLimit = -1;

    @TempDir
    Path webRoot;

    @BeforeEach
    void startServer() throws IOException {
        java.nio.file.Files.writeString(this.webRoot.resolve("index.html"), "<html>ok</html>");
        java.nio.file.Files.writeString(this.webRoot.resolve("app.js"), "console.log(1);");

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
                /* 不涉及停机 */
            }

            @Override
            public String hostsV2Json() {
                return "{\"hosts\":[]}";
            }

            @Override
            public ApiContext.HostOpenResult openHostV2(String name, String host, int port,
                                                        int deviceId, boolean autoReconnect) {
                calls.add("open:" + name + ":" + host + ":" + port + ":" + autoReconnect);
                return new ApiContext.HostOpenResult(ApiContractHardeningTest.this.actionOutcome,
                        "h-new");
            }

            @Override
            public ApiContext.HostOpenResult openHostV2(String name, String host, int port,
                                                        int deviceId, boolean autoReconnect,
                                                        boolean connect) {
                calls.add("open:" + name + ":" + host + ":" + port + ":" + autoReconnect
                        + ":connect=" + connect);
                return new ApiContext.HostOpenResult(ApiContractHardeningTest.this.actionOutcome,
                        "h-new");
            }

            @Override
            public SessionOutcome saveHostV2(String hostId, String name, String host,
                                             int port, int deviceId) {
                calls.add("save:" + hostId);
                return ApiContractHardeningTest.this.actionOutcome;
            }

            @Override
            public SessionOutcome destroyHostV2(String hostId) {
                calls.add("destroy:" + hostId);
                return ApiContractHardeningTest.this.actionOutcome;
            }

            @Override
            public SessionOutcome hostConnectV2(String hostId) {
                calls.add("connect:" + hostId);
                return ApiContractHardeningTest.this.actionOutcome;
            }

            @Override
            public SessionOutcome hostDisconnectV2(String hostId) {
                calls.add("disconnect:" + hostId);
                return ApiContractHardeningTest.this.actionOutcome;
            }

            @Override
            public SessionOutcome hostApplyV2(String hostId) {
                calls.add("apply:" + hostId);
                return ApiContractHardeningTest.this.actionOutcome;
            }

            @Override
            public String hostMessagesV2(String hostId, int limit) {
                ApiContractHardeningTest.this.messagesLimit = limit;
                return "{\"messages\":[]}";
            }

            @Override
            public SessionOutcome createHostTemplateV2(String id, String name, String host,
                                                       int port, int deviceId, String note) {
                calls.add("template-create:" + name);
                return ApiContractHardeningTest.this.actionOutcome;
            }

            @Override
            public SessionOutcome deleteHostTemplateV2(String id) {
                calls.add("template-delete:" + id);
                return ApiContractHardeningTest.this.actionOutcome;
            }
        };
        this.server = HttpApiServer.start(new InetSocketAddress("127.0.0.1", 0),
                AccessControl.open(), context, this.webRoot, null, null, null);
        this.base = "http://127.0.0.1:" + this.server.port();
    }

    @AfterEach
    void stopServer() {
        this.server.close();
    }

    /* ---------- 请求体：合法 JSON 但不是对象 ---------- */

    @Test
    void nonObjectBodyIsBadRequestNotServerError() throws Exception {
        for (String body : List.of("[]", "\"text\"", "null", "123", "true")) {
            HttpResponse<String> res = send("POST", "/api/v2/hosts", body);
            assertEquals(400, res.statusCode(), "body=" + body + " → " + res.body());
            assertTrue(res.body().contains("bad-body"), res.body());
        }
        assertTrue(this.calls.isEmpty(), "非法请求体不能落到业务层: " + this.calls);
    }

    @Test
    void oversizedBodyIsRejectedInsteadOfSilentlyConnectingLocally() throws Exception {
        StringBuilder body = new StringBuilder("{\"host\":\"1.1.1.1\",\"port\":5000,\"pad\":\"");
        body.append("x".repeat(HttpSupport.MAX_CONFIG_BYTES));
        body.append("\"}");
        assertEquals(400, send("POST", "/api/v1/sessions/s1/host-sim", body.toString()).statusCode());
        assertTrue(this.calls.isEmpty(), "超限请求体不能被当成「没给目标」: " + this.calls);
    }

    @Test
    void oversizedObjectBodyReportsTooLargeNotBadBody() throws Exception {
        String body = "{\"name\":\"" + "x".repeat(HttpSupport.MAX_BODY_BYTES) + "\"}";
        HttpResponse<String> res = send("POST", "/api/v2/hosts", body);
        assertEquals(400, res.statusCode(), res.body());
        assertTrue(res.body().contains("body-too-large"), res.body());
        assertTrue(this.calls.isEmpty(), "超限请求体不能落到业务层: " + this.calls);
    }

    /* ---------- 主机动作的状态码 ---------- */

    @Test
    void hostActionFailureIsNotReportedAsSuccess() throws Exception {
        this.actionOutcome = SessionOutcome.FAILED;
        assertEquals(502, send("POST", "/api/v2/hosts/h1/connect", null).statusCode());

        this.actionOutcome = SessionOutcome.UNKNOWN_SESSION;
        assertEquals(404, send("POST", "/api/v2/hosts/h1/connect", null).statusCode());

        this.actionOutcome = SessionOutcome.CONFLICT;
        assertEquals(409, send("POST", "/api/v2/hosts/h1/disconnect", null).statusCode());

        this.actionOutcome = SessionOutcome.OK;
        assertEquals(200, send("POST", "/api/v2/hosts/h1/apply", null).statusCode());
    }

    @Test
    void hostSaveAndDestroyMapOutcomeToStatus() throws Exception {
        this.actionOutcome = SessionOutcome.FAILED;
        assertEquals(502, send("PUT", "/api/v2/hosts/h1",
                "{\"name\":\"n\",\"host\":\"1.1.1.1\",\"port\":5000}").statusCode());
        assertEquals(502, send("DELETE", "/api/v2/hosts/h1", null).statusCode());

        this.actionOutcome = SessionOutcome.CONFLICT;
        assertEquals(409, send("DELETE", "/api/v2/hosts/h1", null).statusCode());

        this.actionOutcome = SessionOutcome.UNKNOWN_SESSION;
        assertEquals(404, send("DELETE", "/api/v2/hosts/h1", null).statusCode());
    }

    @Test
    void hostTemplateConflictIs409Not502() throws Exception {
        this.actionOutcome = SessionOutcome.CONFLICT;
        assertEquals(409, send("POST", "/api/v2/host-templates",
                "{\"id\":\"t1\",\"name\":\"n\",\"host\":\"1.1.1.1\",\"port\":5000}").statusCode());
        assertEquals(409, send("DELETE", "/api/v2/host-templates/t1", null).statusCode());
    }

    @Test
    void hostOpenReturnsTheIdOfThisRequest() throws Exception {
        this.actionOutcome = SessionOutcome.OK;
        HttpResponse<String> res = send("POST", "/api/v2/hosts",
                "{\"name\":\"n\",\"host\":\"1.1.1.1\",\"port\":5000}");
        assertEquals(201, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"hostId\":\"h-new\""), res.body());

        this.actionOutcome = SessionOutcome.CONFLICT;
        assertEquals(409, send("POST", "/api/v2/hosts",
                "{\"name\":\"n\",\"host\":\"1.1.1.1\",\"port\":5000}").statusCode());
    }

    @Test
    void hostOpenConnectFlagDefaultsTrueAndPassesThrough() throws Exception {
        this.actionOutcome = SessionOutcome.OK;
        /* 缺省不带 connect：保持创建即连接的旧行为 */
        assertEquals(201, send("POST", "/api/v2/hosts",
                "{\"host\":\"1.1.1.1\",\"port\":5000}").statusCode());
        assertTrue(this.calls.get(this.calls.size() - 1).endsWith(":connect=true"),
                this.calls.toString());

        /* 显式关掉：只建实例不建链，开关要原样传到业务层 */
        assertEquals(201, send("POST", "/api/v2/hosts",
                "{\"host\":\"1.1.1.1\",\"port\":5000,\"connect\":false}").statusCode());
        assertTrue(this.calls.get(this.calls.size() - 1).endsWith(":connect=false"),
                this.calls.toString());

        /* 字段存在但不是布尔：与 autoReconnect 同口径，optionalBoolean 宽容退回缺省 true */
        assertEquals(201, send("POST", "/api/v2/hosts",
                "{\"host\":\"1.1.1.1\",\"port\":5000,\"connect\":\"yes\"}").statusCode());
        assertTrue(this.calls.get(this.calls.size() - 1).endsWith(":connect=true"),
                this.calls.toString());
    }

    /* ---------- limit 参数 ---------- */

    @Test
    void messagesAcceptsGetAndRejectsBadLimit() throws Exception {
        assertEquals(200, send("GET", "/api/v2/hosts/h1/messages", null).statusCode());
        assertEquals(500, this.messagesLimit, "缺省 limit 应是 500");

        assertEquals(400, send("GET", "/api/v2/hosts/h1/messages?limit=abc", null).statusCode());
        assertEquals(400, send("GET", "/api/v2/hosts/h1/messages?limit=0", null).statusCode());
        assertEquals(400, send("GET", "/api/v2/hosts/h1/messages?limit=999999", null).statusCode());

        assertEquals(200, send("GET", "/api/v2/hosts/h1/messages?limit=10", null).statusCode());
        assertEquals(10, this.messagesLimit);
    }

    /* ---------- 路径里的非数字 id ---------- */

    @Test
    void nonNumericSecsIdIsBadRequestNotNotFound() throws Exception {
        assertEquals(400, send("DELETE", "/api/v2/devices/dev_a/runtime/catalog/svid/abc", null).statusCode());
        assertEquals(400, send("PUT", "/api/v2/devices/dev_a/runtime/catalog/report/xyz", "{}").statusCode());
        assertEquals(400, send("DELETE", "/api/v2/devices/dev_a/runtime/svids/0/pin", null).statusCode());
        assertTrue(this.calls.isEmpty(), "非法 id 不能进业务层: " + this.calls);
    }

    /* ---------- 静态资源 ---------- */

    @Test
    void nulByteInStaticPathIsBadRequestNotServerError() throws Exception {
        HttpResponse<String> res = send("GET", "/%00.js", null);
        assertEquals(400, res.statusCode(), res.body());

        assertEquals(200, send("GET", "/app.js", null).statusCode());
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(this.base + path))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
