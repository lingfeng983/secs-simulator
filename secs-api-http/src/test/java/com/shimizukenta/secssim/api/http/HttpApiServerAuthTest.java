package com.shimizukenta.secssim.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 角色鉴权的端到端矩阵。
 *
 * <p>要证明的不是"谁能调通"，而是<b>被拒的请求确实没有产生副作用</b>：
 * viewer 点 CEID 必须既拿到 403，又不留下任何 {@code triggerCeid} 记录。
 * 只断言状态码的话，一个"先执行再拒绝"的实现也能蒙混过关。
 * </p>
 */
class HttpApiServerAuthTest {

    private static final String VIEWER_PASS = "g-pass";
    private static final String OPERATOR_PASS = "o-pass";
    private static final String ADMIN_PASS = "a-pass";

    private HttpApiServer server;
    private String base;
    private final List<String> triggered = new ArrayList<>();
    private final List<String> lifecycle = new ArrayList<>();
    private boolean shutdownRequested;

    @BeforeEach
    void startServer() throws IOException {
        AccessControl access = AccessControl.parse("""
                {"users": [
                  {"name": "guest", "role": "viewer",   "secret": "g-pass"},
                  {"name": "line1", "role": "operator", "secret": "o-pass"},
                  {"name": "owner", "role": "admin",    "secret": "a-pass"}
                ]}""");

        ApiContext context = new ApiContext() {
            @Override
            public String mode() {
                return "server";
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
                HttpApiServerAuthTest.this.shutdownRequested = true;
            }

            @Override
            public String sessionsJson() {
                return "[{\"id\":\"s1\",\"state\":\"OPEN\"}]";
            }

            @Override
            public TriggerOutcome triggerCeid(String sessionId, int ceid) {
                HttpApiServerAuthTest.this.triggered.add("ceid:" + sessionId + ":" + ceid);
                return TriggerOutcome.OK;
            }

            @Override
            public TriggerOutcome triggerAlarm(String sessionId, int alid, boolean set,
                                               String text, int category) {
                HttpApiServerAuthTest.this.triggered.add("alarm:" + sessionId + ":" + alid + ":" + set);
                return TriggerOutcome.OK;
            }

            @Override
            public String alarmsJson(String sessionId) {
                return "[]";
            }

            @Override
            public String terminalMessagesJson(String sessionId) {
                return "[]";
            }

            @Override
            public String catalogJson(String sessionId) {
                return "{\"ceids\":[],\"reports\":[],\"svids\":[]}";
            }

            @Override
            public SessionOutcome createSession(String configName) {
                HttpApiServerAuthTest.this.lifecycle.add("create:" + configName);
                return SessionOutcome.OK;
            }

            @Override
            public SessionOutcome closeSession(String sessionId) {
                HttpApiServerAuthTest.this.lifecycle.add("close:" + sessionId);
                return "missing".equals(sessionId) ? SessionOutcome.UNKNOWN_SESSION : SessionOutcome.OK;
            }

            @Override
            public SessionOutcome reloadSession(String sessionId) {
                HttpApiServerAuthTest.this.lifecycle.add("reload:" + sessionId);
                return "missing".equals(sessionId) ? SessionOutcome.UNKNOWN_SESSION : SessionOutcome.OK;
            }

            @Override
            public String previewJson(String sessionId, long fromMs, long toMs, int buckets) {
                return "{\"series\":[]}";
            }

            @Override
            public String configsJson() {
                return "[{\"name\":\"line1.json\",\"sizeBytes\":12,\"modifiedMs\":1700000000000}]";
            }

            @Override
            public String configText(String name) {
                return "line1.json".equals(name) ? "{\"ok\":true}" : null;
            }

            @Override
            public ConfigResult saveConfig(String name, String text) {
                HttpApiServerAuthTest.this.lifecycle.add("save:" + name);
                return ConfigResult.ok();
            }
        };

        this.server = HttpApiServer.start(new InetSocketAddress("127.0.0.1", 0), access, context);
        this.base = "http://127.0.0.1:" + this.server.port();
    }

    @AfterEach
    void stopServer() {
        this.server.close();
    }

    @Test
    void anonymousGetsNothingButLiveness() throws Exception {
        assertEquals(401, get("/api/v1/sessions", null).statusCode());
        assertEquals(401, get("/api/v1/sessions/s1/alarms", null).statusCode());
        assertEquals(401, get("/api/v1/preview?session=s1", null).statusCode());
        assertEquals(401, post("/api/v1/ticket", null).statusCode());
        assertEquals(401, post("/api/v1/sessions/s1/ceid/2001", null).statusCode());
        assertEquals(401, post("/api/v1/sessions/s1/alarm/1001", null).statusCode());
        assertEquals(401, post("/api/v1/sessions/s1/events/beat?enabled=false", null).statusCode());
        assertEquals(401, post("/api/v1/shutdown", null).statusCode());
        assertEquals(401, get("/api/v1/configs", null).statusCode());
        assertEquals(401, get("/api/v1/configs/line1.json", null).statusCode());
        assertEquals(401, get("/api/v1/audit", null).statusCode());
    }

