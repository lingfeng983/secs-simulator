package com.shimizukenta.secssim.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * 迁移：JSON → config.db 的复制语义、自检、归档与幂等续跑；--export-config 回滚通道。
 */
class MigratorTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    /** 桩投影：只记正文。 */
    private static final class NoopProjector implements ConfigRepository.DocumentProjector {
        @Override
        public void project(Connection connection, String deviceId, String body) {
            /* 桩：不写投影行 */
        }
    }

    private record Store(Db db, ConfigRepository configs, TemplateRepository templates,
                         ConfigMigrator migrator) {
    }

    private Store open() {
        Db db = Db.open(this.workDir);
        ConfigRepository configs = new ConfigRepository(db, new NoopProjector());
        TemplateRepository templates = new TemplateRepository(db);
        return new Store(db, configs, templates, new ConfigMigrator(db, configs, templates));
    }

    @Test
    void importsConfigsAndTemplatesThenArchives() throws Exception {
        Files.writeString(this.workDir.resolve("line1.json"), "{\"schemaVersion\":6}");
        Files.writeString(this.workDir.resolve("cvd.json"), "{\"schemaVersion\":6}");
        Files.createDirectories(this.workDir.resolve("templates"));
        Files.writeString(this.workDir.resolve("templates").resolve("my-tpl.json"),
                "{\"session\":{\"id\":\"tpl\"}}");

        Store store = open();
        assertTrue(store.migrator().needed(this.workDir));
        ConfigMigrator.Report report = store.migrator().migrate(this.workDir,
                java.util.Map.of("bare", "{\"session\":{}}"));

        assertEquals(2, report.devices());
        assertEquals(2, report.templates(), "内置种子 1 + 自定义 1");
        assertTrue(report.problems().isEmpty(), () -> String.join("; ", report.problems()));
        assertTrue(report.archived());

        /* 正文逐字节一致（自检的另一种验证） */
        var doc = store.configs().readDocument("line1");
        assertTrue(doc.isPresent());
        assertEquals("{\"schemaVersion\":6}", doc.get().body());
        assertEquals(ContentHash.sha256Hex("{\"schemaVersion\":6}"), doc.get().sha256());

        /* 原文件已归档到 legacy/，不再留在 work-dir 顶层 */
        assertTrue(Files.isRegularFile(this.workDir.resolve("legacy").resolve("line1.json")));
        assertFalse(Files.exists(this.workDir.resolve("line1.json")));
        assertEquals("files@" + java.time.LocalDate.now(), store.db().meta("config", "migration_source"));

        /* 内置模板播种且只读 */
        assertTrue(store.templates().find("bare").isPresent());
        assertTrue(store.templates().find("bare").get().readonly());
        StoreException e = assertThrows(StoreException.class,
                () -> store.templates().deleteCustom("bare"));
        assertEquals(StoreException.Kind.CONFLICT, e.kind());
        store.db().close();
    }

    @Test
    void migrationIsIdempotentAndDetectsBadFiles() throws Exception {
        Files.writeString(this.workDir.resolve("good.json"), "{\"a\":1}");
        Files.writeString(this.workDir.resolve("bad.json"), "{not-json");

        Store store = open();
        ConfigMigrator.Report report = store.migrator().migrate(this.workDir, java.util.Map.of());
        assertTrue(report.problems().stream().anyMatch((p) -> p.contains("bad.json")),
                () -> String.join("; ", report.problems()));
        assertFalse(report.archived(), "自检不过不归档");
        /* 好文件已入库，坏文件原地不动（可用 2.x 方式继续） */
        assertTrue(store.configs().findDevice("good").isPresent());
        assertTrue(Files.exists(this.workDir.resolve("bad.json")));
        store.db().close();

        /* 续跑：修好坏文件后再迁移，good 以 SHA 判重跳过 */
        Files.writeString(this.workDir.resolve("bad.json"), "{\"b\":2}");
        Store again = open();
        ConfigMigrator.Report second = again.migrator().migrate(this.workDir, java.util.Map.of());
        assertTrue(second.problems().isEmpty(), () -> String.join("; ", second.problems()));
        assertTrue(second.archived());
        assertEquals(2, again.configs().listDevices().size());
        again.db().close();
    }

    @Test
    void exportRoundTripsBytesExactly() throws Exception {
        String body = "{\"schemaVersion\":6,\"session\":{\"id\":\"round\"}}";
        Files.writeString(this.workDir.resolve("round.json"), body);
        Store store = open();
        store.migrator().migrate(this.workDir, java.util.Map.of());

        Path out = this.workDir.resolve("exported");
        assertEquals(1, store.migrator().exportTo(out));
        assertEquals(body, Files.readString(out.resolve("round.json")));
        store.db().close();
    }

    @Test
    void listRevisionsComesBackNewestFirst() throws Exception {
        Files.writeString(this.workDir.resolve("r.json"), "{\"v\":1}");
        Store store = open();
        store.migrator().migrate(this.workDir, java.util.Map.of());
        store.configs().saveDocument("r", "{\"v\":2}", null, "test", "");
        List<ConfigRepository.RevisionView> revisions = store.configs().listRevisions("r", 10);
        assertEquals(2, revisions.size());
        assertEquals(2, revisions.get(0).revision());
        store.db().close();
    }
}
