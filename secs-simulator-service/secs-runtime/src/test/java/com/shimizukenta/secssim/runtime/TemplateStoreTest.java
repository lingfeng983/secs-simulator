package com.shimizukenta.secssim.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shimizukenta.secssim.api.http.ConfigOutcome;

/** 模板仓库：内置只读、自定义增删改查、配置标记。 */
class TemplateStoreTest {

    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path temp;

    @Test
    void listsBuiltinsAndManagesCustomTemplates() throws Exception {
        TemplateStore store = store();

        assertTrue(store.list().stream().anyMatch((entry) -> entry.id().equals("bare") && entry.source().equals("builtin")));
        assertTrue(store.customText("bare").isEmpty(), "内置模板没有自定义正文");

        String text = DeviceTemplates.json("bare", "device.json").orElseThrow();
        String marked = TemplateStore.withTemplate(text, "my-tpl");
        assertEquals(ConfigOutcome.OK, store.save("my-tpl", marked).outcome());
        String saved = store.text("my-tpl").orElseThrow();
        assertTrue(saved.contains("\"template\"") && saved.contains("my-tpl"));
        assertTrue(store.list().stream().anyMatch((entry) -> entry.id().equals("my-tpl") && entry.source().equals("custom")));

        assertEquals(ConfigOutcome.CONFLICT, store.save("bare", marked).outcome(), "内置模板不能覆盖");
        assertEquals(ConfigOutcome.CONFLICT, store.delete("bare").outcome(), "内置模板不能删除");

        assertEquals(ConfigOutcome.OK, store.delete("my-tpl").outcome());
        assertFalse(store.list().stream().anyMatch((entry) -> entry.id().equals("my-tpl")));
    }

    /** 库支撑的模板门面（内置模板先播种，与生产一致）。 */
    private TemplateStore store() {
        var db = com.shimizukenta.secssim.store.Db.open(this.temp);
        var repo = new com.shimizukenta.secssim.store.ConfigRepository(
                db, new com.shimizukenta.secssim.runtime.store.ConfigProjector());
        var templates = new com.shimizukenta.secssim.store.TemplateRepository(db);
        for (DeviceTemplates.Entry entry : DeviceTemplates.catalog()) {
            templates.seed(entry.id(), entry.label(), entry.description(), true,
                    DeviceTemplates.json(entry.id(), "device.json").orElseThrow());
        }
        return new TemplateStore(this.temp, templates);
    }
}
