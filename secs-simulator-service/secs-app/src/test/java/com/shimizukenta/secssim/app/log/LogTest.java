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

        String info = firstContaining(lines, "INFO");
        assertTrue(info.contains("设备已启动"), info);

        String error = firstContaining(lines, "ERROR");
        assertTrue(error.contains("发送失败"), error);

        /* 没有堆栈的 ERROR 只能看到"失败了"，看不到在哪一行失败 */
        assertTrue(lines.stream().anyMatch(l -> l.contains("IllegalStateException: boom")),
                "ERROR 必须把异常类型与消息落盘");
        assertTrue(lines.stream().anyMatch(l -> l.stripLeading().startsWith("at ")),
                "ERROR 必须带 at 帧");
    }

    /** 时间戳必须带日期：只有时分秒的话，跨天之后对不上。 */
    @Test
    void timestampCarriesDate(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("app.log");
        Log.toFile(file);
        Log.info("x");

        String line = Files.readAllLines(file, StandardCharsets.UTF_8).get(0);
        /* [yyyy-MM-dd HH:mm:ss.SSS] LEVEL [ctx] source message */
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

    /**
     * 级别是五档：WARN 必须比 INFO 严重。
     *
     * <p>旧实现把 WARN 落到 INFO 档，导致"只留 WARN 及以上"根本做不到。</p>
     */
    @Test
    void warnIsMoreSevereThanInfo(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("app.log");
        Log.toFile(file);

        Log.setLevel(Log.Level.WARN);
        Log.info("不该出现的 info");
        Log.warn("该出现的 warn");

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(1, lines.size(), "INFO 应被 WARN 档过滤掉");
        assertTrue(lines.get(0).contains("WARN") && lines.get(0).contains("该出现的 warn"), lines.get(0));

        Log.setLevel(Log.Level.ERROR);
        Log.warn("ERROR 档下不该出现的 warn");

        assertEquals(1, Files.readAllLines(file, StandardCharsets.UTF_8).size(),
                "WARN 应被 ERROR 档过滤掉");
    }

    /** {@code {}} 占位替换 + 来源标识；末位 Throwable 当 cause。 */
    @Test
    void facadeFormatsArgsAndCarriesSource(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("app.log");
        Log.toFile(file);

        Log.Facade log = Log.of(LogTest.class);
        log.info("会话 {} 发送 {} 条", "s-1", 3);
        log.error("写 {} 失败", "runtime.db", new IllegalStateException("disk full"));

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        String info = firstContaining(lines, "INFO");
        assertTrue(info.contains("会话 s-1 发送 3 条"), info);
        assertTrue(info.contains("LogTest"), "行内必须有来源类名: " + info);

        assertTrue(lines.stream().anyMatch(l -> l.contains("disk full")),
                "末位 Throwable 必须被当成 cause 打出来");
    }

    /** 超长消息必须截断，否则一条 SML 就能把终端刷死。 */
    @Test
    void longMessageIsTruncated(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("app.log");
        Log.toFile(file);

        Log.info("y".repeat(3_000));

        String line = Files.readAllLines(file, StandardCharsets.UTF_8).get(0);
        assertTrue(line.contains("(truncated, total=3000)"), line);
        assertTrue(line.length() < 3_000, "整行长度应被截断");
    }

    /** 上下文作用域：块内带 [ctx]，退出后恢复。 */
    @Test
    void scopeAttachesContext(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("app.log");
        Log.toFile(file);

        Log.Scope scope = Log.scope("dev-42");
        try {
            Log.info("块内");
        } finally {
            scope.close();
        }
        Log.info("块外");

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertTrue(lines.get(0).contains("[dev-42]"), lines.get(0));
        assertFalse(lines.get(1).contains("[dev-42]"), lines.get(1));
    }

    private static String firstContaining(List<String> lines, String needle) {
        return lines.stream()
                .filter(l -> l.contains(needle))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有包含 " + needle + " 的行: " + lines));
    }
}
