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
    void serverModeRequiresExplicitToken() throws Exception {
        assertEquals(2, Main.run(new String[] {"--mode=server"}),
                "server 模式没有 token 必须直接失败");
    }
}
