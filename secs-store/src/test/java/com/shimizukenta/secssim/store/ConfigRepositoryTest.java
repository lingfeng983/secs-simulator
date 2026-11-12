package com.shimizukenta.secssim.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * ConfigRepository：唯一写口、乐观锁、快照与裁剪、updateConstants 不漂移、软删除。
 *
 * <p>投影用桩实现（记录每次投影的正文），SQL 层行为是这里的被测对象。</p>
 */
class ConfigRepositoryTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    /** 记录投影调用的桩。 */
    private static final class RecordingProjector implements ConfigRepository.DocumentProjector {
        final List<String> bodies = new ArrayList<>();

        @Override
        public void project(Connection connection, String deviceId, String body) throws SQLException {
            this.bodies.add(body);
            try (var st = connection.prepareStatement(
                    "INSERT INTO unit_def(device_id, unit_no, name, inject_unit_id) VALUES(?,?,?,?)")) {
                st.setString(1, deviceId);
                st.setInt(2, 1);
                st.setInt(3, body.length());
                st.setInt(4, 0);
                st.executeUpdate();
            }
        }
    }

    private static final String BODY_V1 = "{\"schemaVersion\":6,\"session\":{\"id\":\"a\"}}";

    private Db db;
    private RecordingProjector projector;
    private ConfigRepository repo;

    void setUp() {
        this.db = Db.open(this.workDir);
        this.projector = new RecordingProjector();
        this.repo = new ConfigRepository(this.db, this.projector);
    }

    @Test
    void createSaveFindAndIdempotentSave() {
        setUp();
        String id = this.repo.createDevice("line1", "line1", "etch", BODY_V1, true, "test");
        assertTrue(id.startsWith("dev_"));

        /* 按 legacy_id 与 id 都能找到 */
        assertTrue(this.repo.findDevice("line1").isPresent());
        assertTrue(this.repo.findDevice(id).isPresent());
        assertEquals(1, this.repo.findDevice(id).get().revision());

        /* 首次投影发生在 create */
        assertEquals(1, this.projector.bodies.size());

        /* 保存升版本 + 快照 + 重投影 */
        long v2 = this.repo.saveDocument("line1", BODY_V1.replace("a", "b"), 1L, "test", "改动");
        assertEquals(2, v2);
        assertEquals(2, this.projector.bodies.size());

        /* 相同正文保存是幂等的：不升版本 */
        long same = this.repo.saveDocument("line1", BODY_V1.replace("a", "b"), null, "test", "");
        assertEquals(2, same);
        assertEquals(2, this.projector.bodies.size());

        /* 版本历史：v1 与 v2 都在 */
        assertEquals(2, this.repo.listRevisions("line1", 50).size());
        assertTrue(this.repo.readRevision("line1", 1).isPresent());
    }

    @Test
    void revisionConflictIsDetected() {
        setUp();
        this.repo.createDevice("line1", "line1", "etch", BODY_V1, true, "test");
        StoreException e = assertThrows(StoreException.class,
                () -> this.repo.saveDocument("line1", BODY_V1 + " ", 99L, "test", ""));
        assertEquals(StoreException.Kind.CONFLICT, e.kind());
    }

    @Test
    void duplicateNameIsRejectedButTombstoneAllowsReuse() {
        setUp();
        this.repo.createDevice("line1", "line1", "etch", BODY_V1, true, "test");
        StoreException e = assertThrows(StoreException.class,
                () -> this.repo.createDevice("line1", "line2", "etch", BODY_V1, true, "test"));
        assertEquals(StoreException.Kind.CONFLICT, e.kind());

        /* 软删后名字可复用（唯一索引只约束未删除行） */
        this.repo.deleteDevice("line1");
        assertTrue(this.repo.findDevice("line1").isEmpty());
        String reused = this.repo.createDevice("line1", "line1-b", "etch", BODY_V1, true, "test");
        assertTrue(reused.startsWith("dev_"));
    }

    @Test
    void updateConstantsBumpsRevisionWithoutSnapshotAndAlignsRuntime() {
        setUp();
        String id = this.repo.createDevice("line1", "line1", "etch", BODY_V1, true, "test");

        /* 先放一条 device_link（模拟设备已连接；按解析后的设备 id 建行） */
        RuntimeStateRepository runtime = new RuntimeStateRepository(this.db);
        runtime.upsertDeviceLink(id, "CONNECTED", 1L);

        long v2 = this.repo.updateConstants("line1", BODY_V1 + " ", "device");
        assertEquals(2, v2);
        /* 链路版本被追平：不产生配置漂移 */
        assertEquals(2L, runtime.deviceLink(id).get().configRevision());

        /* 不写快照：版本历史仍只有 v1 */
        assertEquals(1, this.repo.listRevisions("line1", 50).size());
    }

    @Test
    void revisionRetentionTrimsToTwoHundred() {
        setUp();
        this.repo.createDevice("line1", "line1", "etch", BODY_V1, true, "test");
        long revision = 1;
        for (int i = 0; i < 230; i++) {
            revision = this.repo.saveDocument("line1", BODY_V1 + "//" + i, null, "test", "");
        }
        assertEquals(231, revision);
        List<ConfigRepository.RevisionView> revisions = this.repo.listRevisions("line1", 10_000);
        assertEquals(ConfigRepository.REVISION_RETENTION, revisions.size(), "快照应裁剪到保留窗口");
        /* 保留的是最新的 200 版（当前版本 231 → 最早保留 32） */
        assertEquals(revision, revisions.get(0).revision());
    }

    @Test
    void legacyIdResolutionPrefersExplicitLegacy() {
        setUp();
        String id = this.repo.createDevice("新设备", "legacy-stem", "etch", BODY_V1, true, "test");
        /* key 解析只认 legacy_id 与 id（v1 的 name == legacy stem） */
        assertTrue(this.repo.findDevice("legacy-stem").isPresent());
        assertTrue(this.repo.findDevice(id).isPresent());
        assertFalse(this.repo.findDevice("没有的").isPresent());
    }
}