    @Test
    void wrongSecretIsUnauthenticatedNotForbidden() throws Exception {
        /* 401 而不是 403：不能让扫描者据此判断"口令接近了，只是角色不够" */
        assertEquals(401, get("/api/v1/sessions", "nope").statusCode());
        assertEquals(401, post("/api/v1/sessions/s1/ceid/2001", "nope").statusCode());
        assertTrue(this.triggered.isEmpty());
    }

    @Test
    void viewerCanRead() throws Exception {
        assertEquals(200, get("/api/v1/sessions", VIEWER_PASS).statusCode());
        assertEquals(200, get("/api/v1/sessions/s1/alarms", VIEWER_PASS).statusCode());
        assertEquals(200, get("/api/v1/sessions/s1/terminal", VIEWER_PASS).statusCode());
        assertEquals(200, get("/api/v1/sessions/s1/catalog", VIEWER_PASS).statusCode());
        assertEquals(200, get("/api/v1/preview?session=s1", VIEWER_PASS).statusCode());
        assertEquals(200, post("/api/v1/ticket", VIEWER_PASS).statusCode());
    }

    /**
     * 反复申请票据但从不建 SSE 时，单账号未消费票据不能无限增长。
     *
     * <p>过期清理只在签发时触发；前端正常流程是"申请一张立刻建连"，
     * 所以上限只要宽于正常并发即可，这里用 8。</p>
     */
    @Test
    void ticketIssuanceIsBoundedPerUser() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertEquals(200, post("/api/v1/ticket", VIEWER_PASS).statusCode());
        }

        /* 票据本身不对外枚举，这里只验证：签发 30 次之后仍然可用（没有被自己的上限锁死），
           并且最早的一张已被淘汰——用最早那张去建 SSE 应当失败。 */
        String first = null;
        HttpResponse<String> response = post("/api/v1/ticket", VIEWER_PASS);
        assertEquals(200, response.statusCode(), response.body());
        first = response.body().replaceAll(".*\"ticket\":\"([0-9a-f]+)\".*", "$1");
        assertTrue(first.length() == 64, first);

        for (int i = 0; i < 8; i++) {
            post("/api/v1/ticket", VIEWER_PASS);
        }

        assertEquals(401, get("/api/v1/events?ticket=" + first, null).statusCode(),
                "被淘汰的票据不能再建 SSE");
    }

    @Test
    void viewerCannotChangeDeviceState() throws Exception {
        HttpResponse<String> ceid = post("/api/v1/sessions/s1/ceid/2001", VIEWER_PASS);
        assertEquals(403, ceid.statusCode(), ceid.body());
        assertTrue(ceid.body().contains("\"forbidden\""), ceid.body());
        assertTrue(ceid.body().contains("\"required\":\"OPERATOR\""), ceid.body());

        assertEquals(403, post("/api/v1/sessions/s1/alarm/1001", VIEWER_PASS).statusCode());
        assertEquals(403, post("/api/v1/sessions/s1/events/beat?enabled=false", VIEWER_PASS).statusCode());
        assertEquals(403, post("/api/v1/shutdown", VIEWER_PASS).statusCode());
        assertEquals(403, get("/api/v1/audit", VIEWER_PASS).statusCode());

        assertTrue(this.triggered.isEmpty(), "被拒的请求不得产生副作用: " + this.triggered);
        assertTrue(!this.shutdownRequested, "viewer 不得停机");
    }

    @Test
    void operatorCanDriveButNotShutDown() throws Exception {
        assertEquals(202, post("/api/v1/sessions/s1/ceid/2001", OPERATOR_PASS).statusCode());
        assertEquals(202, post("/api/v1/sessions/s1/alarm/1001", OPERATOR_PASS).statusCode());
        assertEquals(403, post("/api/v1/shutdown", OPERATOR_PASS).statusCode());
        assertEquals(403, get("/api/v1/audit", OPERATOR_PASS).statusCode());

        assertEquals(List.of("ceid:s1:2001", "alarm:s1:1001:true"), this.triggered);
        assertTrue(!this.shutdownRequested);
    }

    @Test
    void adminCanShutDown() throws Exception {
        assertEquals(200, post("/api/v1/shutdown", ADMIN_PASS).statusCode());
        assertTrue(this.shutdownRequested);
    }

    @Test
    void healthEchoesIdentityOnlyWhenAuthenticated() throws Exception {
        HttpResponse<String> anonymous = get("/api/v1/health", null);
        assertEquals(200, anonymous.statusCode());
        assertTrue(!anonymous.body().contains("\"role\""), anonymous.body());

        HttpResponse<String> viewer = get("/api/v1/health", VIEWER_PASS);
        assertEquals(200, viewer.statusCode());
        assertTrue(viewer.body().contains("\"user\":\"guest\""), viewer.body());
        assertTrue(viewer.body().contains("\"role\":\"viewer\""), viewer.body());

        HttpResponse<String> admin = get("/api/v1/health", ADMIN_PASS);
        assertTrue(admin.body().contains("\"role\":\"admin\""), admin.body());
    }

    @Test
    void ticketEchoesRoleAndStillOpensTheStream() throws Exception {
        HttpResponse<String> response = post("/api/v1/ticket", OPERATOR_PASS);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"user\":\"line1\""), response.body());
        assertTrue(response.body().contains("\"role\":\"operator\""), response.body());

        Matcher matcher = Pattern.compile("\"ticket\":\"([0-9a-f]+)\"").matcher(response.body());
        assertTrue(matcher.find(), response.body());

        HttpResponse<InputStream> stream = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(this.base + "/api/v1/events?ticket=" + matcher.group(1)))
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, stream.statusCode(), "报文流是只读的，operator 也应能订阅");
        stream.body().close();
    }

    @Test
    void viewerCannotChangeSessionLifecycle() throws Exception {
        assertEquals(403, delete("/api/v1/sessions/s1", VIEWER_PASS).statusCode());
        assertEquals(403, post("/api/v1/sessions/s1/reload", VIEWER_PASS).statusCode());
        assertEquals(403, send(
                HttpRequest.newBuilder(URI.create(this.base + "/api/v1/sessions"))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"config\":\"demo\"}", StandardCharsets.UTF_8)),
                VIEWER_PASS).statusCode());

        assertTrue(this.lifecycle.isEmpty(),
                "只读身份不得增删会话（会改变设备状态）: " + this.lifecycle);
    }

    @Test
    void operatorCanReloadButViewerCannot() throws Exception {
        assertEquals(200, post("/api/v1/sessions/s1/reload", OPERATOR_PASS).statusCode());
        assertEquals(List.of("reload:s1"), this.lifecycle);
    }

    /**
     * 改配置属于"会改变设备状态"，和触发 CEID 同一级。
     *
     * <p>配置决定了下一台设备长什么样，viewer 放行它等于让人改设备。
     * </p>
     */
    @Test
    void viewerCanReadConfigButNotSaveIt() throws Exception {
        assertEquals(200, get("/api/v1/configs", VIEWER_PASS).statusCode());
        assertEquals(200, get("/api/v1/configs/line1.json", VIEWER_PASS).statusCode());

        assertEquals(403, send(HttpRequest.newBuilder(URI.create(this.base + "/api/v1/configs/line1.json"))
                .PUT(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8)),
                VIEWER_PASS).statusCode());

        assertTrue(this.lifecycle.isEmpty(), "只读身份不得保存配置: " + this.lifecycle);
    }

    @Test
    void operatorCanSaveConfig() throws Exception {
        assertEquals(200, send(HttpRequest.newBuilder(URI.create(this.base + "/api/v1/configs/line1.json"))
                .PUT(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8)),
                OPERATOR_PASS).statusCode());

        assertEquals(List.of("save:line1.json"), this.lifecycle);
    }

    @Test
    void methodCheckStillBeatsTheRoleOnCommandPaths() throws Exception {
        /* GET 一个只接受 POST 的命令路径：viewer 有读权限，应回 405 而不是 403 */
        assertEquals(405, get("/api/v1/sessions/s1/ceid/2001", VIEWER_PASS).statusCode());

        /* 鉴权仍然排在方法检查前面：没凭据时一律 401，不泄露"这里有这条路" */
        assertEquals(401, get("/api/v1/sessions/s1/ceid/2001", "wrong").statusCode());
        assertEquals(401, get("/api/v1/shutdown", null).statusCode());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(this.base + path)).GET(), token);
    }

    private HttpResponse<String> post(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(this.base + path))
                .POST(HttpRequest.BodyPublishers.noBody()), token);
    }

    private HttpResponse<String> delete(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(this.base + path)).DELETE(), token);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder, String token) throws Exception {
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }

        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .send(builder.timeout(Duration.ofSeconds(10)).build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
