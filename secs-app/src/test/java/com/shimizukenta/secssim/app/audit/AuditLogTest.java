package com.shimizukenta.secssim.app.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shimizukenta.jsonhub.JsonHub;

/** 审计日志：落盘、重启回读、限制条数与坏行容忍。 */
class AuditLogTest {

    @TempDir
    Path temp;

    @Test
    void writesAndReloadsAuditEntries() throws Exception {
        try (AuditLog log = AuditLog.open(this.temp)) {
            log.record("alice", "operator", "trigger-ceid", "line1", "ceid=2001 outcome=OK");
            log.record("bob", "admin", "config-save", "line1.json", "bytes=42");
        }

        Path file = this.temp.resolve("logs").resolve("audit.jsonl");
        assertEquals(2, Files.readString(file, StandardCharsets.UTF_8).lines().count());

        try (AuditLog log = AuditLog.open(this.temp)) {
            JsonHub root = JsonHub.fromJson(log.recentJson(10));
            assertEquals(2, root.get("audit").length());
            assertEquals("alice", root.get("audit").get(0).get("actor").optionalString().orElse(""));
            assertEquals("trigger-ceid", root.get("audit").get(0).get("action").optionalString().orElse(""));
            assertEquals("line1", root.get("audit").get(0).get("target").optionalString().orElse(""));
            assertEquals("bob", root.get("audit").get(1).get("actor").optionalString().orElse(""));
            assertEquals("admin", root.get("audit").get(1).get("role").optionalString().orElse(""));
        }
    }

    @Test
    void returnsTheMostRecentEntriesWithinLimit() throws Exception {
        try (AuditLog log = AuditLog.open(this.temp)) {
            for (int i = 0; i < 5; i++) {
                log.record("u" + i, "operator", "act", "t" + i, "d" + i);
            }
        }

        try (AuditLog log = AuditLog.open(this.temp)) {
            JsonHub root = JsonHub.fromJson(log.recentJson(2));
            assertEquals(2, root.get("audit").length());
            assertEquals("t3", root.get("audit").get(0).get("target").optionalString().orElse(""));
            assertEquals("t4", root.get("audit").get(1).get("target").optionalString().orElse(""));
        }
    }

    @Test
    void ignoresCorruptLines() throws Exception {
        Path dir = this.temp.resolve("logs");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("audit.jsonl"),
                "not json\n"
                        + "{\"at\":1,\"actor\":\"a\",\"role\":\"admin\","
                        + "\"action\":\"save\",\"target\":\"x\",\"detail\":\"ok\"}\n",
                StandardCharsets.UTF_8);

        try (AuditLog log = AuditLog.open(this.temp)) {
            JsonHub root = JsonHub.fromJson(log.recentJson(10));
            assertEquals(1, root.get("audit").length());
            assertEquals("a", root.get("audit").get(0).get("actor").optionalString().orElse(""));
            assertTrue(root.get("audit").get(0).get("detail").optionalString().orElse("").contains("ok"));
        }
    }
}
