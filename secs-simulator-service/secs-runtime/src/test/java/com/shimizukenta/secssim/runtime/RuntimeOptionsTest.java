package com.shimizukenta.secssim.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class RuntimeOptionsTest {

    @Test
    void parsesTlsOptions() {
        RuntimeOptions options = RuntimeOptions.parse(new String[] {
                "--mode=server", "--tls-keystore=server.p12", "--tls-password=secret"
        });

        assertEquals(RuntimeMode.SERVER, options.mode());
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
    void defaultBindIsWildcardInEveryMode() {
        assertEquals("0.0.0.0", RuntimeOptions.parse(new String[] {"--mode=server"}).bindHost(),
                "内网工具默认监听所有网卡");
        assertEquals("0.0.0.0", RuntimeOptions.parse(new String[] {"--mode=integrated"}).bindHost());
        assertEquals("127.0.0.1",
                RuntimeOptions.parse(new String[] {"--mode=server", "--bind=127.0.0.1"}).bindHost(),
                "需要收窄时仍可显式指定");
    }

    /**
     * 凭据参数已移除：内部工具直连访问，脚本里残留 {@code --token} /
     * {@code --auth-users} 应当直接报"未知参数"，而不是被悄悄忽略。
     */
    @Test
    void credentialOptionsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeOptions.parse(new String[] {"--token=t"}));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeOptions.parse(new String[] {"--auth-users=users.json"}));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeOptions.parse(new String[] {"--allow-open"}));
    }
}
