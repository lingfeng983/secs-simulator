package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * 远程服务端形态的端到端用例：<b>server 模式 + TLS + 直连访问</b>。
 *
 * <p>真的用当前 JVM 的 classpath 启动 {@link CliMain}，真的生成 PKCS#12 证书，
 * 从 HTTPS 客户端匿名访问。覆盖：启动握手（ready.txt 不含任何凭据）、
 * 健康检查直接给出部署信息、各业务端点无需令牌、SSE 票据、停机与审计主体。</p>
 */
class RemoteServerTlsTest {

    private static final String KEY_PASS = "changeit";

    @Test
    @Timeout(150)
    void serverModeWithTlsServesDirectlyWithoutCredentials(@TempDir Path tempDir) throws Exception {
        Path javaExe = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java");
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "keytool.exe" : "keytool");
        assumeTrue(Files.isRegularFile(javaExe), "java not found: " + javaExe);
        assumeTrue(Files.isRegularFile(keytool), "keytool not found: " + keytool);
        assumeTrue(!System.getProperty("java.class.path", "").isBlank(),
                "需要 surefire 提供 classpath 才能启动真实服务端进程");

        /* 1) 一次性自签证书（SAN 同时覆盖 localhost 与 127.0.0.1） */
        Path keystore = tempDir.resolve("server.p12");
        Process keygen = new ProcessBuilder(
                keytool.toString(),
                "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost", "-validity", "1",
                "-storetype", "PKCS12", "-keystore", keystore.toString(),
                "-storepass", KEY_PASS, "-keypass", KEY_PASS, "-noprompt",
                "-ext", "SAN=DNS:localhost,IP:127.0.0.1")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        assertTrue(keygen.waitFor(60, TimeUnit.SECONDS), "keytool 生成 keystore 超时");
        assertEquals(0, keygen.exitValue(), "keytool 生成 keystore 失败");

        Path workDir = Files.createDirectories(tempDir.resolve("work"));
        Path out = tempDir.resolve("server.out");

        /* 2) 真实进程：server + 回环 + 自签 TLS，没有任何凭据参数 */
        Process server = new ProcessBuilder(
                javaExe.toString(),
                "-cp", System.getProperty("java.class.path"),
                "com.shimizukenta.secssim.cli.CliMain",
                "--mode=server",
                "--bind=127.0.0.1",
                "--port=0",
                "--work-dir=" + workDir,
                "--tls-keystore=" + keystore,
                "--tls-password=" + KEY_PASS,
                "--log-level=error")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(out.toFile()))
                .start();

        try {
            Path readyFile = workDir.resolve("ready.txt");
            int port = awaitPort(readyFile, server, out);

            /* 握手文件里不应再有任何凭据字段 */
            String ready = Files.readString(readyFile, StandardCharsets.UTF_8);
            assertFalse(ready.contains("token"), "ready.txt 不应再带令牌: " + ready);
            assertFalse(ready.contains("accounts"), "ready.txt 不应再带账号: " + ready);

            HttpClient client = HttpClient.newBuilder()
                    .sslContext(trustAll())
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            String base = "https://127.0.0.1:" + port;

            /* 3) 匿名即可拿到完整部署信息，业务端点全部直连 */
            HttpResponse<String> health = send(client, base, "/api/v1/health", "GET", null);
            assertEquals(200, health.statusCode(), health.body());
            assertTrue(health.body().contains("\"ok\":true"), health.body());
            assertTrue(health.body().contains("\"mode\":\"server\""), health.body());
            assertTrue(health.body().contains("\"version\""), health.body());
            assertTrue(health.body().contains("\"role\":\"admin\""), health.body());

            assertEquals(200, send(client, base, "/api/v1/sessions", "GET", null).statusCode());
            assertEquals(200, send(client, base, "/api/v1/host-sims", "GET", null).statusCode());
            assertEquals(200, send(client, base, "/api/v1/overview", "GET", null).statusCode());

            /* config-preview 不再要求角色：匿名可达，内容非法时回业务错误而不是 401 */
            int preview = send(client, base, "/api/v1/config-preview", "POST", "{}").statusCode();
            assertFalse(preview == 401 || preview == 403, "直连访问不应出现鉴权拒绝: " + preview);

            /* 4) SSE：HTTPS 下换一次性票据并建流 */
            HttpResponse<String> ticket = send(client, base, "/api/v1/ticket", "POST", null);
            assertEquals(200, ticket.statusCode(), ticket.body());
            String value = extract(ticket.body(), "ticket");
            assertFalse(value.isBlank(), "ticket 为空: " + ticket.body());

            HttpRequest events = HttpRequest.newBuilder(URI.create(base + "/api/v1/events?ticket=" + value))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<InputStream> stream = client.send(events, HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, stream.statusCode());
            try (InputStream in = stream.body();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                assertEquals(": connected", reader.readLine(), "SSE 首帧应是连接确认");
            }

            /* 5) 停机 + 审计：直连访问下主体是 local */
            assertEquals(200, send(client, base, "/api/v1/shutdown", "POST", null).statusCode());
            assertTrue(server.waitFor(20, TimeUnit.SECONDS), "服务端收到停机后应在 20s 内退出");

            String audit = readIfExists(workDir.resolve("logs").resolve("audit.jsonl"));
            assertTrue(audit.contains("\"actor\":\"local\""), "审计主体应为 local: " + audit);
            assertTrue(audit.contains("\"action\":\"shutdown\""), "审计里应有停机动作: " + audit);
        }
        finally {
            if (server.isAlive()) {
                server.destroyForcibly();
                server.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static HttpResponse<String> send(HttpClient client, String base, String path,
                                             String method, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10));
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json; charset=utf-8")
                    .POST(body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 等 ready.txt 并解析 http 端口；进程提前退出时把输出尾部带进断言信息。 */
    private static int awaitPort(Path readyFile, Process server, Path out) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(readyFile) && Files.size(readyFile) > 0L) {
                Map<String, String> ready = parse(Files.readString(readyFile, StandardCharsets.UTF_8));
                String http = ready.getOrDefault("http", "");
                assertFalse(http.isBlank(), "ready.txt 缺少 http: " + ready);
                return Integer.parseInt(http.trim());
            }
            if (!server.isAlive()) {
                throw new AssertionError("服务端在就绪前退出（exit=" + server.exitValue()
                        + "）\n--- server.out ---\n" + readIfExists(out));
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("服务端 30s 内未就绪\n--- server.out ---\n" + readIfExists(out));
    }

    private static Map<String, String> parse(String text) {
        Map<String, String> values = new HashMap<>();
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return values;
    }

    private static String extract(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) {
            return "";
        }
        int from = start + needle.length();
        int end = json.indexOf('"', from);
        return end < 0 ? "" : json.substring(from, end);
    }

    private static String readIfExists(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        }
        catch (Exception e) {
            return "<unreadable: " + e + ">";
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static SSLContext trustAll() throws Exception {
        TrustManager[] managers = { new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        } };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers, new SecureRandom());
        return context;
    }
}
