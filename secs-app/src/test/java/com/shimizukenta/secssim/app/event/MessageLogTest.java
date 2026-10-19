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
