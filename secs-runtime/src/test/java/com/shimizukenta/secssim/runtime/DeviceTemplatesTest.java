package com.shimizukenta.secssim.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.api.http.ConfigOutcome;

/**
 * 内置设备模板：目录、按模板新建、未知模板拒绝。
 *
 * <p>核心断言是「每份模板都能编译」：模板是代码里的字符串，
 * 改了模板忘了跟着改设备模型时，用户拿到的会是一份起不来的起点。
 * 校验走 {@link ConfigStore#validate}——与保存、启动同一条判据。</p>
 */
class DeviceTemplatesTest {

    @Test
    void everyBuiltInTemplateCompiles() {
        for (DeviceTemplates.Entry entry : DeviceTemplates.catalog()) {
            String text = DeviceTemplates.json(entry.id(), "dev-1.json").orElseThrow();
            assertEquals(List.of(), ConfigStore.validate(text),
                    "模板 " + entry.id() + " 必须自己就能编译");
        }
    }

    @Test
    void templateFillsSessionIdAndEquipmentNameFromFileName() {
        String text = DeviceTemplates.json("etch", "etch-a.json").orElseThrow();
        assertTrue(text.contains("\"id\": \"etch-a\""), text);
        assertTrue(text.contains("\"name\": \"ETCH-A\""), text);
    }

    @Test
    void bareTemplateMatchesTheSkeleton() {
        assertEquals(ConfigTemplate.skeleton("line1.json"),
                DeviceTemplates.json(DeviceTemplates.BARE_ID, "line1.json").orElseThrow(),
                "空骨架应与 ConfigTemplate.skeleton 同源");
    }

    /** 双 SITE 测试机演示多单元号段：SITE2 的绝对 id 必须落在自己的段里。 */
    @Test
    void testerTemplateSpreadsUnitsAcrossIdSegments() {
        String text = DeviceTemplates.json("tester", "t.json").orElseThrow();
        /* SITE2 事件表达式引用 1100 段的 SVID，说明号段真的错开了 */
        assertTrue(text.contains("svid.1103"), text);
        assertTrue(text.contains("\"ceid\": 2101"), text);
    }

    @Test
    void unknownTemplateIsRejectedNotSilentlyBare() {
        assertTrue(DeviceTemplates.json("no-such-device", "x.json").isEmpty(),
                "未知模板必须返回空，不能悄悄退回骨架");
    }

    @Test
    void storeCreateWithTemplateWritesCompilableConfig() throws Exception {
        java.nio.file.Path workDir = java.nio.file.Files.createTempDirectory("devtpl-test");
        try {
            var db = com.shimizukenta.secssim.store.Db.open(workDir);
            var repo = new com.shimizukenta.secssim.store.ConfigRepository(
                    db, new com.shimizukenta.secssim.runtime.store.ConfigProjector());
            ConfigStore store = new ConfigStore(repo,
                    new com.shimizukenta.secssim.store.TemplateRepository(db), workDir);

            var ok = store.create("etch-1.json", "etch");
            assertEquals(ConfigOutcome.OK, ok.outcome(), ok.problems().toString());
            String text = store.read("etch-1.json");
            assertEquals(List.of(), ConfigStore.validate(text));

            var unknown = store.create("bad.json", "no-such-device");
            assertEquals(ConfigOutcome.INVALID, unknown.outcome());
            assertFalse(unknown.problems().isEmpty(), "未知模板要带出可用模板清单");
            assertTrue(store.list().stream().noneMatch((e) -> e.name().equals("bad.json")),
                    "失败的创建不许留下半截配置");

            /* 老的单参入口仍是骨架，行为不变 */
            var bare = store.create("bare-1.json");
            assertEquals(ConfigOutcome.OK, bare.outcome());
            db.close();
        } finally {
            try (var files = java.nio.file.Files.list(workDir)) {
                files.forEach(p -> p.toFile().delete());
            }
            workDir.toFile().delete();
        }
    }
}
