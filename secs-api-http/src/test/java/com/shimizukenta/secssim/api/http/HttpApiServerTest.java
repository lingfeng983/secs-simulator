package com.shimizukenta.secssim.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
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
    private final List<String> eventToggles = new ArrayList<>();
    private final List<String> auditEntries = new ArrayList<>();
    private SessionOutcome createResult = SessionOutcome.OK;
    private boolean messagesAvailable = true;
    private int shutdownCalls;

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
                HttpApiServerTest.this.shutdownCalls += 1;
            }

            @Override
            public String sessionsJson() {
                return "[{\"id\":\"s1\",\"state\":\"OPEN\"}]";
            }

            @Override
            public String hostSimsJson() {
                return "{\"hosts\":[{\"id\":\"host-1\",\"open\":true}]}";
            }

            @Override
            public String burstHost(String hostId, String requestJson) {
                if ("missing".equals(hostId)) {
                    return null;
                }
                if (requestJson.contains("\"status\":true")) {
                    return "{\"ok\":true,\"running\":false,\"accepted\":false}";
                }
                if (requestJson.contains("\"kind\":\"ceid\"") || requestJson.contains("\"kind\":\"alarm\"")) {
                    return "{\"ok\":false,\"error\":\"unsupported-kind\"}";
                }
                if (requestJson.contains("\"count\":0")) {
                    return "{\"ok\":false,\"error\":\"count-out-of-range\"}";
                }
                return "{\"ok\":true,\"accepted\":true,\"running\":true,\"count\":3,"
                        + "\"sent\":0,\"failed\":0,\"skipped\":0,\"aborted\":false,"
                        + "\"durationMs\":0,\"latencyP50\":0,\"latencyP95\":0,\"latencyMax\":0}";
            }

            @Override
            public SessionOutcome openHostSim(String sessionId, String targetJson, String hostId) {
                return SessionOutcome.OK;
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
            public byte[] previewBinary(String sessionId, long fromMs, long toMs, int buckets) {
                if ("missing".equals(sessionId)) {
                    return null;
                }
                return new byte[] {1, 2, 3, 4};
            }

            @Override
            public String configPreviewJson(String text, long fromMs, long toMs, int buckets) {
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
            public String auditJson(int limit) {
                return "{\"audit\":[{\"actor\":\"local\",\"role\":\"admin\","
                        + "\"action\":\"trigger-ceid\",\"target\":\"s1\"}],\"limit\":" + limit + "}";
            }

            @Override
            public String eventsJson(String sessionId) {
                if ("missing".equals(sessionId)) {
                    return null;
                }
                return "{\"events\":[{\"id\":\"beat\",\"ceid\":2001,\"enabled\":true,"
                        + "\"triggerType\":\"periodic\",\"periodMs\":1000,\"jitterMs\":0}]}";
            }

            @Override
            public EventOutcome setEventEnabled(String sessionId, String planId, boolean enabled) {
                if ("missing".equals(sessionId)) {
                    return EventOutcome.UNKNOWN_SESSION;
                }
                if ("nope".equals(planId)) {
                    return EventOutcome.UNKNOWN_PLAN;
                }
                HttpApiServerTest.this.eventToggles.add(sessionId + ":" + planId + ":" + enabled);
                return EventOutcome.OK;
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

        AuditSink audit = (actor, role, action, target, detail) ->
                this.auditEntries.add(actor + ":" + role + ":" + action + ":" + target + ":" + detail);
        this.server = HttpApiServer.start(new InetSocketAddress("127.0.0.1", 0),
                AccessControl.open(), context, null, null, null, audit);
        this.base = "http://127.0.0.1:" + this.server.port();
    }

    @AfterEach
    void stopServer() {
        this.server.close();
    }

    @Test
    void healthExposesDetailsDirectly() throws Exception {
        HttpResponse<String> response = get("/api/v1/health", null);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"ok\":true"), response.body());
        assertTrue(response.body().contains("uptimeMs"), response.body());
        assertTrue(response.body().contains("\"mode\":\"integrated\""), response.body());
        assertTrue(response.body().contains("\"version\":\"test\""), response.body());
    }

    @Test
    void sessionsAreDirectlyReachable() throws Exception {
        assertEquals(200, get("/api/v1/sessions", null).statusCode());
    }

    @Test
    void sessionsBodyIsForwardedFromContext() throws Exception {
        HttpResponse<String> response = get("/api/v1/sessions", null);
        assertTrue(response.body().contains("\"sessions\":[{\"id\":\"s1\""), response.body());
    }

    @Test
    void triggerAcceptsPostAndReturns202() throws Exception {
        HttpResponse<String> response = post("/api/v1/sessions/s1/ceid/2001", null);
        assertEquals(202, response.statusCode(), response.body());
        assertEquals(List.of("s1:2001"), this.triggered);
    }

    @Test
    void triggerRequiresPost() throws Exception {
        assertEquals(405, get("/api/v1/sessions/s1/ceid/2001", null).statusCode());
    }

    @Test
    void unknownSessionIs404() throws Exception {
        assertEquals(404, post("/api/v1/sessions/missing/ceid/2001", null).statusCode());
    }

    @Test
    void notOpenSessionIs409() throws Exception {
        assertEquals(409, post("/api/v1/sessions/closed/ceid/2001", null).statusCode());
    }

    @Test
    void malformedCommandPathIs400() throws Exception {
        assertEquals(400, post("/api/v1/sessions/s1/ceid/not-a-number", null).statusCode());
        assertEquals(400, post("/api/v1/sessions/s1/other/2001", null).statusCode());
        assertEquals(400, post("/api/v1/sessions//ceid/2001", null).statusCode());
    }

    @Test
    void alarmsAndTerminalQueriesReturnJson() throws Exception {
        HttpResponse<String> alarms = get("/api/v1/sessions/s1/alarms", null);
        assertEquals(200, alarms.statusCode());
        assertTrue(alarms.body().contains("\"alid\":1001"), alarms.body());

        HttpResponse<String> terminal = get("/api/v1/sessions/s1/terminal", null);
        assertEquals(200, terminal.statusCode());
        assertTrue(terminal.body().contains("LINE READY"), terminal.body());

        assertEquals(404, get("/api/v1/sessions/missing/alarms", null).statusCode());
        assertEquals(404, get("/api/v1/sessions/s1/unknown", null).statusCode());
    }

    @Test
    void alarmAcceptsPostAndPassesParams() throws Exception {
        HttpResponse<String> response = post(
                "/api/v1/sessions/s1/alarm/1001?set=false&text=HOT&category=2", null);
        assertEquals(202, response.statusCode(), response.body());
        assertEquals(List.of("s1:1001:false:HOT:2"), this.alarms);
    }

    @Test
    void alarmRejectsBadParameters() throws Exception {
        assertEquals(400, post("/api/v1/sessions/s1/alarm/0", null).statusCode());
        assertEquals(400, post("/api/v1/sessions/s1/alarm/x", null).statusCode());
        assertEquals(400, post("/api/v1/sessions/s1/alarm/1001?category=8", null).statusCode());
        assertEquals(400, post("/api/v1/sessions/s1/alarm/1001?category=x", null).statusCode());
    }

    @Test
    void alarmMapsSessionState() throws Exception {
        assertEquals(404, post("/api/v1/sessions/missing/alarm/1001", null).statusCode());
        assertEquals(409, post("/api/v1/sessions/closed/alarm/1001", null).statusCode());
    }

    @Test
    void sessionsCanBeCreatedFromConfigName() throws Exception {
        HttpResponse<String> response = send(
                HttpRequest.newBuilder(URI.create(this.base + "/api/v1/sessions"))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"config\":\"demo\"}", StandardCharsets.UTF_8)),
                null);

        assertEquals(201, response.statusCode(), response.body());
        assertEquals(List.of("demo"), this.created);
    }

    @Test
    void createRejectsBadOrMissingBody() throws Exception {
        assertEquals(400, post("/api/v1/sessions", null).statusCode(), "空体没有 config 字段");
        assertEquals(400, send(
                HttpRequest.newBuilder(URI.create(this.base + "/api/v1/sessions"))
                        .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8)), null).statusCode());
        assertEquals(400, send(
                HttpRequest.newBuilder(URI.create(this.base + "/api/v1/sessions"))
                        .POST(HttpRequest.BodyPublishers.ofString("not json", StandardCharsets.UTF_8)),
                null).statusCode());
        assertTrue(this.created.isEmpty(), "参数非法的请求不得产生副作用");
    }

    @Test
    void createMapsConflict() throws Exception {
        this.createResult = SessionOutcome.CONFLICT;

        HttpResponse<String> response = send(
                HttpRequest.newBuilder(URI.create(this.base + "/api/v1/sessions"))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"config\":\"demo\"}", StandardCharsets.UTF_8)),
                null);

        assertEquals(409, response.statusCode(), response.body());
    }

    @Test
    void createRejectsOversizedBody() throws Exception {
        String huge = "{\"config\":\"" + "x".repeat(8192) + "\"}";

        HttpResponse<String> response = send(
                HttpRequest.newBuilder(URI.create(this.base + "/api/v1/sessions"))
                        .POST(HttpRequest.BodyPublishers.ofString(huge, StandardCharsets.UTF_8)),
                null);

        assertEquals(400, response.statusCode(), "超大请求体应被拒绝，而不是把内存喂满");
        assertTrue(this.created.isEmpty());
    }

    @Test
    void sessionCanBeClosedWithDelete() throws Exception {
        HttpResponse<String> response = delete("/api/v1/sessions/s1", null);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of("s1"), this.closed);
        assertEquals(404, delete("/api/v1/sessions/missing", null).statusCode());
    }

    @Test
    void sessionCanBeReloaded() throws Exception {
        assertEquals(200, post("/api/v1/sessions/s1/reload", null).statusCode());
        assertEquals(List.of("s1"), this.reloaded);
    }

    @Test
    void reloadDistinguishesUnknownFromNotReloadable() throws Exception {
        HttpResponse<String> unknown = post("/api/v1/sessions/missing/reload", null);
        assertEquals(404, unknown.statusCode(), unknown.body());

        HttpResponse<String> demo = post("/api/v1/sessions/demo/reload", null);
        assertEquals(409, demo.statusCode(), "演示会话没有配置来源，要说清楚而不是笼统 500");
        assertTrue(demo.body().contains("not-reloadable"), demo.body());
    }

    @Test
    void sessionsRejectsPut() throws Exception {
        assertEquals(405, send(
                HttpRequest.newBuilder(URI.create(this.base + "/api/v1/sessions"))
                        .method("PUT", HttpRequest.BodyPublishers.noBody()), null).statusCode());
    }

    @Test
    void catalogReturnsAbsoluteIdsWithSemantics() throws Exception {        HttpResponse<String> response = get("/api/v1/sessions/s1/catalog", null);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"ceid\":2001"), response.body());
        assertTrue(response.body().contains("\"slot\":\"START\""), response.body());
        assertTrue(response.body().contains("\"svid\":1001"), response.body());
        assertTrue(response.body().contains("\"unit\":\"C\""), response.body());

        assertEquals(404, get("/api/v1/sessions/missing/catalog", null).statusCode());
    }

    @Test
    void previewIsDirectlyReachable() throws Exception {
        assertEquals(200, get("/api/v1/preview?session=s1", null).statusCode());
    }

    @Test
    void previewAcceptsDefaultsWhenParamsOmitted() throws Exception {
        HttpResponse<String> response = get("/api/v1/preview?session=s1", null);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"buckets\":120"), response.body());
        assertTrue(response.body().contains("\"from\":0"), response.body());
        assertTrue(response.body().contains("\"to\":60000"), response.body());
    }

    @Test
    void previewRejectsNonNumericParams() throws Exception {
        assertEquals(400, get("/api/v1/preview?session=s1&from=abc", null).statusCode());
        assertEquals(400, get("/api/v1/preview?session=s1&to=1.5", null).statusCode());
        assertEquals(400, get("/api/v1/preview?session=s1&buckets=x", null).statusCode());
    }

    @Test
    void previewRejectsInvertedRange() throws Exception {
        assertEquals(400, get("/api/v1/preview?session=s1&from=5000&to=5000", null).statusCode());
        assertEquals(400, get("/api/v1/preview?session=s1&from=9000&to=1000", null).statusCode());
    }

    @Test
    void previewRejectsOversizedWindow() throws Exception {
        /* 一天 86_400_000ms 是上限；再多就拒绝，而不是让 EventSimulator 空转。 */
        HttpResponse<String> ok = get(
                "/api/v1/preview?session=s1&from=0&to=86400000", null);
        assertEquals(200, ok.statusCode(), ok.body());

        HttpResponse<String> tooLarge = get(
                "/api/v1/preview?session=s1&from=0&to=86400001", null);
        assertEquals(400, tooLarge.statusCode(), tooLarge.body());
        assertTrue(tooLarge.body().contains("range-too-large"), tooLarge.body());

        /* to-from 溢出也要落在同一条分支（差值绕回负数） */
        HttpResponse<String> overflow = get(
                "/api/v1/preview?session=s1&from=-9223372036854775808&to=9223372036854775807", null);
        assertEquals(400, overflow.statusCode(), overflow.body());
        assertTrue(overflow.body().contains("bad-range") || overflow.body().contains("range-too-large"),
                overflow.body());

        /* 二进制预览走同一套参数校验 */
        assertEquals(400, getBytes("/api/v1/preview.bin?session=s1&to=86400001", null).statusCode());
    }

    @Test
    void configPreviewRejectsOversizedWindow() throws Exception {
        /* 草稿预览同样会跑 EventSimulator：超 24h 必须在参数层回 400，
           而不是让下层异常被吞成 422 config-invalid（把 CPU 预算问题报成配置错误） */
        HttpResponse<String> ok = post("/api/v1/config-preview?from=0&to=86400000", null);
        assertEquals(200, ok.statusCode(), ok.body());

        HttpResponse<String> tooLarge = post("/api/v1/config-preview?from=0&to=86400001", null);
        assertEquals(400, tooLarge.statusCode(), tooLarge.body());
        assertTrue(tooLarge.body().contains("range-too-large"), tooLarge.body());

        /* to-from 溢出同样落在拒绝分支 */
        HttpResponse<String> overflow = post(
                "/api/v1/config-preview?from=-9223372036854775808&to=9223372036854775807", null);
        assertEquals(400, overflow.statusCode(), overflow.body());
    }

    @Test
    void previewRejectsOutOfRangeBuckets() throws Exception {
        assertEquals(400, get("/api/v1/preview?session=s1&buckets=0", null).statusCode());
        assertEquals(400, get("/api/v1/preview?session=s1&buckets=501", null).statusCode());
        assertEquals(400, get("/api/v1/preview?session=s1&buckets=99999999999999", null).statusCode());
    }

    @Test
    void previewRequiresSessionAndChecksExistence() throws Exception {
        assertEquals(400, get("/api/v1/preview", null).statusCode());
        assertEquals(404, get("/api/v1/preview?session=missing", null).statusCode());
    }

    @Test
    void previewRejectsNonGet() throws Exception {
        assertEquals(405, post("/api/v1/preview?session=s1", null).statusCode());
    }

    @Test
    void binaryPreviewReturnsOctetStream() throws Exception {
        HttpResponse<byte[]> response = getBytes(
                "/api/v1/preview.bin?session=s1&from=0&to=1000&buckets=2", null);

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/octet-stream"),
                response.headers().toString());
        assertTrue(response.headers().firstValue("Content-Disposition").orElse("").contains("preview.bin"),
                response.headers().toString());
        assertEquals(List.of(1, 2, 3, 4), java.util.Arrays.stream(toInts(response.body())).boxed().toList());
        assertEquals(404, getBytes("/api/v1/preview.bin?session=missing", null).statusCode());
        assertEquals(405, post("/api/v1/preview.bin?session=s1", null).statusCode());
        assertEquals(400, getBytes("/api/v1/preview.bin?session=s1&buckets=0", null).statusCode());
    }

    @Test
    void messagesAreGetOnly() throws Exception {
        assertEquals(405, post("/api/v1/messages", null).statusCode());
        assertEquals(200, get("/api/v1/messages", null).statusCode());
    }

    @Test
    void messagesPassFiltersAndLimit() throws Exception {
        HttpResponse<String> response = get(
                "/api/v1/messages?limit=10&session=s1&direction=in", null);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"limit\":10"), response.body());
        assertEquals(List.of("json:10:s1:in"), this.messageQueries);
    }

    @Test
    void messagesRejectBadParams() throws Exception {
        assertEquals(400, get("/api/v1/messages?limit=0", null).statusCode());
        assertEquals(400, get("/api/v1/messages?limit=5001", null).statusCode());
        assertEquals(400, get("/api/v1/messages?limit=x", null).statusCode());
        assertEquals(400, get("/api/v1/messages?direction=sideways", null).statusCode());
        assertEquals(400, get("/api/v1/messages?format=xml", null).statusCode());
    }

    @Test
    void messagesJsonlDownloadsAsAttachment() throws Exception {
        HttpResponse<String> response = get(
                "/api/v1/messages?format=jsonl&limit=5&direction=in", null);

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

        assertEquals(503, get("/api/v1/messages", null).statusCode());
        assertEquals(503, get("/api/v1/messages?format=jsonl", null).statusCode());
    }

    @Test
    void sendMessageRequiresPost() throws Exception {
        assertEquals(405, get("/api/v1/sessions/s1/message", null).statusCode());
        assertEquals(200, postSml("/api/v1/sessions/s1/message", null, "S1F1 W.").statusCode());
    }

    @Test
    void sendMessagePassesRawSmlAndMapsErrors() throws Exception {
        HttpResponse<String> ok = postSml("/api/v1/sessions/s1/message", null, "S1F1 W.");
        assertEquals(200, ok.statusCode(), ok.body());
        assertTrue(ok.body().contains("\"ok\":true"), ok.body());
        assertEquals(List.of("s1:S1F1 W."), this.sentSml);

        assertEquals(404, postSml("/api/v1/sessions/missing/message", null, "S1F1 W.").statusCode());
        assertEquals(409, postSml("/api/v1/sessions/closed/message", null, "S1F1 W.").statusCode());
        assertEquals(400, postSml("/api/v1/sessions/s1/message", null, "S1F1 BAD").statusCode());
    }

    @Test
    void sendMessageRejectsEmptyBody() throws Exception {
        assertEquals(400, postSml("/api/v1/sessions/s1/message", null, "   ").statusCode());
    }

    @Test
    void auditValidatesLimit() throws Exception {
        HttpResponse<String> response = get("/api/v1/audit?limit=10", null);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"action\":\"trigger-ceid\""), response.body());
        assertTrue(response.body().contains("\"limit\":10"), response.body());
        assertEquals(405, post("/api/v1/audit", null).statusCode());
        assertEquals(400, get("/api/v1/audit?limit=0", null).statusCode());
        assertEquals(400, get("/api/v1/audit?limit=x", null).statusCode());
    }

    @Test
    void eventsListAndToggleMapUnknowns() throws Exception {
        HttpResponse<String> list = get("/api/v1/sessions/s1/events", null);
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("\"id\":\"beat\""), list.body());
        assertTrue(list.body().contains("\"triggerType\":\"periodic\""), list.body());
        assertEquals(404, get("/api/v1/sessions/missing/events", null).statusCode());

        assertEquals(200, post("/api/v1/sessions/s1/events/beat?enabled=false", null).statusCode());
        assertEquals(List.of("s1:beat:false"), this.eventToggles);
        assertEquals(404, post("/api/v1/sessions/missing/events/beat", null).statusCode());
        assertEquals(404, post("/api/v1/sessions/s1/events/nope", null).statusCode());
        assertEquals(400, post("/api/v1/sessions/s1/events/beat?enabled=maybe", null).statusCode());
    }

    @Test
    void auditsMutatingOperationsWithActorAndRole() throws Exception {
        assertEquals(202, post("/api/v1/sessions/s1/ceid/2001", null).statusCode());
        assertTrue(this.auditEntries.stream().anyMatch(entry ->
                entry.contains("local:admin:trigger-ceid:s1:ceid=2001 outcome=OK")), this.auditEntries.toString());

        HttpResponse<String> created = send(
                HttpRequest.newBuilder(URI.create(this.base + "/api/v1/sessions"))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"config\":\"demo\"}", StandardCharsets.UTF_8)),
                null);
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(this.auditEntries.stream().anyMatch(entry ->
                entry.contains("local:admin:session-create:demo:outcome=OK")), this.auditEntries.toString());
    }

    @Test
    void shutdownRequiresPost() throws Exception {
        assertEquals(405, get("/api/v1/shutdown", null).statusCode());
    }

    @Test
    void eventsRejectNonGet() throws Exception {
        assertEquals(405, post("/api/v1/events", null).statusCode());
    }


    @Test
    void ticketIsPostOnly() throws Exception {
        assertEquals(405, get("/api/v1/ticket", null).statusCode());
        assertEquals(200, post("/api/v1/ticket", null).statusCode());
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
    void sseRequiresValidTicket() throws Exception {
        HttpResponse<String> response = send(
                HttpRequest.newBuilder(URI.create(this.base + "/api/v1/events?ticket=deadbeef")).GET(), null);
        assertEquals(401, response.statusCode(), "没有有效票据时必须 401");
    }

    private String postTicket() throws Exception {
        HttpResponse<String> response = post("/api/v1/ticket", null);
        assertEquals(200, response.statusCode(), response.body());

        Matcher matcher = Pattern.compile("\"ticket\":\"([0-9a-f]+)\"").matcher(response.body());
        assertTrue(matcher.find(), response.body());
        return matcher.group(1);
    }

    /**
     * 跨域白名单：任意网站不能再驱动本机 API；Tauri 壳与回环开发服务器仍可用。
     *
     * <p>旧实现回 {@code Allow-Origin: *}，用户在浏览器里打开任意页面就可能被它
     * 建会话、改配置、停机（本机形态默认开放访问）。</p>
     */
    @Test
    void corsOnlyEchoesAllowedOrigins() throws Exception {
        HttpResponse<String> evilPreflight = send(
                HttpRequest.newBuilder(URI.create(this.base + "/api/v1/health"))
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .header("Origin", "https://evil.example"),
                null);
        assertEquals(403, evilPreflight.statusCode(), "白名单外来源的预检必须被拒绝");
        assertTrue(evilPreflight.headers().firstValue("Access-Control-Allow-Origin").isEmpty());

        HttpResponse<String> tauriPreflight = send(
                HttpRequest.newBuilder(URI.create(this.base + "/api/v1/health"))
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .header("Origin", "http://tauri.localhost"),
                null);
        assertEquals(204, tauriPreflight.statusCode());
        assertEquals("http://tauri.localhost",
                tauriPreflight.headers().firstValue("Access-Control-Allow-Origin").orElse(""));

        HttpResponse<String> evilGet = send(
                HttpRequest.newBuilder(URI.create(this.base + "/api/v1/health"))
                        .GET()
                        .header("Origin", "https://evil.example"),
                null);
        assertEquals(200, evilGet.statusCode());
        assertTrue(evilGet.headers().firstValue("Access-Control-Allow-Origin").isEmpty(),
                "白名单之外不下发 CORS 头，浏览器读不到响应");
    }

    /**
     * 跨站“简单请求”不能执行副作用。
     *
     * <p>{@code POST /shutdown} 没有正文、没有自定义头，浏览器不触发预检——只挡预检和响应头
     * 挡不住它：页面照样能让本机停服。这里断言白名单之外的 Origin 在**执行之前**被拒。</p>
     */
    @Test
    void writeFromDisallowedOriginIsRejectedBeforeExecution() throws Exception {
        HttpResponse<String> response = send(
                HttpRequest.newBuilder(URI.create(this.base + "/api/v1/shutdown"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .header("Origin", "https://evil.example"),
                null);

        assertEquals(403, response.statusCode(), response.body());
        assertTrue(response.body().contains("origin-not-allowed"), response.body());
        assertEquals(0, this.shutdownCalls, "被拒的跨站写操作不得执行 handler");
    }

    /** 没有 Origin 的客户端（curl/脚本/桌面壳首跳）不受影响：它不是浏览器发起的跨站请求。 */
    @Test
    void writeWithoutOriginStillWorks() throws Exception {
        HttpResponse<String> response = post("/api/v1/shutdown", null);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(1, this.shutdownCalls);
    }

    /**
     * 陌生 Host 一律拒绝。
     *
     * <p>DNS rebinding 后浏览器认为 `evil.example` 与页面同源，请求会带
     * `Host: evil.example` 打进来；只校验 Origin 等于 Host 的话，白名单会自己放过自己。</p>
     *
     * <p>用裸 socket 发包：Java 的 HttpClient 不允许覆写 Host。</p>
     */
    @Test
    void foreignHostIsRejected() throws Exception {
        String response = rawGet("/api/v1/health", "evil.example");
        assertTrue(response.startsWith("HTTP/1.1 403"), response);
        assertTrue(response.contains("host-not-allowed"), response);

        String local = rawGet("/api/v1/health", "127.0.0.1:" + this.server.port());
        assertTrue(local.startsWith("HTTP/1.1 200"), local);
    }

    private String rawGet(String path, String host) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", this.server.port())) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(this.base + path)).GET(), token);
    }

    private HttpResponse<String> postJson(String path, String token, String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(this.base + path))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)), token);
    }

    private HttpResponse<String> post(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(this.base + path))
                .POST(HttpRequest.BodyPublishers.noBody()), token);
    }

    private HttpResponse<byte[]> getBytes(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(this.base + path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static int[] toInts(byte[] bytes) {
        int[] values = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            values[i] = bytes[i] & 0xFF;
        }
        return values;
    }

    /**
     * 运行时编辑的 PUT/DELETE 子路径必须到达命令分发(默认 context 回 404),
     * 不能被方法门(405)或关会话分支(bad-path/400)吃掉——真后端冒烟抓到过一次回归。
     */
    @Test
    void runtimeEditPutAndDeleteSubpathsReachTheCommandDispatcher() throws Exception {
        String body = "{\"id\":\"p\",\"ceid\":1,\"trigger\":{\"type\":\"periodic\",\"periodMs\":1000}}";
        /* PUT 更新计划/变量值:到达 context(桩回 404)而不是 405 */
        assertEquals(404, putJson("/api/v1/sessions/s1/events/p", null, body).statusCode());
        assertEquals(404, putJson("/api/v1/sessions/s1/svids/1001/value", null, "{\"value\":1}").statusCode());
        /* DELETE 删除计划:到达 context 而不是被当关会话 */
        assertEquals(404, delete("/api/v1/sessions/s1/events/p", null).statusCode());
        /* DELETE 根路径仍是关会话(已有用例覆盖 404 未知会话) */
        assertEquals(404, delete("/api/v1/sessions/missing", null).statusCode());
    }

    private HttpResponse<String> putJson(String path, String token, String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(this.base + path))
                .header("Content-Type", "application/json; charset=utf-8")
                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)), token);
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

    /**
     * 主机实例集合的路由不能被实例前缀抢走。
     *
     * <p>修复前：{@code /api/v1/host-sims/} 先注册，HttpServer 按注册顺序匹配，
     * 无尾斜杠的 {@code /api/v1/host-sims} 也落进实例处理器，
     * 切分得到空数组、取实例 id 时抛异常——连接被无解释地断开，
     * 前端看到的是"无法新建本机主机"。</p>
     */
    @Test
    void hostSimCollectionRouteIsNotShadowedByInstancePrefix() throws Exception {
        HttpResponse<String> list = get("/api/v1/host-sims", null);
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("hosts"), list.body());

        HttpResponse<String> created = postJson("/api/v1/host-sims", null, "{\"session\":\"s1\"}");
        assertEquals(201, created.statusCode(), created.body());
    }

    /**
     * 实例路径缺 id 时要回 400，不能崩。
     */
    @Test
    void hostSimInstancePathWithoutIdIsRejected() throws Exception {
        HttpResponse<String> response = get("/api/v1/host-sims/", null);
        assertTrue(response.statusCode() == 400 || response.statusCode() == 404,
                "空实例 id 应回 4xx，实际 " + response.statusCode() + "：" + response.body());
    }

    /**
     * 主机级压测路由：POST 受理回 202、GET 回状态、未知实例 404、参数错误 400。
     *
     * <p>与会话级 {@code /sessions/{id}/burst} 同一套状态码约定——前端按
     * {@code running}/{@code accepted} 轮询，不能靠 HTTP 200 判断"跑完了"。</p>
     */
    @Test
    void hostBurstAcceptsThenReportsStatus() throws Exception {
        HttpResponse<String> accepted = postJson("/api/v1/host-sims/host-1/burst", null,
                "{\"kind\":\"message\",\"sml\":\"S1F1 W.\",\"count\":3}");
        assertEquals(202, accepted.statusCode(), accepted.body());
        assertTrue(accepted.body().contains("\"accepted\":true"), accepted.body());

        HttpResponse<String> status = get("/api/v1/host-sims/host-1/burst", null);
        assertEquals(200, status.statusCode(), status.body());
        assertTrue(status.body().contains("\"running\":false"), status.body());

        HttpResponse<String> unknown = postJson("/api/v1/host-sims/missing/burst", null,
                "{\"kind\":\"message\",\"sml\":\"S1F1 W.\",\"count\":3}");
        assertEquals(404, unknown.statusCode(), unknown.body());

        HttpResponse<String> badCount = postJson("/api/v1/host-sims/host-1/burst", null,
                "{\"kind\":\"message\",\"sml\":\"S1F1 W.\",\"count\":0}");
        assertEquals(400, badCount.statusCode(), badCount.body());
    }

    /** 主机级压测只接受报文：CEID / 报警是设备侧能力，主机没有对应语义。 */
    @Test
    void hostBurstRejectsNonMessageKinds() throws Exception {
        HttpResponse<String> response = postJson("/api/v1/host-sims/host-1/burst", null,
                "{\"kind\":\"ceid\",\"ceid\":2001,\"count\":1}");
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("unsupported-kind"), response.body());
    }

}
