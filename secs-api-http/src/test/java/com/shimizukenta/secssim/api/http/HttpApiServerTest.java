package com.shimizukenta.secssim.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
    private TriggerOutcome outcome = TriggerOutcome.OK;

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
            public String previewJson(String sessionId, long fromMs, long toMs, int buckets) {
                if ("missing".equals(sessionId)) {
                    return null;
                }
                return "{\"from\":" + fromMs + ",\"to\":" + toMs
                        + ",\"buckets\":" + buckets + ",\"series\":[]}";
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
    void healthNeedsNoToken() throws Exception {
        HttpResponse<String> response = get("/api/v1/health", null);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"mode\":\"integrated\""), response.body());
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
    void shutdownRequiresTokenAndPost() throws Exception {
        assertEquals(401, get("/api/v1/shutdown", null).statusCode());
        assertEquals(405, get("/api/v1/shutdown", TOKEN).statusCode());
    }

    @Test
    void eventsRejectNonGet() throws Exception {
        assertEquals(405, post("/api/v1/events", TOKEN).statusCode());
    }

    @Test
    void tokenInQueryIsUrlDecoded() throws Exception {
        ApiContext context = new ApiContext() {
            @Override
            public String mode() {
                return "test";
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
        };

        HttpApiServer special = HttpApiServer.start(new InetSocketAddress("127.0.0.1", 0), "a+b", context);
        try {
            String specialBase = "http://127.0.0.1:" + special.port();

            assertEquals(200, send(HttpRequest.newBuilder(
                    URI.create(specialBase + "/api/v1/sessions?token=a%2Bb")).GET(), null).statusCode(),
                    "百分号编码的 + 必须解码回 +");
            assertEquals(401, send(HttpRequest.newBuilder(
                    URI.create(specialBase + "/api/v1/sessions?token=a+b")).GET(), null).statusCode(),
                    "裸 + 按 query 语义解码成空格，不应通过");
        } finally {
            special.close();
        }
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(this.base + path)).GET(), token);
    }

    private HttpResponse<String> post(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(this.base + path))
                .POST(HttpRequest.BodyPublishers.noBody()), token);
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
