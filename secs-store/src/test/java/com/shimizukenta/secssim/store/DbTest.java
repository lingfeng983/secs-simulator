package com.shimizukenta.secssim.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Db 门面：双库建立、meta、跨进程锁、事务回滚、在线备份。
 *
 * <p>cleanup=NEVER：sqlite-jdbc 会把本地库 System.load 进 JVM，文件锁到进程退出，
 * JUnit 删临时目录不应算失败（同 DbSpikeTest 的结论，残留交给系统临时目录清理）。</p>
 */
class DbTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    @Test
    void openCreatesBothDatabasesWithSchema() throws Exception {
        try (Db db = Db.open(workDir)) {
            assertTrue(Files.isRegularFile(workDir.resolve("config.db")));
            assertTrue(Files.isRegularFile(workDir.resolve("runtime.db")));
            assertEquals(String.valueOf(Schema.VERSION), db.meta("config", "schema_version"));
            assertEquals(String.valueOf(Schema.VERSION), db.meta("runtime", "schema_version"));

            /* WAL 在读写连接上都生效 */
            try (var c = db.readConfig(); var st = c.createStatement();
                 var rs = st.executeQuery("PRAGMA journal_mode")) {
                assertTrue(rs.next());
                assertEquals("wal", rs.getString(1).toLowerCase(java.util.Locale.ROOT));
            }
        }
    }

    @Test
    void secondOpenOnSameWorkDirIsRejected() throws Exception {
        Db first = Db.open(workDir);
        try {
            StoreException e = assertThrows(StoreException.class, () -> Db.open(workDir));
            assertTrue(e.getMessage().contains("不允许同时跑两个进程"), e.getMessage());
        } finally {
            first.close();
        }
        /* 关闭后锁释放：可以重新打开 */
        try (Db _ = Db.open(workDir)) {
            assertTrue(Files.isRegularFile(workDir.resolve("config.db")));
        }
    }

    @Test
    void rollbackOnFailedTransaction() throws Exception {
        try (Db db = Db.open(workDir)) {
            db.writeConfig(connection -> {
                exec(connection, "CREATE TABLE probe_r (v INTEGER)");
                exec(connection, "INSERT INTO probe_r VALUES (7)");
                return null;
            });
            assertThrows(StoreException.class, () -> db.writeConfig(connection -> {
                exec(connection, "INSERT INTO probe_r VALUES (8)");
                exec(connection, "INSERT INTO missing_table VALUES (9)");
                return null;
            }));
            try (var c = db.readConfig(); var st = c.createStatement();
                 var rs = st.executeQuery("SELECT COUNT(*) FROM probe_r")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "失败事务的行不应残留");
            }
        }
    }

    @Test
    void vacuumIntoProducesOpenableBackup() throws Exception {
        try (Db db = Db.open(workDir)) {
            db.writeConfig(connection -> {
                exec(connection, "CREATE TABLE backup_probe (v TEXT)");
                exec(connection, "INSERT INTO backup_probe VALUES ('数据')");
                return null;
            });
            Path backup = workDir.resolve("backup").resolve("config-snapshot.db");
            db.backupConfigTo(backup);
            assertTrue(Files.isRegularFile(backup), "备份文件没有生成");

            /* 备份能被独立打开且数据在（设计 §2.8：VACUUM INTO 备份可独立打开） */
            try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + backup);
                 var st = c.createStatement();
                 var rs = st.executeQuery("SELECT v FROM backup_probe")) {
                assertTrue(rs.next());
                assertEquals("数据", rs.getString(1));
            }
        }
    }

    @Test
    void nativeLibraryIsExtractedIntoWorkDir() throws Exception {
        try (Db _ = Db.open(workDir)) {
            Path lib = workDir.resolve("native").resolve(
                    Path.of(DbBootstrap.nativeResource()).getFileName());
            assertTrue(Files.isRegularFile(lib), "本地库没有释放: " + lib);
            assertTrue(Files.size(lib) > 0);
        }
    }

    private static void exec(java.sql.Connection connection, String sql) {
        try (var st = connection.createStatement()) {
            st.executeUpdate(sql);
        } catch (SQLException e) {
            throw new StoreException(e.getMessage(), e);
        }
    }
}
