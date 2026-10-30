package com.shimizukenta.secssim.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.app.event.EventBus;

/**
 * 卡在慢正文上的请求不能占满请求线程。
 *
 * <p>JDK 的 HttpServer 把每个请求交给执行器；处理器在 {@code readBody} 里等正文时
 * 会一直占着那个线程。固定 8 线程池时，十来条"声明了 Content-Length 却只发一个字节"
 * 的连接就能让 health/轮询/取票全部排队，界面表现为"后端卡死"——</p>
 *
 * <p>本用例开 10 条这样的半截请求（超过旧实现的 8 个线程），再要求 health 在 2 秒内
 * 响应。虚拟线程在等 socket 时卸载载体线程，所以成立；用
 * {@code -Dsecssim.http.virtual=false} 退回平台线程池时这里会超时失败，
 * 这正说明该开关确实是"调试回退"而不是等价替代。</p>
 */
class HalfSentBodyStarvationTest {

    /** 超过旧实现的 8 个请求线程，才能暴露"半截正文占线程"的问题。 */
    private static final int STUCK_REQUESTS = 10;

    @Test
    void halfSentBodiesDoNotExhaustRequestThreads() throws Exception {
        HttpApiServer server = HttpApiServer.start(new InetSocketAddress("127.0.0.1", 0), AccessControl.open(),
                new StubContext(), null, new EventBus());
        String host = "127.0.0.1:" + server.port();

        List<Socket> stuck = new ArrayList<>();
        try {
            for (int i = 0; i < STUCK_REQUESTS; i++) {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 2000);
                OutputStream out = socket.getOutputStream();
                /* 声明 1000 字节只发 1 字节：处理器会停在 readBody 上 */
                out.write(("POST /api/v1/sessions HTTP/1.1\r\nHost: " + host
                        + "\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n{")
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                stuck.add(socket);
                /* 给服务端一点时间把请求派发到处理器上 */
                Thread.sleep(30L);
            }

            long startedAt = System.nanoTime();
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(URI.create("http://" + host + "/api/v1/health"))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertEquals(200, health.statusCode(), "慢正文占住请求线程后 health 仍应响应");
            assertTrue(elapsedMs < 2000L,
                    STUCK_REQUESTS + " 条半截正文后 health 仍应立刻响应，实际 " + elapsedMs + "ms");
        } finally {
            for (Socket socket : stuck) {
                try {
                    socket.close();
                } catch (java.io.IOException ignored) {
                    /* 收尾：关不掉不影响断言 */
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
