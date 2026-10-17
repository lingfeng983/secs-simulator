package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
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
 * native 可执行文件的端到端冒烟测试（默认跳过）。
 *
 * <p>用法：先构建原生镜像，再指定路径运行：
 * </p>
 * <pre>
 * mvn -Pnative -pl secs-cli -am package
 * mvn -pl secs-cli -Dtest=NativeSmokeIT -Dsecs.smoke.exe=secs-cli/target/secs-smoke.exe test
 * </pre>
 * <p>它用 JVM 侧的 HSMS 主动连接去打原生镜像的被动端口，
 * 因此同时验证了 {@code AsynchronousSocketChannel} 在 native 下的收发能力；
 * 仅仅"端口能连上"是测不出这个的。
 * </p>
 */
class NativeSmokeIT {

    @Test
    void nativeBinaryServesSecsHttpAndUtf8() throws Exception {
        String exe = System.getProperty("secs.smoke.exe", "");
        assumeTrue(!exe.isBlank(), "skip: -Dsecs.smoke.exe not set");

        Path exePath = Path.of(exe);
        assumeTrue(Files.isRegularFile(exePath), "skip: not a file: " + exe);

        int hsmsPort = freePort();
        Path workDir = Files.createTempDirectory("secs-smoke-");
        Process process = new ProcessBuilder(
                exePath.toString(),
                "--hsms-port", Integer.toString(hsmsPort),
                "--work-dir", workDir.toString())
                .redirectErrorStream(true)
                .start();

        try {
            int httpPort = awaitReady(process);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> health = client.send(
                    request("http://127.0.0.1:" + httpPort + "/health"),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, health.statusCode(), health.body());
            assertTrue(health.body().contains("\"ok\":true"), health.body());
            assertTrue(health.body().contains("\"utf8\":true"), "中文路径回归失败: " + health.body());
            assertTrue(health.body().contains("\"vm\":\"runtime\""), "not a native image: " + health.body());

            HttpResponse<String> sse = client.send(
                    request("http://127.0.0.1:" + httpPort + "/sse"),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, sse.statusCode());
            assertTrue(countOccurrences(sse.body(), "event: tick") >= 3, "SSE 未推送: " + sse.body());

            assertS1F1GetsS1F2(hsmsPort);

            client.send(request("http://127.0.0.1:" + httpPort + "/shutdown"),
                    HttpResponse.BodyHandlers.discarding());
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "native process did not exit");
            assertEquals(0, process.exitValue(), "native process exit code");
        } finally {
            process.destroyForcibly();
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
            assertTrue(reply.isPresent(), "no S1F2 reply");
            assertEquals(1, reply.get().getStream());
            assertEquals(2, reply.get().getFunction());
        }
    }

    private static HttpRequest request(String uri) {
        return HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
    }

    private static int awaitReady(Process process) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("READY ")) {
                for (String token : line.split(" ")) {
                    if (token.startsWith("http=")) {
                        return Integer.parseInt(token.substring("http=".length()));
                    }
                }
            }
        }
        throw new IllegalStateException("native process ended before READY, exit=" + process.exitValue());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
