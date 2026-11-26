package com.shimizukenta.secssim.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/** TemplateRepository（设备模板）：播种幂等（含软删）、自定义 CRUD 与冲突判据。 */
class TemplateRepositoryTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    private Db db;
    private TemplateRepository repo;

    void setUp() {
        this.db = Db.open(this.workDir);
        this.repo = new TemplateRepository(this.db);
    }

    @Test
    void seedIsIdempotent() {
        setUp();

        assertTrue(this.repo.seed("t-1", "标签", "说明", true, "{}"), "首播应成功");
        assertFalse(this.repo.seed("t-1", "标签", "说明", true, "{}"), "重复播应跳过");
        assertTrue(this.repo.find("t-1").orElseThrow().readonly());
    }

    /** P2-2：软删同 id 行存在时，播种应跳过而不是 PRIMARY KEY 失败。 */
    @Test
    void seedSkipsSoftDeletedRowWithoutPrimaryKeyFailure() {
        setUp();

        this.repo.createCustom("t-x", "甲", "", "{}");
        this.repo.deleteCustom("t-x");
        assertFalse(this.repo.seed("t-x", "甲", "", true, "{}"), "软删同 id 行应被跳过");
        assertTrue(this.repo.find("t-x").isEmpty(), "播种不得复活软删行");
    }

    /** P3-1：同 id 冲突按扩展错误码判定，回 CONFLICT。 */
    @Test
    void duplicateCreateIsConflict() {
        setUp();

        this.repo.createCustom("t-1", "甲", "", "{}");
        StoreException e = assertThrows(StoreException.class,
                () -> this.repo.createCustom("t-1", "乙", "", "{}"));
        assertEquals(StoreException.Kind.CONFLICT, e.kind());
    }
}