package com.shimizukenta.secssim.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.app.config.SessionConfig;
import com.shimizukenta.secssim.app.config.SessionConfigJson;
import com.shimizukenta.secssim.app.config.ValueSources;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.value.ValueSource;

/**
 * 内置默认设备配置（resources/defaults）的编译守门。
 *
 * <p>这两份 JSON 是"随包分发的活文档"——演示三类数据（晶圆属性 / 设备常量 / 参数变量）
 * 各自的值定义，以及一条 wafer 周期。它们一旦编译不过，播种与
 * {@code --demo-session} 全部失效，所以必须在单测里跟产品代码一起验证。</p>
 */
class DefaultConfigsTest {

    private static SessionConfig load(String name) throws IOException {
        try (InputStream in = DefaultConfigsTest.class.getResourceAsStream("/defaults/" + name)) {
            if (in == null) {
                throw new IOException("缺少内置默认配置: /defaults/" + name);
            }
            return SessionConfigJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void demoEtchCompilesAndBuildsValues() throws IOException {
        SessionConfig config = load("demo-etch.json");
        CompilationResult result = config.compile();
        assertTrue(result.ok(), "demo-etch 编译失败: " + result.errors());

        ValueSource values = ValueSources.of(config, result.equipment());
        /* CH1 TEMP_PV：randomWalk(start=180) */
        double v = values.valueAt(1001, 0L).orElseThrow(() -> new AssertionError("CH1.TEMP_PV 无值"));
        assertTrue(v >= 0 && v <= 400, "值应在配置值域内: " + v);
        /* CH2 PRESS：sine 0.2..2.0（相位重放） */
        double p = values.valueAt(1103, 0L).orElseThrow(() -> new AssertionError("CH2.PRESS 无值"));
        assertTrue(p >= 0.2 && p <= 2.0, "曲线驱动的值应在曲线值域内: " + p);
        /* 全局 SVID 内联 sine */
        assertTrue(values.valueAt(9001, 0L).isPresent(), "FACILITY_TEMP 应有值");
    }

    @Test
    void demoCvdCompilesAndBuildsValues() throws IOException {
        SessionConfig config = load("demo-cvd.json");
        CompilationResult result = config.compile();
        assertTrue(result.ok(), "demo-cvd 编译失败: " + result.errors());

        ValueSource values = ValueSources.of(config, result.equipment());
        /* DEP.PRES 走 expr + 私有 vars（clamp 0..10） */
        double p = values.valueAt(1002, 12345L).orElseThrow(() -> new AssertionError("DEP.PRES 无值"));
        assertTrue(p >= 0 && p <= 10, "表达式输出应在 clamp 值域内: " + p);
        assertTrue(config.schedule().plans().isEmpty(), "样例的周期事件应全部写在 cycle 段里");
    }

    @Test
    void bothDemoConfigsCarryThreeDataKinds() throws IOException {
        for (String name : new String[] {"demo-etch.json", "demo-cvd.json"}) {
            SessionConfig config = load(name);
            assertTrue(config.hasSources(), name + " 应给变量配值定义");
            assertEquals(5, config.context().size(), name + " 应演示 5 个晶圆属性");
            assertNotNull(config.cycle(), name + " 应演示 wafer 周期");
            /* 周期事件在构建期按片展开：片数 × 每条事件 + 手写计划 */
            int perCycle = config.cycle().events().size();
            int expected = config.cycle().count() * perCycle + config.schedule().plans().size();
            assertEquals(expected, config.effectiveSchedule().plans().size(),
                    name + " 的周期事件应逐片展开");
        }
    }
}
