package com.shimizukenta.secssim.api.http;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.app.event.EventBus;
import com.shimizukenta.secssim.app.event.MessageEvent;

/**
 * SSE 报文推送：总线上发布的事件必须以 {@code event: message} 帧到达订阅者。
 */
class SseMessageTest {

    private static final String TOKEN = "t";

    @Test
    void publishedMessageReachesSubscriber() throws Exception {
        EventBus bus = new EventBus();
        HttpApiServer server = HttpApiServer.start(new InetSocketAddress("127.0.0.1", 0), TOKEN,
                new StubContext(), null, bus);
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try {
            String base = "http://127.0.0.1:" + server.port();
            HttpResponse<InputStream> stream = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/events?token=" + TOKEN))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            Future<String> reader = pool.submit(() -> readUntilMessage(stream));

            MessageEvent event = new MessageEvent("s1", "in", 1, 3, true, "<L[2] U4 1001>", 123L);
            for (int i = 0; i < 30 && !reader.isDone(); i++) {
                bus.publish(event);
                Thread.sleep(100L);
            }

            String text = reader.get(5L, TimeUnit.SECONDS);

            assertTrue(text.contains("event: message"), "没有收到报文帧: " + text);
            assertTrue(text.contains("\"stream\":1"), text);
            assertTrue(text.contains("\"function\":3"), text);
            assertTrue(text.contains("\"direction\":\"in\""), text);
        } finally {
            pool.shutdownNow();
            server.close();
        }
    }

    private static String readUntilMessage(HttpResponse<InputStream> stream) throws Exception {
        StringBuilder text = new StringBuilder();
        byte[] buffer = new byte[256];

        try (InputStream body = stream.body()) {
            int read;
            while ((read = body.read(buffer)) > 0) {
                text.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                if (text.indexOf("event: message") >= 0) {
                    return text.toString();
                }
            }
        }
        return text.toString();
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
