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
import org.junit.jupiter.api.io.TempDir;

import com.shimizukenta.secssim.app.config.SessionConfigJson;
import com.shimizukenta.secssim.app.event.EventBus;
import com.shimizukenta.secssim.app.session.SessionManager;

/**
 * 内置默认配置的播种语义。
 *
 * <p>播种曾经靠"把模板文本里的 {@code "port": 5000} 替换成启动端口"来让
 * {@code --demo-session} 生效：模板一被格式化（空格/换行变化）替换就静默失效，
 * 演示端口永远是 5000。现在端口/deviceId 由 {@link SessionOverrides} 在启动时
 * 覆盖，播种只做"原样落盘 + 幂等不覆盖"。</p>
 */
class BootstrapSeedingTest {

    private static final String[] NAMES = {"demo-etch.json", "demo-cvd.json"};

    @TempDir
    Path workDir;

    @Test
    void seedsTemplatesVerbatim() throws Exception {
        seed(workDir);

        for (String name : NAMES) {
            Path file = workDir.resolve(name);
            assertTrue(Files.exists(file), name + " 必须被播种");

            String seeded = Files.readString(file, StandardCharsets.UTF_8);
            assertEquals(resource(name), seeded, name + " 必须原样落盘（不得改写端口/deviceId）");
            assertDoesNotThrow(() -> SessionConfigJson.parse(seeded), name + " 播种内容必须能解析");
        }
    }

    @Test
    void doesNotOverwriteUserEditedFile() throws Exception {
        Path edited = workDir.resolve("demo-etch.json");
        Files.writeString(edited, "{\"user\":true}", StandardCharsets.UTF_8);

        seed(workDir);

        assertEquals("{\"user\":true}", Files.readString(edited, StandardCharsets.UTF_8),
                "用户改过的配置不能被播种覆盖");
        assertTrue(Files.exists(workDir.resolve("demo-cvd.json")), "缺失的模板仍应补齐");
    }

    /** 播种是私有的启动步骤；用反射直接验证，避免为测试放宽生产 API。 */
    private static void seed(Path dir) throws Exception {
        Method method = Bootstrap.class.getDeclaredMethod("seedDefaultConfigs", Path.class);
        method.setAccessible(true);
        method.invoke(null, dir);
    }

    @Test
    void demoSessionRejectsPortsThatWouldOverflow() throws Exception {
        SessionManager sessions = new SessionManager();
        SessionLauncher launcher = new SessionLauncher(sessions, new EventBus(), this.workDir, 5000);
        RuntimeOptions options = RuntimeOptions.parse(new String[] {
                "--demo-session", "--hsms-port", "65535", "--work-dir", this.workDir.toString() });

        assertEquals(3, startDemoSession(options, launcher), "第二台演示设备的端口会越界，必须提前拒绝");
        assertTrue(sessions.list().isEmpty(), "越界时不能留下半个演示会话");
        assertFalse(Files.exists(this.workDir.resolve("demo-etch.json")),
                "越界时不得落模板文件（否则下次启动要手工清理）");
    }

    @Test
    void demoSessionRejectsDeviceIdsThatWouldOverflow() throws Exception {
        SessionManager sessions = new SessionManager();
        SessionLauncher launcher = new SessionLauncher(sessions, new EventBus(), this.workDir, 5000);
        RuntimeOptions options = RuntimeOptions.parse(new String[] {
                "--demo-session", "--device-id", "32767", "--work-dir", this.workDir.toString() });

        assertEquals(3, startDemoSession(options, launcher), "第二台演示设备的 device-id 会越界");
        assertTrue(sessions.list().isEmpty());
        assertFalse(Files.exists(this.workDir.resolve("demo-cvd.json")),
                "越界时不得落模板文件");
    }

    private static int startDemoSession(RuntimeOptions options, SessionLauncher launcher) throws Exception {
        Method method = Bootstrap.class.getDeclaredMethod("startDemoSession",
                RuntimeOptions.class, SessionLauncher.class);
        method.setAccessible(true);
        return ((Integer) method.invoke(null, options, launcher)).intValue();
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
