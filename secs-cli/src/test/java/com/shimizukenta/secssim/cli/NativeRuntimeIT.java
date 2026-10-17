package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.hsmsss.HsmsSsProtocol;

/**
 * 原生镜像的端到端测试（默认跳过，需显式指定 {@code -Dsecs.smoke.exe}）。
 *
 * <p>覆盖：进程启动握手、健康检查、鉴权、SSE 首帧、HSMS-SS 异步收发、优雅停机。
 * 它用 JVM 侧的主动连接去打原生镜像的被动端口，因此真正验证了
 * {@code AsynchronousSocketChannel} 在 native 下的能力；只连上端口是测不出这个的。
 * </p>
 */
class NativeRuntimeIT {

    @Test
    void nativeRuntimeServesApiAndSecs() throws Exception {
        String exe = System.getProperty("secs.smoke.exe", "");
        assumeTrue(!exe.isBlank(), "skip: -Dsecs.smoke.exe not set");
        Path exePath = Path.of(exe);
        assumeTrue(Files.isRegularFile(exePath), "skip: not a file: " + exe);

        int hsmsPort = freePort();
        Path workDir = Files.createTempDirectory("secs-sim-");
        Process process = new ProcessBuilder(
                exePath.toString(),
                "--mode=probe",
                "--hsms-port", Integer.toString(hsmsPort),
                "--work-dir", workDir.toString())
                .redirectErrorStream(true)
                .start();

        try {
            String ready = awaitReady(process);
            int httpPort = intToken(ready, "http=");
            String token = textToken(ready, "token=");

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + "/api/v1/health"))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, health.statusCode(), health.body());
            assertTrue(health.body().contains("\"ok\":true"), health.body());
            assertTrue(health.body().contains("\"mode\":\"probe\""), health.body());
            assertTrue(health.body().contains("\"utf8\":true"), "中文路径回归失败: " + health.body());
            assertTrue(health.body().contains("\"vm\":\"runtime\""), "不是原生镜像: " + health.body());

            assertSseStreams(httpPort, token, client);

            HttpResponse<String> denied = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + "/api/v1/shutdown"))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(401, denied.statusCode(), "未带 token 的停机请求必须被拒绝");

            assertS1F1GetsS1F2(hsmsPort);

            HttpResponse<String> shutdown = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + "/api/v1/shutdown"))
                            .header("Authorization", "Bearer " + token)
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, shutdown.statusCode(), shutdown.body());

            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "native 进程没有退出");
            assertEquals(0, process.exitValue(), "native 进程退出码");
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void nativeSessionAnswersS1F3FromValueEngine() throws Exception {
        String exe = System.getProperty("secs.smoke.exe", "");
        assumeTrue(!exe.isBlank(), "skip: -Dsecs.smoke.exe not set");
        Path exePath = Path.of(exe);
        assumeTrue(Files.isRegularFile(exePath), "skip: not a file: " + exe);

        int hsmsPort = freePort();
        Path workDir = Files.createTempDirectory("secs-sim-demo-");
        Process process = new ProcessBuilder(
                exePath.toString(),
                "--mode=probe",
                "--demo-session",
                "--hsms-port", Integer.toString(hsmsPort),
                "--work-dir", workDir.toString())
                .redirectErrorStream(true)
                .start();

        try {
            String ready = awaitReady(process);
            int httpPort = intToken(ready, "http=");
            String token = textToken(ready, "token=");
            assertTrue(ready.contains("sessions=1"), "演示会话未创建: " + ready);

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + "/api/v1/health"))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertTrue(health.body().contains("\"sessions\":1"), health.body());

            /* 会话列表：真实运行时的 sessionsJson 组装 */
            HttpResponse<String> sessions = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + "/api/v1/sessions"))
                            .header("Authorization", "Bearer " + token)
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, sessions.statusCode(), sessions.body());
            assertTrue(sessions.body().contains("\"id\":\"demo\""), sessions.body());
            assertTrue(sessions.body().contains("\"state\":\"OPEN\""), sessions.body());
            assertTrue(sessions.body().contains("\"units\":2"), sessions.body());

            /* 没有主机在线时触发应得到 409（设备状态），而不是 500（服务器错误） */
            assertEquals(409, trigger(hsnsBase(httpPort), token).statusCode(),
                    "无主机会话时触发 S6F11 应返回 409");

            HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
            config.protocol(HsmsSsProtocol.ACTIVE);
            config.socketAddress(new InetSocketAddress("127.0.0.1", hsmsPort));
            config.deviceId(10);

            try (SecsCommunicator comm = HsmsSsCommunicator.open(config)) {
                comm.openAndWaitUntilCommunicating();

                var builder = com.shimizukenta.secs.secs2.Secs2.getBuilder();
                Optional<SecsMessage> reply = comm.send(1, 3, true,
                        builder.list(builder.uint4(1001L), builder.uint4(1090L)));
                assertTrue(reply.isPresent(), "native 会话没有应答 S1F3");
                assertEquals(2, reply.get().secs2().size());

                double temperature = com.shimizukenta.secssim.runtime.session.Secs2Read
                        .asDouble(reply.get().secs2().get(0));
                int unitId = com.shimizukenta.secssim.runtime.session.Secs2Read
                        .asInt(reply.get().secs2().get(1));

                assertTrue(temperature >= 0.0 && temperature <= 400.0, "温度越界: " + temperature);
                assertEquals(1, unitId, "UNIT_ID 应为 CH1");

                /* 主机在线时，API 触发的 S6F11 才能真正发出去 */
                assertEquals(202, trigger(hsnsBase(httpPort), token).statusCode(),
                        "主机在线时触发 S6F11 应为 202");
            }

            client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + "/api/v1/shutdown"))
                            .header("Authorization", "Bearer " + token)
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());

            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "native 进程没有退出");
            assertEquals(0, process.exitValue());
        } finally {
            process.destroyForcibly();
        }
    }

    private static String hsnsBase(int httpPort) {
        return "http://127.0.0.1:" + httpPort + "/api/v1/sessions/demo/ceid/2001";
    }

    private static HttpResponse<String> trigger(String url, String token) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(HttpRequest.newBuilder(URI.create(url))
                                .header("Authorization", "Bearer " + token)
                                .timeout(Duration.ofSeconds(10))
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void assertSseStreams(int httpPort, String token, HttpClient client) throws Exception {
        HttpResponse<InputStream> sse = client.send(
                HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + httpPort + "/api/v1/events?token=" + token))
                        .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());

        assertEquals(200, sse.statusCode());
        try (InputStream body = sse.body()) {
            byte[] buffer = new byte[256];
            int read = body.read(buffer);
            assertTrue(read > 0, "SSE 没有收到任何数据");
            String chunk = new String(buffer, 0, read, StandardCharsets.UTF_8);
            assertTrue(chunk.contains("event: heartbeat"), "SSE 首帧异常: " + chunk);
        }
    }

    private static void assertS1F1GetsS1F2(int hsmsPort) throws Exception {
        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.protocol(HsmsSsProtocol.ACTIVE);
        config.socketAddress(new InetSocketAddress("127.0.0.1", hsmsPort));
        config.deviceId(10);

        try (SecsCommunicator comm = HsmsSsCommunicator.open(config)) {
            comm.openAndWaitUntilCommunicating();
            Optional<SecsMessage> reply = comm.send(1, 1, true);
            assertTrue(reply.isPresent(), "没有收到 S1F2");
            assertEquals(1, reply.get().getStream());
            assertEquals(2, reply.get().getFunction());
        }
    }

    private static String awaitReady(Process process) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("READY ")) {
                return line;
            }
        }
        throw new IllegalStateException("进程在 READY 之前结束, exit=" + process.exitValue());
    }

    private static int intToken(String line, String prefix) {
        return Integer.parseInt(textToken(line, prefix));
    }

    private static String textToken(String line, String prefix) {
        for (String token : line.split(" ")) {
            if (token.startsWith(prefix)) {
                return token.substring(prefix.length());
            }
        }
        throw new IllegalStateException("READY 行缺少 " + prefix + ": " + line);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
