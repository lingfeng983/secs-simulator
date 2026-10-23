package com.shimizukenta.secssim.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.app.event.EventBus;
import com.shimizukenta.secssim.app.event.MessageEvent;

/**
 * 客户端只连不读时，看门狗必须把连接收走。
 *
 * <p>模拟办法：用一个不读数据的裸 socket，让服务端把 TCP 窗口写满；
 * 之后写入停滞，看门狗应在 stall 超时后断开并释放订阅者。
 * </p>
 */
class SseStallTest {

    private static final String TOKEN = "t";
    private static final String STALL_PROPERTY = "secssim.sse.stallTimeoutMs";

    @Test
    void stalledClientIsDroppedByWatchdog() throws Exception {
        System.setProperty(STALL_PROPERTY, "800");

        EventBus bus = new EventBus();
        HttpApiServer server = HttpApiServer.start(new InetSocketAddress("127.0.0.1", 0), TOKEN,
                new StubContext(), null, bus);
        Socket socket = new Socket();

        try {
            String base = "http://127.0.0.1:" + server.port();
            String ticket = ticket(base);

            socket.setReceiveBufferSize(1024);
            socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 5000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET /api/v1/events?ticket=" + ticket + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\nConnection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();

            /* 读到连接确认就停手，之后一个字节都不读 */
            InputStream in = socket.getInputStream();
            byte[] head = new byte[64];
            assertTrue(in.read(head) > 0, "至少应收到 SSE 注释帧");

            /* 读到首个字节只说明响应头已经发出来了；服务端把这条流登记进 activeStreams
               是另外一步，两者之间有时间窗。直接断言等于赌时序，应等它出现。
               （本用例要验证的是"卡死会被看门狗踢掉"，不是"登记是原子的"——
               所以这里等就绪，不改变用例意图。） */
            long registeredBy = System.currentTimeMillis() + 5_000L;
            while (System.currentTimeMillis() < registeredBy && server.activeStreams() != 1) {
                Thread.sleep(10L);
            }
            assertEquals(1, server.activeStreams(), "连接应已登记");

            /* 用大报文体灌满窗口：2000 字符 × 1200 条 ≈ 2.4MB */
            String body = "<L[1] A".repeat(200);
            for (int i = 0; i < 1200; i++) {
                bus.publish(new MessageEvent("s1", "in", 6, 11, true, body, System.currentTimeMillis()));
            }

            /* 看门狗每 5 秒巡检一次，给足 20 秒 */
            long deadline = System.currentTimeMillis() + 20_000L;
            while (System.currentTimeMillis() < deadline && server.activeStreams() > 0) {
                Thread.sleep(200L);
            }

            assertEquals(0, server.activeStreams(), "停滞的客户端应被看门狗清理");
        } finally {
            socket.close();
            server.close();
            System.clearProperty(STALL_PROPERTY);
        }
    }

    private static String ticket(String base) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/ticket"))
                        .header("Authorization", "Bearer " + TOKEN)
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        Matcher matcher = Pattern.compile("\"ticket\":\"([0-9a-f]+)\"").matcher(response.body());
        if (!matcher.find()) {
            throw new IllegalStateException("没有拿到票据: " + response.body());
        }
        return matcher.group(1);
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
