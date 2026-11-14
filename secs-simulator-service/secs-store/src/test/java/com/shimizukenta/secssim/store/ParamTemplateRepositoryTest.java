package com.shimizukenta.secssim.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/** ParamTemplateRepository：播种幂等、内置只读、自定义增删改查、软删。 */
class ParamTemplateRepositoryTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    private Db db;
    private ParamTemplateRepository repo;

    void setUp() {
        this.db = Db.open(this.workDir);
        this.repo = new ParamTemplateRepository(this.db);
    }

    @Test
    void seedIsIdempotentAndBuiltinIsReadOnly() {
        setUp();

        assertTrue(this.repo.seed("builtin-1", "恒温", "说明", "{\"curve\":{}}"), "首播应成功");
        assertFalse(this.repo.seed("builtin-1", "恒温", "说明", "{\"curve\":{}}"), "重复播应跳过");

        var view = this.repo.find("builtin-1").orElseThrow();
        assertEquals("builtin", view.source());
        assertTrue(view.readonly());

        assertThrows(StoreException.class, () -> this.repo.saveCustom("builtin-1", "改", "改", "{}"));
        assertThrows(StoreException.class, () -> this.repo.deleteCustom("builtin-1"));
        assertTrue(this.repo.find("builtin-1").isPresent(), "只读拒绝后行还在");
    }

    @Test
    void createSaveAndDeleteCustom() {
        setUp();

        this.repo.createCustom("pt-1", "模板甲", "说明", "{\"curve\":{}}");
        assertEquals("custom", this.repo.find("pt-1").orElseThrow().source());
        assertEquals("{\"curve\":{}}", this.repo.body("pt-1").orElseThrow());

        this.repo.saveCustom("pt-1", "模板乙", "新说明", "{\"curves\":[]}");
        var updated = this.repo.find("pt-1").orElseThrow();
        assertEquals("模板乙", updated.name());
        assertEquals("{\"curves\":[]}", updated.body());

        assertTrue(this.repo.list().stream().anyMatch((v) -> v.id().equals("pt-1")));
        this.repo.deleteCustom("pt-1");
        assertTrue(this.repo.find("pt-1").isEmpty(), "删除后列表与查找都不可见");
        assertTrue(this.repo.list().stream().noneMatch((v) -> v.id().equals("pt-1")));
    }

    @Test
    void duplicateCreateIsConflict() {
        setUp();

        this.repo.createCustom("pt-1", "甲", "", "{}");
        StoreException e = assertThrows(StoreException.class,
                () -> this.repo.createCustom("pt-1", "乙", "", "{}"));
        assertEquals(StoreException.Kind.CONFLICT, e.kind());
    }

    @Test
    void missingTemplateIsNotFound() {
        setUp();

        StoreException e = assertThrows(StoreException.class,
                () -> this.repo.saveCustom("ghost", "名", "", "{}"));
        assertEquals(StoreException.Kind.NOT_FOUND, e.kind());
    }
}
