package com.shimizukenta.secssim.api.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import com.shimizukenta.secssim.app.event.NoticeBus;
import com.shimizukenta.secssim.app.event.NoticeEvent;

/**
 * SSE 报文推送：总线上发布的事件必须以 {@code event: message} 帧到达订阅者。
 */
class SseMessageTest {

    
    @Test
    void publishedMessageReachesSubscriber() throws Exception {
        EventBus bus = new EventBus();
        HttpApiServer server = HttpApiServer.start(new InetSocketAddress("127.0.0.1", 0), AccessControl.open(),
                new StubContext(), null, bus);
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try {
            String base = "http://127.0.0.1:" + server.port();
            String ticket = ticket(base);
            HttpResponse<InputStream> stream = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/events?ticket=" + ticket))
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

    @Test
    void hostInstanceMessagesCarryTheirHostId() throws Exception {
        /* 远程主机的报文由主机实例自己发布：SSE 帧必须带 hostId，
           否则消息监控页无法把"这条是谁发的"分开（远程实例之间会串台）。 */
        EventBus bus = new EventBus();
        HttpApiServer server = HttpApiServer.start(new InetSocketAddress("127.0.0.1", 0), AccessControl.open(),
                new StubContext(), null, bus);
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try {
            String base = "http://127.0.0.1:" + server.port();
            String ticket = ticket(base);
            HttpResponse<InputStream> stream = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/events?ticket=" + ticket))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            Future<String> reader = pool.submit(() -> readUntilMessage(stream));

            MessageEvent event = MessageEvent.ofHost("s1", "host-7", "in",
                    1, 3, true, "<L[1] <U4 1001>>", 123L);
            for (int i = 0; i < 30 && !reader.isDone(); i++) {
                bus.publish(event);
                Thread.sleep(100L);
            }

            String text = reader.get(5L, TimeUnit.SECONDS);
            assertTrue(text.contains("\"hostId\":\"host-7\""), text);
            /* 设备会话本身的报文仍然是空串，前端据此区分"设备侧发布" */
            assertTrue(text.contains("\"sessionId\":\"s1\""), text);
        } finally {
            pool.shutdownNow();
            server.close();
        }
    }

    @Test
    void nonObjectNoticePayloadStaysValidJson() throws Exception {
        /* payload 不一定是 JSON 对象（数组/字符串/数字）；过去一律丢掉首字符再拼接，
           "[1,2]" 会变成 {...,"at":456,1,2]} 这种非法帧，前端 SSE 解析直接断流。 */
        EventBus bus = new EventBus();
        NoticeBus notices = new NoticeBus();
        HttpApiServer server = HttpApiServer.start(new InetSocketAddress("127.0.0.1", 0), AccessControl.open(),
                new StubContext(), null, bus);
        server.subscribeNotices(notices);
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try {
            String base = "http://127.0.0.1:" + server.port();
            String ticket = ticket(base);
            HttpResponse<InputStream> stream = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/events?ticket=" + ticket))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            Future<String> reader = pool.submit(() -> readUntil(stream, "event: alarm"));

            for (int i = 0; i < 30 && !reader.isDone(); i++) {
                notices.publish(new NoticeEvent("s1", NoticeEvent.ALARM, "[1,2]", 456L));
                Thread.sleep(100L);
            }

            String text = reader.get(5L, TimeUnit.SECONDS);

            assertTrue(text.contains("event: alarm"), "没有收到报警帧: " + text);
            assertTrue(text.contains("\"payload\":[1,2]"), "非对象载荷必须放进 payload 字段: " + text);
            assertTrue(text.contains("\"sessionId\":\"s1\""), text);
            assertTrue(text.contains("\"at\":456"), text);
        } finally {
            pool.shutdownNow();
            server.close();
        }
    }

    @Test
    void emptyArrayNoticePayloadIsNotSwallowed() throws Exception {
        /* "[]" 只有两个字符：按长度 ≤2 判空会把它当“没有载荷”整个丢掉，
           前端就永远看不到这个通知的内容。只有空串与 {} 才该丢。 */
        EventBus bus = new EventBus();
        NoticeBus notices = new NoticeBus();
        HttpApiServer server = HttpApiServer.start(new InetSocketAddress("127.0.0.1", 0), AccessControl.open(),
                new StubContext(), null, bus);
        server.subscribeNotices(notices);
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try {
            String base = "http://127.0.0.1:" + server.port();
            String ticket = ticket(base);
            HttpResponse<InputStream> stream = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/events?ticket=" + ticket))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            Future<String> reader = pool.submit(() -> readUntil(stream, "event: session"));

            for (int i = 0; i < 30 && !reader.isDone(); i++) {
                notices.publish(new NoticeEvent("s1", NoticeEvent.SESSION, "[]", 789L));
                Thread.sleep(100L);
            }

            String text = reader.get(5L, TimeUnit.SECONDS);

            assertTrue(text.contains("\"payload\":[]"), "空数组是合法载荷，不能丢: " + text);
            assertTrue(text.contains("\"at\":789"), text);

            /* 子串对不代表整帧合法：拼接残留（如多余的逗号）也会让前端 JSON.parse 静默失败 */
            int at = text.indexOf("event: session");
            String data = text.substring(text.indexOf("data: ", at) + "data: ".length(),
                    text.indexOf("\n\n", at));
            assertDoesNotThrow(() -> com.shimizukenta.jsonhub.JsonHub.fromJson(data),
                    "通知帧必须是合法 JSON: " + data);
        } finally {
            pool.shutdownNow();
            server.close();
        }
    }

    private static String ticket(String base) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/ticket"))
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"ticket\":\"([0-9a-f]+)\"").matcher(response.body());
        if (!matcher.find()) {
            throw new IllegalStateException("没有拿到票据: " + response.body());
        }
        return matcher.group(1);
    }

    private static String readUntilMessage(HttpResponse<InputStream> stream) throws Exception {
        return readUntil(stream, "event: message");
    }

    /**
     * 读到指定事件头<b>且该帧已结束</b>才返回。
     *
     * <p>只看 marker 会在 {@code event:} 行到、{@code data:} 行还没到时返回，
     * 断言偶发失败；空行才是 SSE 帧边界。</p>
     */
    private static String readUntil(HttpResponse<InputStream> stream, String marker) throws Exception {
        StringBuilder text = new StringBuilder();
        byte[] buffer = new byte[256];

        try (InputStream body = stream.body()) {
            int read;
            while ((read = body.read(buffer)) > 0) {
                text.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                int at = text.indexOf(marker);
                if (at >= 0 && text.indexOf("\n\n", at) >= 0) {
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
