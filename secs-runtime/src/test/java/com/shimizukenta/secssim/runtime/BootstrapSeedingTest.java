package com.shimizukenta.secssim.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import com.shimizukenta.secssim.app.config.SessionConfigJson;
import com.shimizukenta.secssim.app.event.EventBus;
import com.shimizukenta.secssim.app.session.SessionManager;
import com.shimizukenta.secssim.store.ConfigRepository;
import com.shimizukenta.secssim.store.Db;
import com.shimizukenta.secssim.store.TemplateRepository;

/**
 * 内置默认设备的播种语义（3.0：播种进 config.db，不再写 work-dir 文件）。
 *
 * <p>端口/deviceId 仍由 {@link SessionOverrides} 在启动时覆盖（历史教训见
 * 2.x 的测试注释），播种只做「原样入库 + 幂等不覆盖」。</p>
 */
class BootstrapSeedingTest {

    private static final String[] NAMES = {"demo-etch.json", "demo-cvd.json"};

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    @Test
    void seedsDefaultDevicesIntoDatabase() throws Exception {
        ConfigRepository configs = repo();
        seed(configs);

        for (String name : NAMES) {
            String stem = name.substring(0, name.length() - 5);
            var doc = configs.readDocument(stem);
            assertTrue(doc.isPresent(), stem + " 必须被播种");
            assertEquals(resource(name), doc.get().body(),
                    name + " 必须原样入库（不得改写端口/deviceId）");
            assertDoesNotThrow(() -> SessionConfigJson.parse(doc.get().body()),
                    name + " 播种内容必须能解析");
        }
    }

    @Test
    void doesNotOverwriteExistingDevice() throws Exception {
        ConfigRepository configs = repo();
        /* 用户已有的同名设备（迁移进来的、改过的）不能被播种覆盖 */
        configs.createDevice("demo-etch", "demo-etch", null, "{\"user\":true}", true, "test");

        seed(configs);

        assertEquals("{\"user\":true}", configs.readDocument("demo-etch").orElseThrow().body(),
                "已有的同名设备不能被播种覆盖");
        assertTrue(configs.readDocument("demo-cvd").isPresent(), "缺失的模板仍应补齐");
    }

    /** 播种是私有的启动步骤；用反射直接验证，避免为测试放宽生产 API。 */
    private static void seed(ConfigRepository configs) throws Exception {
        Method method = Bootstrap.class.getDeclaredMethod("seedDefaultDevices", ConfigRepository.class);
        method.setAccessible(true);
        method.invoke(null, configs);
    }

    @Test
    void demoSessionRejectsPortsThatWouldOverflow() throws Exception {
        SessionManager sessions = new SessionManager();
        SessionLauncher launcher = new SessionLauncher(sessions, new EventBus(), this.workDir, 5000);
        RuntimeOptions options = RuntimeOptions.parse(new String[] {
                "--demo-session", "--hsms-port", "65535", "--work-dir", this.workDir.toString() });

        assertEquals(3, startDemoSession(options, launcher), "第二台演示设备的端口会越界，必须提前拒绝");
        assertTrue(sessions.list().isEmpty(), "越界时不能留下半个演示会话");
    }

    @Test
    void demoSessionRejectsDeviceIdsThatWouldOverflow() throws Exception {
        SessionManager sessions = new SessionManager();
        SessionLauncher launcher = new SessionLauncher(sessions, new EventBus(), this.workDir, 5000);
        RuntimeOptions options = RuntimeOptions.parse(new String[] {
                "--demo-session", "--device-id", "32767", "--work-dir", this.workDir.toString() });

        assertEquals(3, startDemoSession(options, launcher), "第二台演示设备的 device-id 会越界");
        assertTrue(sessions.list().isEmpty());
    }

    private static int startDemoSession(RuntimeOptions options, SessionLauncher launcher) throws Exception {
        Method method = Bootstrap.class.getDeclaredMethod("startDemoSession",
                RuntimeOptions.class, SessionLauncher.class, ConfigStore.class);
        method.setAccessible(true);
        try (Db db = Db.open(Path.of(options.workDir().toString(), "..storage-probe-")
                .normalize())) {
            /* 越界校验发生在读配置之前：给一个空库的门面即可 */
            ConfigStore store = new ConfigStore(
                    new ConfigRepository(db, new com.shimizukenta.secssim.runtime.store.ConfigProjector()),
                    new TemplateRepository(db), options.workDir());
            return ((Integer) method.invoke(null, options, launcher, store)).intValue();
        }
    }

    private ConfigRepository repo() {
        Db db = Db.open(this.workDir.resolve("seed-db").normalize());
        return new ConfigRepository(db, new com.shimizukenta.secssim.runtime.store.ConfigProjector());
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = Bootstrap.class.getResourceAsStream("/defaults/" + name)) {
            if (in == null) {
                throw new IOException("缺少内置默认配置: /defaults/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
