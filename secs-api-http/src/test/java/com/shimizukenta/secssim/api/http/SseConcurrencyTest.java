package com.shimizukenta.secssim.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.app.event.EventBus;

/**
 * SSE 长连接不能拖垮普通请求。
 *
 * <p>这是一个回归测试：早先 SSE 与 REST 共用一个固定 4 线程的池，
 * 打开 4 个浏览器标签页就能让 health/sessions 全部排队超时。
 * </p>
 */
class SseConcurrencyTest {

    private static final String TOKEN = "t";
    private static final int STREAMS = 6;

    @Test
    void manyStreamsDoNotStarveRequestPool() throws Exception {
        HttpApiServer server = HttpApiServer.start(new InetSocketAddress("127.0.0.1", 0), TOKEN,
                new StubContext(), null, new EventBus());
        String base = "http://127.0.0.1:" + server.port();

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        List<HttpResponse<InputStream>> streams = new ArrayList<>();

        try {
            for (int i = 0; i < STREAMS; i++) {
                HttpResponse<InputStream> stream = client.send(
                        HttpRequest.newBuilder(URI.create(base + "/api/v1/events?token=" + TOKEN))
                                .timeout(Duration.ofSeconds(10)).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                assertEquals(200, stream.statusCode(), "第 " + (i + 1) + " 个 SSE 连接应建立成功");
                streams.add(stream);
            }

            long startedAt = System.nanoTime();
            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/health"))
                            .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertEquals(200, health.statusCode());
            assertTrue(elapsedMs < 2000L, "SSE 占满 " + STREAMS + " 条连接后 health 仍应立刻响应，实际 " + elapsedMs + "ms");
        } finally {
            for (HttpResponse<InputStream> stream : streams) {
                try {
                    stream.body().close();
                } catch (Exception ignored) {
                    /* 测试收尾：关不掉也不影响断言 */
                }
            }
            server.close();
        }
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
