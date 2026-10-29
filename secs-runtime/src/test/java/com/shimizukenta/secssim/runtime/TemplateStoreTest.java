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

    @TempDir
    Path temp;

    @Test
    void listsBuiltinsAndManagesCustomTemplates() throws Exception {
        TemplateStore store = new TemplateStore(this.temp);

        assertTrue(store.list().stream().anyMatch((entry) -> entry.id().equals("bare") && entry.source().equals("builtin")));
        assertTrue(store.customText("bare").isEmpty(), "内置模板没有自定义文件");

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
}
