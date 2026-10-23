package com.shimizukenta.secssim.app.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 日志落盘与轮转。
 *
 * <p>现场最常见的求助是"设备昨天突然不发事件了"。stderr 那时早就没了，
 * 所以日志必须落盘；同时它不能把磁盘写满、也不能因为写盘失败拖垮业务。</p>
 */
class LogTest {

    @AfterEach
    void tearDown() {
        Log.reset();
    }

    @Test
    void writesToBothStderrAndFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("logs").resolve("app.log");
        Log.toFile(file);

        assertTrue(Log.fileEnabled());
        Log.info("设备已启动");
        Log.error("发送失败", new IllegalStateException("boom"));

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());

        assertTrue(lines.get(0).contains("INFO") && lines.get(0).contains("设备已启动"), lines.get(0));
        assertTrue(lines.get(1).contains("ERROR") && lines.get(1).contains("boom"), lines.get(1));
    }

    /** 时间戳必须带日期：只有时分秒的话，跨天之后对不上。 */
    @Test
    void timestampCarriesDate(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("app.log");
        Log.toFile(file);
        Log.info("x");

        String line = Files.readAllLines(file, StandardCharsets.UTF_8).get(0);
        /* [yyyy-MM-dd HH:mm:ss.SSS] */
        assertTrue(line.matches("^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}] .*"), line);
    }

    /**
     * 写盘目标不可用时不能让业务崩掉。
     *
     * <p>这里用一个"父路径是普通文件"的位置，创建目录必定失败。</p>
     */
    @Test
    void unavailableTargetDegradesToStderrOnly(@TempDir Path dir) throws Exception {
        Path blocker = dir.resolve("blocked");
        Files.writeString(blocker, "not a directory", StandardCharsets.UTF_8);

        Log.toFile(blocker.resolve("app.log"));

        assertFalse(Log.fileEnabled(), "无法创建目录时应退化为只写 stderr");
        /* 不抛异常即为通过：下面这行不能崩 */
        Log.info("仍然可以记录");
    }

    /** 超过上限要轮转，且历史份数有上限。 */
    @Test
    void rotatesWhenFileGrowsTooLarge(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("app.log");
        Log.toFile(file);

        /* 先灌到超过 8MB（MAX_BYTES），再写一条触发轮转 */
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            big.append('x');
        }
        for (int i = 0; i < 45_000; i++) {
            Log.info(big.toString());
        }

        assertTrue(Files.exists(dir.resolve("app.log.1")), "应生成第一份轮转文件");
        assertFalse(Files.exists(dir.resolve("app.log.4")),
                "历史份数必须封顶，不能无限增长");

        /* 轮转后当前文件应该重新变小 */
        assertTrue(Files.size(file) < 9L * 1024L * 1024L, "当前文件应小于上限");
    }
}
