package com.shimizukenta.secssim.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import com.shimizukenta.secssim.api.http.ConfigOutcome;
import com.shimizukenta.secssim.app.config.ParamTemplateBody;
import com.shimizukenta.secssim.store.Db;
import com.shimizukenta.secssim.store.ParamTemplateRepository;

/** ParamTemplateStore：校验入库、内置只读、id 生成、列表带体。 */
class ParamTemplateStoreTest {

    private static final String SINE = "{\"curve\":{\"type\":\"sine\",\"min\":0,\"max\":10,\"periodMs\":1000}}";
    private static final String BAD = "{\"curve\":{\"type\":\"没有这种曲线\"}}";

    @TempDir(cleanup = CleanupMode.NEVER)
    Path temp;

    @Test
    void everyBuiltinPresetNormalizes() {
        for (ParamTemplates.Entry entry : ParamTemplates.catalog()) {
            String normalized = ParamTemplateBody.normalize(entry.body());
            assertTrue(normalized.contains("\"result\""), entry.id());
        }
    }

    @Test
    void saveCreateUpdateDeleteAndReadonlyBuiltin() {
        ParamTemplateStore store = store();

        /* 新建：服务端生成 pt_ id，简形归一化为完整形入库 */
        var created = store.save("", "模板甲", "说明", SINE);
        assertEquals(ConfigOutcome.OK, created.result().outcome());
        assertNotNull(created.id());
        assertTrue(created.id().startsWith("pt_"));
        String list = store.listJson();
        assertTrue(list.contains("模板甲"), list);
        assertTrue(list.contains("\"result\""), "入库的是归一化完整形: " + list);
        assertTrue(list.contains("\"readonly\":false"), list);

        /* 带 id 更新 */
        var updated = store.save(created.id(), "模板乙", "新说明",
                "{\"curves\":[{\"id\":\"c\",\"type\":\"constant\",\"value\":1}],\"result\":\"c.V\"}");
        assertEquals(ConfigOutcome.OK, updated.result().outcome());
        assertEquals(created.id(), updated.id());
        assertTrue(store.listJson().contains("模板乙"));

        /* 校验不过不落盘 */
        var invalid = store.save("", "坏模板", "", BAD);
        assertEquals(ConfigOutcome.INVALID, invalid.result().outcome());
        assertTrue(!invalid.result().problems().isEmpty());
        assertTrue(store.listJson().contains("模板甲") || store.listJson().contains("模板乙"));

        /* 内置只读（与生产一致的播种） */
        assertEquals(ConfigOutcome.CONFLICT, store.save("builtin-1", "改内置", "", SINE).result().outcome());
        assertEquals(ConfigOutcome.CONFLICT, store.delete("builtin-1").outcome());

        /* 删除自定义 */
        assertEquals(ConfigOutcome.OK, store.delete(created.id()).outcome());
        assertTrue(!store.listJson().contains(created.id()));

        /* 名字必填 */
        assertEquals(ConfigOutcome.INVALID, store.save("", " ", "", SINE).result().outcome());
    }

    private ParamTemplateStore store() {
        Db db = Db.open(this.temp);
        ParamTemplateRepository repo = new ParamTemplateRepository(db);
        repo.seed("builtin-1", "恒温保温", "内置", "{\"curves\":[{\"id\":\"main\",\"type\":\"constant\",\"value\":1}],\"result\":\"main.V\"}");
        return new ParamTemplateStore(repo);
    }

    /**
     * 内置目录缩小后，老库里多出来的内置行要跟着下线；自定义模板不受影响。
     *
     * <p>这是「内置多少套」的唯一控制点：目录是 20 套，库里就不能还留着上一版
     * 的 41 套——否则删条目等于没删。</p>
     */
    @Test
    void pruneBuiltinDropsRowsNoLongerInCatalogButKeepsCustom() {
        Db db = Db.open(this.temp);
        ParamTemplateRepository repo = new ParamTemplateRepository(db);
        String body = "{\"curves\":[{\"id\":\"main\",\"type\":\"constant\",\"value\":1}],\"result\":\"main.V\"}";
        repo.seed("keep", "留下", "", body);
        repo.seed("drop", "下线", "", body);
        repo.createCustom("custom-1", "自定义", "", body);

        assertEquals(1, repo.pruneBuiltin(java.util.List.of("keep")));

        assertEquals(java.util.Set.of("custom-1", "keep"),
                repo.list().stream().map(ParamTemplateRepository.ParamTemplateView::id)
                        .collect(java.util.stream.Collectors.toSet()));
        /* 再跑一次是幂等的：没有可下线的行 */
        assertEquals(0, repo.pruneBuiltin(java.util.List.of("keep")));
    }
}
