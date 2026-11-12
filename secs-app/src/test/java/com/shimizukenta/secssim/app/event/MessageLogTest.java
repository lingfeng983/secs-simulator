package com.shimizukenta.secssim.app.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shimizukenta.jsonhub.JsonHub;

/** 报文落盘、重启回读与过滤。 */
class MessageLogTest {

    @TempDir
    Path temp;

    @Test
    void appendsJsonLinesAndReloadsTailAcrossRestart() throws Exception {
        MessageEvent first = new MessageEvent("s1", "in", 1, 3, true,
                "<A [4] \"PING\">\n第二行", 1_000L);
        MessageEvent second = new MessageEvent("s2", "out", 6, 11, false,
                "<L [2] <U4 1> <U4 2>>", 2_000L);

        try (MessageLog log = MessageLog.open(this.temp)) {
            log.append(first);
            log.append(second);
        }

        Path file = this.temp.resolve("logs").resolve("messages.jsonl");
        String lines = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.lines().count());
        assertTrue(lines.contains("\"direction\":\"in\""), lines);
        assertTrue(lines.contains("PING"), lines);

        try (MessageLog reopened = MessageLog.open(this.temp)) {
            JsonHub root = JsonHub.fromJson(reopened.recentJson(10, "", ""));
            assertEquals(2, root.get("messages").length());
            assertEquals(2, root.get("total").intValue());
            assertFalse(root.get("truncated").booleanValue());

            JsonHub restored = root.get("messages").get(0);
            assertEquals("s1", restored.get("sessionId").optionalString().orElse(""));
            assertEquals("in", restored.get("direction").optionalString().orElse(""));
            assertEquals(1, restored.get("stream").intValue());
            assertEquals(3, restored.get("function").intValue());
            assertTrue(restored.get("wbit").booleanValue());
            assertEquals(first.body(), restored.get("body").optionalString().orElse(""));
            assertEquals(1_000L, restored.get("at").longValue());

            assertEquals(2, reopened.recentJsonl(10, "", "").lines().count());
        }
    }

    @Test
    void splitsDeviceAndHostLogsPerSession() throws Exception {
        try (MessageLog log = MessageLog.open(this.temp)) {
            log.append(new MessageEvent("line1", "in", 1, 3, true, "<A \"REQ\">", 1_000L));
            log.append(new MessageEvent("line1", "out", 1, 4, false, "<A \"ACK\">", 1_100L));
            log.append(new MessageEvent("line2", "in", 6, 11, false, "<L>", 1_200L));
            log.append(new MessageEvent("a/b", "out", 2, 17, false, "<L>", 1_300L));
        }

        Path devices = this.temp.resolve("logs").resolve("devices");
        Path hosts = this.temp.resolve("logs").resolve("hosts");

        String deviceLine1 = Files.readString(devices.resolve("line1.jsonl"), StandardCharsets.UTF_8);
        assertEquals(2, deviceLine1.lines().count(), "设备文件只含本会话的进出报文");
        assertTrue(deviceLine1.contains("\"sessionId\":\"line1\""), deviceLine1);

        String hostLine1 = Files.readString(hosts.resolve("line1.jsonl"), StandardCharsets.UTF_8);
        assertEquals(2, hostLine1.lines().count());
        assertTrue(hostLine1.contains("\"perspective\":\"host\""), hostLine1);
        /* 设备侧 in = 主机侧 out，方向必须翻过来 */
        assertTrue(hostLine1.contains("\"direction\":\"out\""), hostLine1);
        assertTrue(hostLine1.contains("\"direction\":\"in\""), hostLine1);

        assertEquals(1, Files.readString(devices.resolve("line2.jsonl"), StandardCharsets.UTF_8)
                .lines().count(), "另一台设备的文件不串");
        assertTrue(Files.isRegularFile(devices.resolve("a_b.jsonl")), "非法字符要转义成安全文件名");
    }

    @Test
    void keepsHostIdAcrossRestart() throws Exception {
        /* 远程主机的报文也要落盘：重启后消息监控页仍能按 hostId 归属到实例上。 */
        try (MessageLog log = MessageLog.open(this.temp)) {
            log.append(MessageEvent.ofHost("line1", "host-9", "in", 1, 3, true, "<A \"REQ\">", 1_000L));
            log.append(MessageEvent.ofHost("line1", "host-9", "out", 1, 4, false, "<A \"ACK\">", 1_100L));
            /* 设备会话自己发布的报文没有主机标记 */
            log.append(new MessageEvent("line1", "in", 1, 1, true, "", 1_200L));
        }

        try (MessageLog reopened = MessageLog.open(this.temp)) {
            JsonHub root = JsonHub.fromJson(reopened.recentJson(10, "", ""));
            assertEquals(3, root.get("messages").length());
            assertEquals("host-9",
                    root.get("messages").get(0).get("hostId").optionalString().orElse(""));
            /* 旧行/设备行读回空串而不是缺字段：前端据此判"设备侧发布" */
            assertEquals("",
                    root.get("messages").get(2).get("hostId").optionalString().orElse("x"));
        }
    }

    @Test
    void filtersAndMarksTruncation() throws Exception {
        try (MessageLog log = MessageLog.open(this.temp)) {
            for (int i = 0; i < 5; i++) {
                log.append(new MessageEvent("s1", i % 2 == 0 ? "in" : "out",
                        1, i, false, "body-" + i, 1_000L + i));
            }
        }

        try (MessageLog log = MessageLog.open(this.temp)) {
            JsonHub all = JsonHub.fromJson(log.recentJson(10, "s1", ""));
            assertEquals(5, all.get("messages").length());

            JsonHub filtered = JsonHub.fromJson(log.recentJson(2, "s1", "in"));
            assertEquals(3, filtered.get("total").intValue());
            assertEquals(2, filtered.get("messages").length());
            assertTrue(filtered.get("truncated").booleanValue());
            assertEquals(4, filtered.get("messages").get(1).get("function").intValue());

            JsonHub missing = JsonHub.fromJson(log.recentJson(10, "nope", ""));
            assertEquals(0, missing.get("messages").length());
        }
    }

    @Test
    void repairsACrashTruncatedLastLineBeforeAppending() throws Exception {
        Path dir = this.temp.resolve("logs");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("messages.jsonl"), "{\"sessionId\":\"broken\"",
                StandardCharsets.UTF_8);

        try (MessageLog log = MessageLog.open(this.temp)) {
            log.append(new MessageEvent("s1", "out", 1, 2, false, "after-crash", 7L));
        }

        String lines = Files.readString(dir.resolve("messages.jsonl"), StandardCharsets.UTF_8);
        assertEquals(2, lines.lines().count(), lines);
        try (MessageLog log = MessageLog.open(this.temp)) {
            JsonHub root = JsonHub.fromJson(log.recentJson(10, "", ""));
            assertEquals(1, root.get("messages").length(), "半截旧行仍应被跳过");
            assertEquals("after-crash", root.get("messages").get(0).get("body").optionalString().orElse(""));
        }
    }

    @Test
    void rotatesWhenFileExceedsConfiguredSize() throws Exception {
        System.setProperty("secssim.messages.maxBytes", "1024");
        try {
            try (MessageLog log = MessageLog.open(this.temp)) {
                for (int i = 0; i < 60; i++) {
                    log.append(new MessageEvent("s1", "in", 1, 3, false,
                            "payload-" + i + "-" + "x".repeat(40), 1_000L + i));
                }
            }
        }
        finally {
            System.clearProperty("secssim.messages.maxBytes");
        }

        Path dir = this.temp.resolve("logs");
        Path rotated = dir.resolve("messages.jsonl.1");
        assertTrue(Files.isRegularFile(rotated), "超过上限后必须轮转出 .1 文件");
        assertTrue(Files.size(dir.resolve("messages.jsonl")) < 4_096L,
                "当前文件应在轮转后保持较小体积");

        /* 轮转不丢可读性：主文件仍然是合法 JSONL */
        for (String line : Files.readAllLines(dir.resolve("messages.jsonl"), StandardCharsets.UTF_8)) {
            if ( ! line.isBlank() ) {
                JsonHub.fromJson(line);
            }
        }

        try (MessageLog reopened = MessageLog.open(this.temp)) {
            assertTrue(reopened.recentJson(100, "", "").contains("payload-59"),
                    "重启后从当前文件尾部恢复");
        }
    }

    @Test
    void ignoresCorruptLinesInsteadOfDroppingHistory() throws Exception {
        Path dir = this.temp.resolve("logs");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("messages.jsonl"),
                "not json\n"
                        + "{\"sessionId\":\"s1\",\"direction\":\"out\",\"stream\":1,"
                        + "\"function\":2,\"wbit\":false,\"body\":\"ok\",\"at\":5}\n"
                        + "{\"direction\":\"sideways\"}\n",
                StandardCharsets.UTF_8);

        try (MessageLog log = MessageLog.open(this.temp)) {
            JsonHub root = JsonHub.fromJson(log.recentJson(10, "", ""));
            assertEquals(1, root.get("messages").length());
            assertEquals("ok", root.get("messages").get(0).get("body").optionalString().orElse(""));
        }
    }
}
