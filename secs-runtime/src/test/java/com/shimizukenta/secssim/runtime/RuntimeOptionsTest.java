package com.shimizukenta.secssim.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeOptionsTest {

    @Test
    void parsesTlsOptions() {
        RuntimeOptions options = RuntimeOptions.parse(new String[] {
                "--mode=server", "--token=t", "--tls-keystore=server.p12", "--tls-password=secret"
        });

        assertEquals(RuntimeMode.SERVER, options.mode());
        assertEquals("t", options.token());
        assertEquals(Path.of("server.p12"), options.tlsKeystore());
        assertEquals("secret", options.tlsPassword());
    }

    @Test
    void tlsOptionsAreOptional() {
        RuntimeOptions options = RuntimeOptions.parse(new String[] {"--mode=integrated"});

        assertNull(options.tlsKeystore());
        assertEquals("", options.tlsPassword());
    }

    @Test
    void unknownOptionIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeOptions.parse(new String[] {"--tls-keystore-x=nope"}));
    }

    @Test
    void serverModeRequiresExplicitCredential() throws Exception {
        assertEquals(2, Main.run(new String[] {"--mode=server"}),
                "server 模式没有 --token 也没有 --auth-users 必须直接失败");
    }

    @Test
    void serverModeAcceptsAuthUsersInsteadOfToken() {
        /*
         * 成功路径不能在这里跑：Main.run 会一直阻塞在 stopped.await()。
         * "账号文件能真正起服务"由 HttpApiServerAuthTest 覆盖，
         * 这里只守住启动前就能判定的两条失败路径。
         */
        RuntimeOptions options = RuntimeOptions.parse(new String[] {
                "--mode=server", "--auth-users=users.json"
        });

        assertEquals(RuntimeMode.SERVER, options.mode());
        assertEquals(Path.of("users.json"), options.authUsers());
        assertEquals("", options.token(), "给了账号文件就不该再带单令牌");
    }

    @Test
    void authUsersIsOptional() {
        assertNull(RuntimeOptions.parse(new String[] {"--mode=integrated"}).authUsers());
    }

    @Test
    void brokenAuthUsersFileStopsStartup(@TempDir Path tempDir) throws Exception {
        Path users = tempDir.resolve("bad.json");
        Files.writeString(users, "{\"users\":[]}", StandardCharsets.UTF_8);

        assertEquals(2, Main.run(new String[] {
            "--mode=server", "--auth-users=" + users, "--work-dir=" + tempDir
        }), "空账号表等于没有鉴权，必须在启动阶段就失败");
    }

    @Test
    void missingAuthUsersFileStopsStartup(@TempDir Path tempDir) throws Exception {
        assertEquals(2, Main.run(new String[] {
            "--mode=server", "--auth-users=" + tempDir.resolve("nope.json"), "--work-dir=" + tempDir
        }));
    }

    @Test
    void integratedModeWithoutTokenGetsAUsableRandomOne() throws Exception {
        /*
         * 回归：曾经用 options.token()（空串）去建 AccessControl，
         * 于是 integrated 默认启动直接抛 "token must not be blank"。
         * Main.run 的成功路径会阻塞在 stopped.await()，所以只测凭据解析这一段。
         */
        Main.Credentials credentials = Main.resolveCredentials(
                RuntimeOptions.parse(new String[] {"--mode=integrated"}));

        assertEquals(64, credentials.token().length(), "随机 token 必须是 64 位十六进制");
        assertNotNull(credentials.access().authenticate(credentials.token()),
                "打印到 READY 行的 token 必须真的能通过鉴权");
    }

    @Test
    void explicitTokenIsUsedAsIs() throws Exception {
        Main.Credentials credentials = Main.resolveCredentials(
                RuntimeOptions.parse(new String[] {"--mode=server", "--token=fixed"}));

        assertEquals("fixed", credentials.token());
        assertNotNull(credentials.access().authenticate("fixed"));
    }

    @Test
    void authUsersModePrintsNoSingleToken(@TempDir Path tempDir) throws Exception {
        Path users = tempDir.resolve("users.json");
        Files.writeString(users, """
                {"users":[{"name":"guest","role":"viewer","secret":"g-pass"}]}""",
                StandardCharsets.UTF_8);

        Main.Credentials credentials = Main.resolveCredentials(RuntimeOptions.parse(new String[] {
            "--mode=server", "--auth-users=" + users
        }));

        assertEquals("", credentials.token(), "账号文件模式下 READY 行不应打印单一令牌");
        assertEquals(1, credentials.access().size());
        assertNotNull(credentials.access().authenticate("g-pass"));
    }
}
