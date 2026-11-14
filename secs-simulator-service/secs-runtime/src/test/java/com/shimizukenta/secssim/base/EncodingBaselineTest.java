package com.shimizukenta.secssim.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 编码基线。
 *
 * <p>这台机器的默认区域是 zh_CN，而模板、SML、单位名里一定会有中文。
 * native-image 会把默认编码固化进镜像，一旦基线错了，上线后全是乱码且无法在不重新编译的情况下修。
 * 所以把这四条断言钉死在 CI 里。
 * </p>
 */
class EncodingBaselineTest {

    @Test
    void defaultCharsetIsUtf8() {
        assertEquals(StandardCharsets.UTF_8, Charset.defaultCharset());
        assertEquals("UTF-8", System.getProperty("file.encoding"));
    }

    @Test
    void chineseDirectoryAndFileNameRoundTrip(@TempDir Path tempDir) throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("腔体配置"));
        Path file = dir.resolve("模板-温度.json");
        String content = "{\"单元\":\"CH1\",\"名称\":\"温度设置值\",\"值\":180.5}";

        Files.writeString(file, content, StandardCharsets.UTF_8);

        assertTrue(Files.exists(file), "file with chinese name was not created");
        assertEquals(content, Files.readString(file, StandardCharsets.UTF_8));
    }
}
