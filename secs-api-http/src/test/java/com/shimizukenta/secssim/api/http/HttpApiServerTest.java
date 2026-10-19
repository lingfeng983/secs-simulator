package com.shimizukenta.secssim.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * HTTP 层的行为：鉴权、路由、状态码。
 *
 * <p>用桩 {@link ApiContext} 而不是真会话，因为这个测试要证明的是
 * "HTTP 层把结果映射成了正确的状态码"，与会话内部无关。
 * </p>
 */
class HttpApiServerTest {

    private static final String TOKEN = "test-token";

    private HttpApiServer server;
    private String base;
    private final List<String> triggered = new ArrayList<>();
    private final List<String> alarms = new ArrayList<>();
    private TriggerOutcome outcome = TriggerOutcome.OK;
    private final List<String> created = new ArrayList<>();
    private final List<String> closed = new ArrayList<>();
    private final List<String> reloaded = new ArrayList<>();
    private final List<String> messageQueries = new ArrayList<>();
    private final List<String> sentSml = new ArrayList<>();
    private SessionOutcome createResult = SessionOutcome.OK;
    private boolean messagesAvailable = true;

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
                /* 桩 */
            }

            @Override
            public String sessionsJson() {
                return "[{\"id\":\"s1\",\"state\":\"OPEN\"}]";
            }

            @Override
            public TriggerOutcome triggerCeid(String sessionId, int ceid) {
                if ("missing".equals(sessionId)) {
                    return TriggerOutcome.UNKNOWN_SESSION;
                }
                if ("closed".equals(sessionId)) {
                    return TriggerOutcome.NOT_OPEN;
                }
                triggered.add(sessionId + ":" + ceid);
                return outcome;
            }

            @Override
            public TriggerOutcome triggerAlarm(String sessionId, int alid, boolean set,
                                               String text, int category) {
                if ("missing".equals(sessionId)) {
                    return TriggerOutcome.UNKNOWN_SESSION;
                }
                if ("closed".equals(sessionId)) {
                    return TriggerOutcome.NOT_OPEN;
                }
                alarms.add(sessionId + ":" + alid + ":" + set + ":" + text + ":" + category);
                return outcome;
            }

            @Override
            public String alarmsJson(String sessionId) {
                return "missing".equals(sessionId) ? null : "[{\"alid\":1001}]";
            }

            @Override
            public String terminalMessagesJson(String sessionId) {
                return "missing".equals(sessionId) ? null : "[{\"tid\":1,\"text\":\"LINE READY\"}]";
            }

            @Override
            public String catalogJson(String sessionId) {
                return "missing".equals(sessionId)
                        ? null
                        : "{\"ceids\":[{\"ceid\":2001,\"owner\":\"CH1\",\"slot\":\"START\",\"reports\":[]}],"
                                + "\"reports\":[],\"svids\":[{\"svid\":1001,\"owner\":\"CH1\","
                                + "\"slot\":\"TEMP\",\"type\":\"F4\",\"unit\":\"C\"}]}";
            }

            @Override
            public String previewJson(String sessionId, long fromMs, long toMs, int buckets) {
                if ("missing".equals(sessionId)) {
                    return null;
                }
                return "{\"from\":" + fromMs + ",\"to\":" + toMs
                        + ",\"buckets\":" + buckets + ",\"series\":[]}";
            }

            @Override
            public String messagesJson(int limit, String sessionId, String direction) {
                if (!HttpApiServerTest.this.messagesAvailable) {
                    return null;
                }
                HttpApiServerTest.this.messageQueries.add("json:" + limit + ":" + sessionId + ":" + direction);
                return "{\"messages\":[],\"total\":0,\"limit\":" + limit
                        + ",\"truncated\":false}";
            }

            @Override
            public String messagesJsonl(int limit, String sessionId, String direction) {
                if (!HttpApiServerTest.this.messagesAvailable) {
                    return null;
                }
                HttpApiServerTest.this.messageQueries.add("jsonl:" + limit + ":" + sessionId + ":" + direction);
                return "{\"sessionId\":\"s1\",\"direction\":\"in\"}\n";
            }

            @Override
            public String sendSml(String sessionId, String sml) {
                if ("missing".equals(sessionId)) {
                    return null;
                }
                if ("closed".equals(sessionId)) {
                    throw new IllegalStateException("not open");
                }
                if ("S1F1 BAD".equals(sml)) {
                    throw new IllegalArgumentException("SML 解析失败: bad");
                }
                HttpApiServerTest.this.sentSml.add(sessionId + ":" + sml);
                return "{\"ok\":true,\"stream\":1,\"function\":1,\"wbit\":true,"
                        + "\"sentSml\":\"<A \\\"HI\\\">\",\"timedOut\":false,"
                        + "\"replySml\":null}";
            }

            @Override
            public SessionOutcome createSession(String configName) {
                HttpApiServerTest.this.created.add(configName);
                return HttpApiServerTest.this.createResult;
            }

            @Override
            public SessionOutcome closeSession(String sessionId) {
                HttpApiServerTest.this.closed.add(sessionId);
                return "missing".equals(sessionId) ? SessionOutcome.UNKNOWN_SESSION : SessionOutcome.OK;
            }

            @Override
            public SessionOutcome reloadSession(String sessionId) {
                HttpApiServerTest.this.reloaded.add(sessionId);
                if ("missing".equals(sessionId)) {
                    return SessionOutcome.UNKNOWN_SESSION;
                }
                if ("demo".equals(sessionId)) {
                    return SessionOutcome.NOT_RELOADABLE;
                }
                return SessionOutcome.OK;
            }
        };

        this.server = HttpApiServer.start(new InetSocketAddress("127.0.0.1", 0), TOKEN, context);
        this.base = "http://127.0.0.1:" + this.server.port();
    }

    @AfterEach
    void stopServer() {
        this.server.close();
    }

    @Test
    void healthNeedsNoTokenButHidesDetails() throws Exception {
        HttpResponse<String> response = get("/api/v1/health", null);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"ok\":true"), response.body());
        assertTrue(response.body().contains("uptimeMs"), response.body());
        assertFalse(response.body().contains("\"version\""), "免鉴权不应泄露版本: " + response.body());
        assertFalse(response.body().contains("\"mode\""), "免鉴权不应泄露形态: " + response.body());
    }

    @Test
    void healthWithTokenExposesDetails() throws Exception {
        HttpResponse<String> response = get("/api/v1/health", TOKEN);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"mode\":\"integrated\""), response.body());
        assertTrue(response.body().contains("\"version\":\"test\""), response.body());
    }

    @Test
    void sessionsRequireToken() throws Exception {
        assertEquals(401, get("/api/v1/sessions", null).statusCode());
        assertEquals(200, get("/api/v1/sessions", TOKEN).statusCode());
    }

    @Test
    void sessionsBodyIsForwardedFromContext() throws Exception {
        HttpResponse<String> response = get("/api/v1/sessions", TOKEN);
        assertTrue(response.body().contains("\"sessions\":[{\"id\":\"s1\""), response.body());
    }

    @Test
    void triggerAcceptsPostAndReturns202() throws Exception {
        HttpResponse<String> response = post("/api/v1/sessions/s1/ceid/2001", TOKEN);
        assertEquals(202, response.statusCode(), response.body());
        assertEquals(List.of("s1:2001"), this.triggered);
    }

    @Test
    void triggerRequiresTokenAndPost() throws Exception {
        assertEquals(401, post("/api/v1/sessions/s1/ceid/2001", null).statusCode());
        assertEquals(405, get("/api/v1/sessions/s1/ceid/2001", TOKEN).statusCode());
    }

    @Test
    void unknownSessionIs404() throws Exception {
        assertEquals(404, post("/api/v1/sessions/missing/ceid/2001", TOKEN).statusCode());
    }

    @Test
    void notOpenSessionIs409() throws Exception {
        assertEquals(409, post("/api/v1/sessions/closed/ceid/2001", TOKEN).statusCode());
    }

    @Test
    void malformedCommandPathIs400() throws Exception {
        assertEquals(400, post("/api/v1/sessions/s1/ceid/not-a-number", TOKEN).statusCode());
        assertEquals(400, post("/api/v1/sessions/s1/other/2001", TOKEN).statusCode());
        assertEquals(400, post("/api/v1/sessions//ceid/2001", TOKEN).statusCode());
    }

    @Test
    void alarmsAndTerminalQueriesReturnJson() throws Exception {
        HttpResponse<String> alarms = get("/api/v1/sessions/s1/alarms", TOKEN);
        assertEquals(200, alarms.statusCode());
        assertTrue(alarms.body().contains("\"alid\":1001"), alarms.body());

        HttpResponse<String> terminal = get("/api/v1/sessions/s1/terminal", TOKEN);
        assertEquals(200, terminal.statusCode());
        assertTrue(terminal.body().contains("LINE READY"), terminal.body());

        assertEquals(404, get("/api/v1/sessions/missing/alarms", TOKEN).statusCode());
        assertEquals(404, get("/api/v1/sessions/s1/unknown", TOKEN).statusCode());
    }

    @Test
    void alarmAcceptsPostAndPassesParams() throws Exception {
        HttpResponse<String> response = post(
                "/api/v1/sessions/s1/alarm/1001?set=false&text=HOT&category=2", TOKEN);
        assertEquals(202, response.statusCode(), response.body());
        assertEquals(List.of("s1:1001:false:HOT:2"), this.alarms);
    }

    @Test
    void alarmRejectsBadParameters() throws Exception {
        assertEquals(400, post("/api/v1/sessions/s1/alarm/0", TOKEN).statusCode());
        assertEquals(400, post("/api/v1/sessions/s1/alarm/x", TOKEN).statusCode());
        assertEquals(400, post("/api/v1/sessions/s1/alarm/1001?category=8", TOKEN).statusCode());
        assertEquals(400, post("/api/v1/sessions/s1/alarm/1001?category=x", TOKEN).statusCode());
    }

    @Test
    void alarmMapsSessionState() throws Exception {
        assertEquals(404, post("/api/v1/sessions/missing/alarm/1001", TOKEN).statusCode());
        assertEquals(409, post("/api/v1/sessions/closed/alarm/1001", TOKEN).statusCode());
    }

    @Test
    void sessionsCanBeCreatedFromConfigName() throws Exception {
        HttpResponse<String> response = send(
                HttpRequest.newBuilder(URI.create(this.base + "/api/v1/sessions"))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"config\":\"demo\"}", StandardCharsets.UTF_8)),
                TOKEN);

        assertEquals(201, response.statusCode(), response.body());
        assertEquals(List.of("demo"), this.created);
    }

    @Test
    void createRejectsBadOrMissingBody() throws Exception {
        assertEquals(400, post("/api/v1/sessions", TOKEN).statusCode(), "空体没有 config 字段");
        assertEquals(400, send(
                HttpRequest.newBuilder(URI.create(this.base + "/api/v1/sessions"))
                        .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8)), TOKEN).statusCode());
        assertEquals(400, send(
                HttpRequest.newBuilder(URI.create(this.base + "/api/v1/sessions"))
                        .POST(HttpRequest.BodyPublishers.ofString("not json", StandardCharsets.UTF_8)),
                TOKEN).statusCode());
        assertTrue(this.created.isEmpty(), "参数非法的请求不得产生副作用");
    }

    @Test
    void createMapsConflict() throws Exception {
        this.createResult = SessionOutcome.CONFLICT;

        HttpResponse<String> response = send(
                HttpRequest.newBuilder(URI.create(this.base + "/api/v1/sessions"))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"config\":\"demo\"}", StandardCharsets.UTF_8)),
                TOKEN);

        assertEquals(409, response.statusCode(), response.body());
    }

    @Test
    void createRejectsOversizedBody() throws Exception {
        String huge = "{\"config\":\"" + "x".repeat(8192) + "\"}";

        HttpResponse<String> response = send(
                HttpRequest.newBuilder(URI.create(this.base + "/api/v1/sessions"))
                        .POST(HttpRequest.BodyPublishers.ofString(huge, StandardCharsets.UTF_8)),
                TOKEN);

        assertEquals(400, response.statusCode(), "超大请求体应被拒绝，而不是把内存喂满");
        assertTrue(this.created.isEmpty());
    }

    @Test
    void sessionCanBeClosedWithDelete() throws Exception {
        HttpResponse<String> response = delete("/api/v1/sessions/s1", TOKEN);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of("s1"), this.closed);
        assertEquals(404, delete("/api/v1/sessions/missing", TOKEN).statusCode());
    }

    @Test
    void sessionCanBeReloaded() throws Exception {
        assertEquals(200, post("/api/v1/sessions/s1/reload", TOKEN).statusCode());
        assertEquals(List.of("s1"), this.reloaded);
    }

    @Test
    void reloadDistinguishesUnknownFromNotReloadable() throws Exception {
        HttpResponse<String> unknown = post("/api/v1/sessions/missing/reload", TOKEN);
        assertEquals(404, unknown.statusCode(), unknown.body());

        HttpResponse<String> demo = post("/api/v1/sessions/demo/reload", TOKEN);
        assertEquals(409, demo.statusCode(), "演示会话没有配置来源，要说清楚而不是笼统 500");
        assertTrue(demo.body().contains("not-reloadable"), demo.body());
    }

    @Test
    void sessionsRejectsPut() throws Exception {
        assertEquals(405, send(
                HttpRequest.newBuilder(URI.create(this.base + "/api/v1/sessions"))
                        .method("PUT", HttpRequest.BodyPublishers.noBody()), TOKEN).statusCode());
    }

    @Test
    void catalogReturnsAbsoluteIdsWithSemantics() throws Exception {        HttpResponse<String> response = get("/api/v1/sessions/s1/catalog", TOKEN);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"ceid\":2001"), response.body());
        assertTrue(response.body().contains("\"slot\":\"START\""), response.body());
        assertTrue(response.body().contains("\"svid\":1001"), response.body());
        assertTrue(response.body().contains("\"unit\":\"C\""), response.body());

        assertEquals(404, get("/api/v1/sessions/missing/catalog", TOKEN).statusCode());
    }

    @Test
    void previewRequiresToken() throws Exception {
        assertEquals(401, get("/api/v1/preview?session=s1", null).statusCode());
        assertEquals(200, get("/api/v1/preview?session=s1", TOKEN).statusCode());
    }

    @Test
    void previewAcceptsDefaultsWhenParamsOmitted() throws Exception {
        HttpResponse<String> response = get("/api/v1/preview?session=s1", TOKEN);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"buckets\":120"), response.body());
        assertTrue(response.body().contains("\"from\":0"), response.body());
        assertTrue(response.body().contains("\"to\":60000"), response.body());
    }

    @Test
    void previewRejectsNonNumericParams() throws Exception {
        assertEquals(400, get("/api/v1/preview?session=s1&from=abc", TOKEN).statusCode());
        assertEquals(400, get("/api/v1/preview?session=s1&to=1.5", TOKEN).statusCode());
        assertEquals(400, get("/api/v1/preview?session=s1&buckets=x", TOKEN).statusCode());
    }

    @Test
    void previewRejectsInvertedRange() throws Exception {
        assertEquals(400, get("/api/v1/preview?session=s1&from=5000&to=5000", TOKEN).statusCode());
        assertEquals(400, get("/api/v1/preview?session=s1&from=9000&to=1000", TOKEN).statusCode());
    }

    @Test
    void previewRejectsOutOfRangeBuckets() throws Exception {
        assertEquals(400, get("/api/v1/preview?session=s1&buckets=0", TOKEN).statusCode());
        assertEquals(400, get("/api/v1/preview?session=s1&buckets=501", TOKEN).statusCode());
        assertEquals(400, get("/api/v1/preview?session=s1&buckets=99999999999999", TOKEN).statusCode());
    }

    @Test
    void previewRequiresSessionAndChecksExistence() throws Exception {
        assertEquals(400, get("/api/v1/preview", TOKEN).statusCode());
        assertEquals(404, get("/api/v1/preview?session=missing", TOKEN).statusCode());
    }

    @Test
    void previewRejectsNonGet() throws Exception {
        assertEquals(405, post("/api/v1/preview?session=s1", TOKEN).statusCode());
    }

    @Test
    void messagesRequireTokenAndGet() throws Exception {
        assertEquals(401, get("/api/v1/messages", null).statusCode());
        assertEquals(405, post("/api/v1/messages", TOKEN).statusCode());
        assertEquals(200, get("/api/v1/messages", TOKEN).statusCode());
    }

    @Test
    void messagesPassFiltersAndLimit() throws Exception {
        HttpResponse<String> response = get(
                "/api/v1/messages?limit=10&session=s1&direction=in", TOKEN);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"limit\":10"), response.body());
        assertEquals(List.of("json:10:s1:in"), this.messageQueries);
    }

    @Test
    void messagesRejectBadParams() throws Exception {
        assertEquals(400, get("/api/v1/messages?limit=0", TOKEN).statusCode());
        assertEquals(400, get("/api/v1/messages?limit=5001", TOKEN).statusCode());
        assertEquals(400, get("/api/v1/messages?limit=x", TOKEN).statusCode());
        assertEquals(400, get("/api/v1/messages?direction=sideways", TOKEN).statusCode());
        assertEquals(400, get("/api/v1/messages?format=xml", TOKEN).statusCode());
    }

    @Test
    void messagesJsonlDownloadsAsAttachment() throws Exception {
        HttpResponse<String> response = get(
                "/api/v1/messages?format=jsonl&limit=5&direction=in", TOKEN);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/x-ndjson"),
                response.headers().toString());
        assertTrue(response.headers().firstValue("Content-Disposition").orElse("").contains("messages.jsonl"),
                response.headers().toString());
        assertTrue(response.body().contains("\"sessionId\":\"s1\""), response.body());
        assertEquals(List.of("jsonl:5::in"), this.messageQueries);
    }

    @Test
    void messagesUnavailableIsServiceUnavailable() throws Exception {
        this.messagesAvailable = false;

        assertEquals(503, get("/api/v1/messages", TOKEN).statusCode());
        assertEquals(503, get("/api/v1/messages?format=jsonl", TOKEN).statusCode());
    }

    @Test
    void sendMessageRequiresTokenAndPost() throws Exception {
        assertEquals(401, postSml("/api/v1/sessions/s1/message", null, "S1F1 W.").statusCode());
        assertEquals(405, get("/api/v1/sessions/s1/message", TOKEN).statusCode());
        assertEquals(200, postSml("/api/v1/sessions/s1/message", TOKEN, "S1F1 W.").statusCode());
    }

    @Test
    void sendMessagePassesRawSmlAndMapsErrors() throws Exception {
        HttpResponse<String> ok = postSml("/api/v1/sessions/s1/message", TOKEN, "S1F1 W.");
        assertEquals(200, ok.statusCode(), ok.body());
        assertTrue(ok.body().contains("\"ok\":true"), ok.body());
        assertEquals(List.of("s1:S1F1 W."), this.sentSml);

        assertEquals(404, postSml("/api/v1/sessions/missing/message", TOKEN, "S1F1 W.").statusCode());
        assertEquals(409, postSml("/api/v1/sessions/closed/message", TOKEN, "S1F1 W.").statusCode());
        assertEquals(400, postSml("/api/v1/sessions/s1/message", TOKEN, "S1F1 BAD").statusCode());
    }

    @Test
    void sendMessageRejectsEmptyBody() throws Exception {
        assertEquals(400, postSml("/api/v1/sessions/s1/message", TOKEN, "   ").statusCode());
    }

    @Test
    void shutdownRequiresTokenAndPost() throws Exception {
        assertEquals(401, get("/api/v1/shutdown", null).statusCode());
        assertEquals(405, get("/api/v1/shutdown", TOKEN).statusCode());
    }

    @Test
    void eventsRejectNonGet() throws Exception {
        assertEquals(405, post("/api/v1/events", TOKEN).statusCode());
    }

    @Test
    void tokenInQueryIsNoLongerAccepted() throws Exception {
        /* 长期凭据不进 URL：普通端点只认 Authorization 头 */
        assertEquals(401, get("/api/v1/sessions?token=" + TOKEN, null).statusCode());
    }

    @Test
    void ticketRequiresBearerAndPost() throws Exception {
        assertEquals(401, post("/api/v1/ticket", null).statusCode());
        assertEquals(405, get("/api/v1/ticket", TOKEN).statusCode());
        assertEquals(200, post("/api/v1/ticket", TOKEN).statusCode());
    }

    @Test
    void sseTicketIsSingleUse() throws Exception {
        String ticket = postTicket();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<InputStream> first = client.send(
                HttpRequest.newBuilder(URI.create(this.base + "/api/v1/events?ticket=" + ticket))
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, first.statusCode(), "票据首次使用应建立连接");
        first.body().close();

        HttpResponse<String> second = send(
                HttpRequest.newBuilder(URI.create(this.base + "/api/v1/events?ticket=" + ticket)).GET(), null);
        assertEquals(401, second.statusCode(), "同一票据不能用第二次");
    }

    @Test
    void sseRejectsTokenInQuery() throws Exception {
        HttpResponse<String> response = send(
                HttpRequest.newBuilder(URI.create(this.base + "/api/v1/events?token=" + TOKEN)).GET(), null);
        assertEquals(401, response.statusCode(), "SSE 不再接受长期 token，只能使用一次性 ticket");
    }

    private String postTicket() throws Exception {
        HttpResponse<String> response = post("/api/v1/ticket", TOKEN);
        assertEquals(200, response.statusCode(), response.body());

        Matcher matcher = Pattern.compile("\"ticket\":\"([0-9a-f]+)\"").matcher(response.body());
        assertTrue(matcher.find(), response.body());
        return matcher.group(1);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(this.base + path)).GET(), token);
    }

    private HttpResponse<String> post(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(this.base + path))
                .POST(HttpRequest.BodyPublishers.noBody()), token);
    }

    private HttpResponse<String> postSml(String path, String token, String sml) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(this.base + path))
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(sml, StandardCharsets.UTF_8)), token);
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
