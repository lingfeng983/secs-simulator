package com.shimizukenta.secssim.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void nativeLibraryIsExtractedIntoWorkDir() throws Exception {
        try (Db _ = Db.open(workDir)) {
            Path lib = workDir.resolve("native").resolve(
                    Path.of(DbBootstrap.nativeResource()).getFileName());
            assertTrue(Files.isRegularFile(lib), "本地库没有释放: " + lib);
            assertTrue(Files.size(lib) > 0);
        }
    }

    /** close 必须等在途写事务提交完，不能把写连接从事务脚下抽走（P1-2）。 */
    @Test
    void closeWaitsForInFlightWriteTransaction() throws Exception {
        Db db = Db.open(workDir);
        java.util.concurrent.CountDownLatch inTransaction = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch letCommit = new java.util.concurrent.CountDownLatch(1);
        Thread writer = new Thread(() -> db.writeConfig(connection -> {
            exec(connection, "CREATE TABLE probe_close (v INTEGER)");
            exec(connection, "INSERT INTO probe_close VALUES (7)");
            inTransaction.countDown();
            try {
                /* 事务保持打开，直到测试放行提交 */
                assertTrue(letCommit.await(10, java.util.concurrent.TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待提交被中断", e);
            }
            return null;
        }), "in-flight-writer");
        writer.start();
        assertTrue(inTransaction.await(10, java.util.concurrent.TimeUnit.SECONDS), "写事务应已开始");

        Thread closer = new Thread(db::close, "closer");
        closer.start();
        /* close 已发起但事务未提交：closer 必须阻塞在写锁上 */
        closer.join(300);
        assertTrue(closer.isAlive(), "close 不应抢在在途写事务之前关连接");

        letCommit.countDown();
        writer.join(10_000);
        closer.join(10_000);
        assertFalse(writer.isAlive());
        assertFalse(closer.isAlive());

        /* 在途事务完整提交（没被 close 打断回滚）；关闭后进程锁已释放，可重开验证 */
        try (Db reopened = Db.open(workDir);
             var c = reopened.readConfig(); var st = c.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM probe_close")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "在途事务的行应完整落库");
        }
    }

    /** closed 后 write/read 快速失败，抛明确异常而不是混乱 SQLException（P1-2）。 */
    @Test
    void closedDbFailsFastWithClearException() throws Exception {
        Db db = Db.open(workDir);
        db.close();
        db.close(); /* 幂等：重复 close 不抛 */

        IllegalStateException w = assertThrows(IllegalStateException.class,
                () -> db.writeConfig(connection -> null));
        assertTrue(w.getMessage().contains("已关闭"), w.getMessage());
        assertThrows(IllegalStateException.class, () -> db.writeRuntime(connection -> null));
        assertThrows(IllegalStateException.class, db::readConfig);
        assertThrows(IllegalStateException.class, db::readRuntime);
    }

    private static void exec(java.sql.Connection connection, String sql) {
        try (var st = connection.createStatement()) {
            st.executeUpdate(sql);
        } catch (SQLException e) {
            throw new StoreException(e.getMessage(), e);
        }
    }
}
