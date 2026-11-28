package com.shimizukenta.secssim.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 编码基线。
 *
 * <p>这台机器的默认区域是 zh_CN，而模板、SML、单位名里一定会有中文。
 * native-image 会把默认编码固化进镜像，一旦基线错了，上线后全是乱码且无法在不重新编译的情况下修。
 * </p>
 *
 * <p>断言只保留「会因项目配置出错而变红」的那部分：JDK 25 起 JEP 400 已让
 * {@code Charset.defaultCharset()} 恒为 UTF-8，再去断言它等于在测 JDK 而不是测项目；
 * 真正能反映镜像配置的是 {@code file.encoding} 系统属性（由 native-image 的
 * {@code -Dfile.encoding=UTF-8} buildArg 决定）。同理，「写完文件后断言文件存在」
 * 永远为真，改为校验磁盘上的文件名字节与内容字节。</p>
 */
class EncodingBaselineTest {

    @Test
    void fileEncodingPropertyIsUtf8() {
        assertEquals("UTF-8", System.getProperty("file.encoding"),
                "native-image 的 -Dfile.encoding 丢了，镜像里的中文会按平台默认编码解码");
    }

    @Test
    void chineseDirectoryAndFileNameRoundTrip(@TempDir Path tempDir) throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("腔体配置"));
        Path file = dir.resolve("模板-温度.json");
        String content = "{\"单元\":\"CH1\",\"名称\":\"温度设置值\",\"值\":180.5}";

        Files.writeString(file, content, StandardCharsets.UTF_8);

        /* 目录里必须能按原名枚举到——只看 Files.exists 是恒真的，测不出编码问题 */
        try (var entries = Files.list(dir)) {
            List<String> names = entries.map(p -> p.getFileName().toString()).toList();
            assertTrue(names.contains("模板-温度.json"),
                    "按 UTF-8 写出的中文文件名在目录里找不到，实际条目: " + names);
        }

        assertEquals(content, Files.readString(file, StandardCharsets.UTF_8));
    }
}
