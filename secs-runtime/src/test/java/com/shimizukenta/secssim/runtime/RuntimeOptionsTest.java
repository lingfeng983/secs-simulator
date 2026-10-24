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
    @org.junit.jupiter.api.Disabled("起真实 server 会阻塞在 stopped.await()；开放访问行为由 HttpApiServer 集成验证覆盖")
    void serverModeWithoutCredentialStartsOpen() {
        /*
         * 内部工具语义：server 模式不带凭据 = 开放访问（匿名管理员），不再退出码 2。
         * Main.run 会一直阻塞，不能在这里直跑；放行行为由运行时冒烟与前端 e2e 覆盖。
         */
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
    void integratedModeWithoutTokenIsOpenAccess() throws Exception {
        /*
         * 内部工具语义：integrated 不带 --token = 开放访问（匿名管理员），
         * READY 行不再打印随机 token。
         * Main.run 的成功路径会阻塞在 stopped.await()，所以只测凭据解析这一段。
         */
        Main.Credentials credentials = Main.resolveCredentials(
                RuntimeOptions.parse(new String[] {"--mode=integrated"}));

        assertEquals("", credentials.token(), "开放访问模式 READY 行没有 token");
        var anonymous = credentials.access().authenticate(null);
        assertNotNull(anonymous, "开放访问下匿名请求必须是合法身份");
        assertEquals("anonymous", anonymous.name());
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
