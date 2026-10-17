package com.shimizukenta.secssim.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Locale;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shimizukenta.secssim.api.http.ApiContext;
import com.shimizukenta.secssim.api.http.HttpApiServer;

/**
 * HTTPS 冒烟：用 keytool 生成一次性自签证书，客户端信任所有证书访问 health。
 */
class TlsServerTest {

    @Test
    void httpsHealthWorksWithGeneratedKeystore(@TempDir Path tempDir) throws Exception {
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                        ? "keytool.exe" : "keytool");
        assumeTrue(Files.isRegularFile(keytool), "keytool not found: " + keytool);

        Path keystore = tempDir.resolve("server.p12");
        Process keygen = new ProcessBuilder(
                keytool.toString(),
                "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost", "-validity", "1",
                "-storetype", "PKCS12", "-keystore", keystore.toString(),
                "-storepass", "changeit", "-keypass", "changeit", "-noprompt",
                "-ext", "SAN=DNS:localhost,IP:127.0.0.1")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        assertEquals(0, keygen.waitFor(), "keytool 生成 keystore 失败");

        SSLContext serverTls = TlsSupport.load(keystore, "changeit");
        HttpApiServer server = HttpApiServer.start(
                new InetSocketAddress("127.0.0.1", 0), "token", new StubContext(), null, null, serverTls);

        try {
            HttpClient client = HttpClient.newBuilder()
                    .sslContext(trustAll())
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("https://localhost:" + server.port() + "/api/v1/health"))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.body().contains("\"ok\":true"), response.body());
        } finally {
            server.close();
        }
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

    private static final class StubContext implements ApiContext {

        @Override
        public String mode() {
            return "server";
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
        }
    }
}
