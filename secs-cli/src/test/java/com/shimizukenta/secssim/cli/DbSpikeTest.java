package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * M0 尖峰的 JVM 侧验证：sqlite-jdbc 在普通 JVM 里跑通双库建立、版本读取与台账写入。
 *
 * <p>native-image 路径由 {@link NativeRuntimeIT} 覆盖；这里保证旗标解析、
 * 本地库释放与 SQL 执行本身在 CI 的日常 {@code mvn test} 里就有回归。
 * cleanup=NEVER：尖峰会把本地库 System.load 进 JVM，文件锁到进程退出才释放，
 * JUnit 删不掉临时目录不应算失败（残留交给系统临时目录清理）。</p>
 */
class DbSpikeTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    @Test
    void spikeCreatesDatabasesAndWritesLedger() throws Exception {
        DbSpike.run(workDir);

        assertTrue(Files.isRegularFile(workDir.resolve("config.db")), "config.db 没有建立");
        assertTrue(Files.isRegularFile(workDir.resolve("runtime.db")), "runtime.db 没有建立");
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String lib = os.contains("win") ? "sqlitejdbc.dll"
                : os.contains("mac") ? "libsqlitejdbc.jnilib" : "libsqlitejdbc.so";
        assertTrue(Files.isRegularFile(workDir.resolve("native").resolve(lib)),
                "本地库没有释放到 native/");
    }

    @Test
    void maybeRunOnlyStripsItsOwnFlag() throws Exception {
        String[] input = { "--mode=probe", "--db-spike", "--work-dir", workDir.toString() };
        String[] rest = DbSpike.maybeRun(input);
        assertEquals(3, rest.length, "旗标应被去掉");
        assertEquals("--mode=probe", rest[0]);
        assertEquals("--work-dir", rest[1]);
        assertTrue(Files.isRegularFile(workDir.resolve("config.db")), "尖峰没有执行");

        String[] untouched = { "--mode=probe" };
        DbSpike.maybeRun(untouched);
        assertEquals(1, untouched.length, "没有旗标时不应改动参数");
    }

    @Test
    void spikeRequiresExplicitWorkDir() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> DbSpike.maybeRun(new String[] { "--db-spike" }),
                "没有 --work-dir 时应拒绝执行，避免把 db 写错目录");
    }
}
