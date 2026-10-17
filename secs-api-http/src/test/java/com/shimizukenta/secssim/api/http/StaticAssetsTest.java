package com.shimizukenta.secssim.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 静态资源托管：同源提供前端，浏览器不需要任何 CORS 配置。
 */
class StaticAssetsTest {

    private static final String TOKEN = "t";

    @TempDir
    Path root;

    private HttpApiServer server;
    private String base;

    @BeforeEach
    void startServer() throws IOException {
        Files.writeString(this.root.resolve("index.html"), "<html><body>MARKER</body></html>",
                StandardCharsets.UTF_8);
        Files.writeString(this.root.resolve("app.js"), "console.log('hi');", StandardCharsets.UTF_8);

        this.server = HttpApiServer.start(new InetSocketAddress("127.0.0.1", 0), TOKEN,
                new StubContext(), this.root);
        this.base = "http://127.0.0.1:" + this.server.port();
    }

    @AfterEach
    void stopServer() {
        this.server.close();
    }

    @Test
    void servesIndexHtmlAtRoot() throws Exception {
        HttpResponse<String> response = get("/");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("MARKER"));
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
    }

    @Test
    void servesJavaScriptWithProperContentType() throws Exception {
        HttpResponse<String> response = get("/app.js");

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"));
        assertTrue(response.body().contains("console.log"));
    }

    @Test
    void unknownPathFallsBackToIndexForSpa() throws Exception {
        HttpResponse<String> response = get("/some/deep/route");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("MARKER"), "未知路径回落到 index.html");
    }

    @Test
    void pathTraversalDoesNotLeakFilesOutsideRoot() throws Exception {
        Path secret = this.root.getParent().resolve("secret.txt");
        Files.writeString(secret, "SECRET-CONTENT", StandardCharsets.UTF_8);

        HttpResponse<String> response = get("/%2e%2e/secret.txt");
        assertFalse(response.body().contains("SECRET-CONTENT"), "不得越过 web root");
    }

    @Test
    void apiIsNotShadowedByStaticHandler() throws Exception {
        HttpResponse<String> health = get("/api/v1/health");
        assertEquals(200, health.statusCode());
        assertTrue(health.body().contains("\"ok\":true"));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(HttpRequest.newBuilder(URI.create(this.base + path))
                                .timeout(Duration.ofSeconds(10)).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 最小上下文桩。 */
    private static final class StubContext implements ApiContext {

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
    }
}
