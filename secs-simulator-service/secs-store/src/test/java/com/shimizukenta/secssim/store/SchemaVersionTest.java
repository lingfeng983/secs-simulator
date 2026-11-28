package com.shimizukenta.secssim.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * schema 版本策略：先读后判、不匹配拒绝启动、显式允许时才备份重建；
 * 以及结构收敛后的表/列断言（投影表与 device_link 死列都不该再出现）。
 */
class SchemaVersionTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    /** 旧版本写出的库：meta 里记着给定版本，并留一张只有旧结构才有的表。 */
    private void seedVersion(Path dbFile, int version) throws Exception {
        try (Connection connection = open(dbFile);
             Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            st.executeUpdate("INSERT INTO meta(key, value) VALUES('schema_version', '" + version + "')");
            st.executeUpdate("CREATE TABLE legacy_marker (v INTEGER)");
        }
    }

    @Test
    void mismatchedVersionRefusesToStartAndLeavesDatabaseUntouched() throws Exception {
        Path dbFile = this.workDir.resolve("config.db").toAbsolutePath();
        seedVersion(dbFile, 999);

        try (Connection connection = open(dbFile)) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> Schema.apply(connection, true, dbFile, false));
            String message = e.getMessage();
            assertTrue(message.contains(dbFile.toString()), "提示里要有库路径: " + message);
            assertTrue(message.contains("--allow-schema-reset"), "提示里要给出开关: " + message);
            assertTrue(message.contains("v999"), "提示里要有实际版本: " + message);

            /* 拒绝启动不能动库：版本号没被覆盖，旧表还在 */
            assertEquals(999, Schema.readVersion(connection));
            assertTrue(tableExists(connection, "legacy_marker"));
        }
        assertFalse(hasBackupFile(), "拒绝启动时不该产生备份");
    }

    @Test
    void dbOpenPropagatesRefusalWithoutOverwritingVersion() throws Exception {
        Path dbFile = this.workDir.resolve("config.db").toAbsolutePath();
        seedVersion(dbFile, 999);

        assertThrows(IllegalStateException.class, () -> Db.open(this.workDir));

        try (Connection connection = open(dbFile)) {
            assertEquals(999, Schema.readVersion(connection));
        }
    }

    @Test
    void allowResetBacksUpThenRebuildsWithCurrentSchema() throws Exception {
        Path dbFile = this.workDir.resolve("config.db").toAbsolutePath();
        seedVersion(dbFile, 999);

        try (Connection connection = open(dbFile)) {
            Schema.apply(connection, true, dbFile, true);
            assertEquals(Schema.VERSION, Schema.readVersion(connection));
            assertFalse(tableExists(connection, "legacy_marker"), "旧结构应被清掉");
            assertEquals(9, countTables(connection), "config.db 只应剩 9 张表");
        }
        assertTrue(hasBackupFile(), "重建前必须先备份成 <db>.bak.<时间戳>");
    }

    @Test
    void dbOpenWithAllowResetRecoversMismatchedDatabase() throws Exception {
        Path dbFile = this.workDir.resolve("config.db").toAbsolutePath();
        seedVersion(dbFile, 999);

        try (Db db = Db.open(this.workDir, true)) {
            assertEquals(String.valueOf(Schema.VERSION), db.meta("config", "schema_version"));
        }
        assertTrue(hasBackupFile());
    }

    @Test
    void missingMetaTableIsTreatedAsFresh() throws Exception {
        Path noMeta = this.workDir.resolve("no-meta.db").toAbsolutePath();
        try (Connection connection = open(noMeta)) {
            assertEquals(0, Schema.readVersion(connection));
            Schema.apply(connection, false, noMeta, false);
            assertEquals(Schema.VERSION, Schema.readVersion(connection));
        }
    }

    @Test
    void metaWithoutVersionKeyIsRefusedInsteadOfTreatedAsFresh() throws Exception {
        /* 旧行为是当成全新库直接建空表并写版本——那会把「建表建到一半」或
           「被外部改过」的库静默当成新库，用户的设备配置就这么没了。
           现在必须显式失败，让用户知道要备份后删库。 */
        Path noKey = this.workDir.resolve("no-key.db").toAbsolutePath();
        try (Connection connection = open(noKey);
             Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            assertThrows(java.sql.SQLException.class, () -> Schema.readVersion(connection),
                    "meta 存在但缺 schema_version 时必须报错，不能当成全新库");
        }
    }

    @Test
    void configSchemaHasNoProjectionTables() throws Exception {
        List<String> projectionTables = List.of(
                "connection_spec", "equipment_spec", "unit_def", "svid_def", "equipment_constant",
                "report_def", "report_svid", "ceid_def", "ceid_report", "event_plan", "plan_action");
        try (Db db = Db.open(this.workDir); Connection connection = db.readConfig()) {
            for (String table : projectionTables) {
                assertFalse(tableExists(connection, table), "投影表应已删除: " + table);
            }
            assertEquals(9, countTables(connection));
        }
    }

    @Test
    void deviceLinkHasNoDeadColumns() throws Exception {
        try (Db db = Db.open(this.workDir); Connection connection = db.readRuntime()) {
            List<String> columns = columnsOf(connection, "device_link");
            assertFalse(columns.contains("counters_json"), columns.toString());
            assertFalse(columns.contains("overrides_json"), columns.toString());
            assertFalse(columns.contains("last_good_json"), columns.toString());
        }
    }

    /**
     * P2-4：备份彻底失败（VACUUM INTO 与文件复制都不行）时，异常链必须保留
     * VACUUM INTO 的真实失败原因，而不是只报复制的 IOException。
     *
     * <p>构造法：dbPath 的父路径是一个普通文件（{@code blocker/child.db}，
     * blocker 是文件不是目录）——备份目标 {@code blocker/child.db.bak.<ts>}
     * 无法创建：VACUUM INTO 报 SQLException，Files.copy 也报 IOException。</p>
     */
    @Test
    void backupFailureKeepsVacuumCauseInExceptionChain() throws Exception {
        Path metaDb = this.workDir.resolve("meta.db");
        seedVersion(metaDb, 999);
        Path blocker = this.workDir.resolve("blocker");
        Files.writeString(blocker, "not a directory");
        Path fakeDb = blocker.resolve("child.db");

        try (Connection connection = open(metaDb)) {
            StoreException e = assertThrows(StoreException.class,
                    () -> Schema.apply(connection, true, fakeDb, true));
            assertTrue(e.getMessage().contains("拒绝继续重建"), e.getMessage());
            Throwable io = e.getCause();
            assertTrue(io instanceof java.io.IOException, "cause 应是复制失败的 IOException: " + io);
            assertTrue(io.getCause() instanceof SQLException,
                    "IOException 的 cause 应保留 VACUUM INTO 的 SQLException: " + io.getCause());
        }
    }

    /* ---------- 工具 ---------- */

    private Connection open(Path dbFile) throws Exception {
        DbBootstrap.ensureNativeLibrary(this.workDir);
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile);
    }

    private boolean hasBackupFile() throws Exception {
        try (var files = Files.list(this.workDir)) {
            return files.anyMatch(p -> p.getFileName().toString().startsWith("config.db.bak."));
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (var st = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            st.setString(1, table);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static int countTables(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private static List<String> columnsOf(Connection connection, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }
        return columns;
    }
}
