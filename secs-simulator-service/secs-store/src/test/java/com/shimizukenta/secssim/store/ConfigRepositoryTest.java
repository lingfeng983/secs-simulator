package com.shimizukenta.secssim.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * ConfigRepository：唯一写口、乐观锁、快照与裁剪、updateConstants 不漂移、软删除、导出。
 */
class ConfigRepositoryTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    private static final String BODY_V1 = "{\"schemaVersion\":6,\"session\":{\"id\":\"a\"}}";

    private Db db;
    private ConfigRepository repo;

    void setUp() {
        this.db = Db.open(this.workDir);
        this.repo = new ConfigRepository(this.db);
    }

    @Test
    void createSaveFindAndIdempotentSave() {
        setUp();
        String id = this.repo.createDevice("line1", "etch", BODY_V1, true, "test");
        assertTrue(id.startsWith("dev_"));

        /* 按设备名与设备 id 都能找到 */
        assertTrue(this.repo.findDevice("line1").isPresent());
        assertTrue(this.repo.findDevice(id).isPresent());
        assertEquals(1, this.repo.findDevice(id).get().revision());

        /* 保存升版本 + 快照 */
        long v2 = this.repo.saveDocument("line1", BODY_V1.replace("a", "b"), 1L, "test", "改动");
        assertEquals(2, v2);

        /* 相同正文保存是幂等的：不升版本 */
        long same = this.repo.saveDocument("line1", BODY_V1.replace("a", "b"), null, "test", "");
        assertEquals(2, same);

        /* 版本历史：v1 与 v2 都在 */
        assertEquals(2, this.repo.listRevisions("line1", 50).size());
        assertTrue(this.repo.readRevision("line1", 1).isPresent());
    }

    @Test
    void revisionConflictIsDetected() {
        setUp();
        this.repo.createDevice("line1", "etch", BODY_V1, true, "test");
        StoreException e = assertThrows(StoreException.class,
                () -> this.repo.saveDocument("line1", BODY_V1 + " ", 99L, "test", ""));
        assertEquals(StoreException.Kind.CONFLICT, e.kind());
    }

    @Test
    void duplicateNameIsRejected() {
        setUp();
        this.repo.createDevice("line1", "etch", BODY_V1, true, "test");
        StoreException e = assertThrows(StoreException.class,
                () -> this.repo.createDevice("line1", "etch", BODY_V1, true, "test"));
        assertEquals(StoreException.Kind.CONFLICT, e.kind());
    }

    @Test
    void updateConstantsBumpsRevisionWithoutSnapshotAndAlignsRuntime() {
        setUp();
        String id = this.repo.createDevice("line1", "etch", BODY_V1, true, "test");

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

    /** P1-3：带 expectedRevision 的常量回写——读改写窗口内的用户编辑不能被覆盖。 */
    @Test
    void updateConstantsWithExpectedRevisionDetectsConcurrentEdit() {
        setUp();
        this.repo.createDevice("line1", "etch", BODY_V1, true, "test");

        /* 基于 v1 的回写正常成功 */
        long v2 = this.repo.updateConstants("line1", BODY_V1 + " ", 1L, "device");
        assertEquals(2, v2);

        /* 模拟读改写窗口：设备侧读到 v2 后，用户 saveDocument 提交到 v3 */
        long staleBase = 2L;
        this.repo.saveDocument("line1", BODY_V1 + "//user", null, "test", "用户编辑");
        StoreException e = assertThrows(StoreException.class,
                () -> this.repo.updateConstants("line1", BODY_V1 + " ", staleBase, "device"));
        assertEquals(StoreException.Kind.CONFLICT, e.kind());
        /* 冲突不落笔：正文仍是用户编辑后的那份，版本停在 v3 */
        assertEquals(BODY_V1 + "//user", this.repo.readDocument("line1").orElseThrow().body());
        assertEquals(3L, this.repo.findDevice("line1").orElseThrow().revision());

        /* expectedRevision=null 保持旧语义：不校验，兼容既有调用方 */
        assertEquals(4L, this.repo.updateConstants("line1", BODY_V1 + "  ", "device"));
    }

    /** P2-3：带路径分隔符 / .. / 盘符的设备名不能越界写文件。 */
    @Test
    void exportRejectsPathBearingDeviceNames() throws Exception {
        setUp();
        for (String bad : new String[] {"../evil", "a/b", "a\\b", "C:evil", ".."}) {
            this.repo.createDevice(bad, null, BODY_V1, true, "test");
        }
        Path out = this.workDir.resolve("exported");
        StoreException e = assertThrows(StoreException.class, () -> this.repo.exportTo(out));
        assertTrue(e.getMessage().contains("导出文件名"), e.getMessage());
        assertFalse(Files.exists(this.workDir.resolve("evil.json")), "不得越界写到目录外");
    }

    /** P2-3：导出不再使用固定 .tmp 名；正常导出不留临时垃圾。 */
    @Test
    void exportUsesRandomizedTmpNameAndLeavesNoResidue() throws Exception {
        setUp();
        this.repo.createDevice("rand", null, BODY_V1, true, "test");
        Path out = this.workDir.resolve("exported");
        assertEquals(1, this.repo.exportTo(out));
        assertEquals(1, this.repo.exportTo(out), "重复导出（覆盖）也应成功");
        try (var files = Files.list(out)) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of("rand.json"), names, "只应留下目标文件，不留 .tmp");
        }
    }

    @Test
    void revisionRetentionTrimsToTwoHundred() {
        setUp();
        this.repo.createDevice("line1", "etch", BODY_V1, true, "test");
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
    void keyResolutionAcceptsNameOrId() {
        setUp();
        String id = this.repo.createDevice("新设备", "etch", BODY_V1, true, "test");
        /* key 解析认设备名与设备 id，别的一律查不到 */
        assertTrue(this.repo.findDevice("新设备").isPresent());
        assertTrue(this.repo.findDevice(id).isPresent());
        assertFalse(this.repo.findDevice("没有的").isPresent());
    }

    @Test
    void deleteDeviceHidesRowButKeepsHistoryAndFreesTheName() throws SQLException {
        setUp();
        String id = this.repo.createDevice("line1", "etch", BODY_V1, true, "test");

        assertTrue(this.repo.deleteDevice("line1"), "第一次删除应当删掉一台在用设备");
        /* 清单里消失：列表与按 key 查都查不到（key 解析只认未删除设备） */
        assertTrue(this.repo.listDevices().isEmpty());
        assertFalse(this.repo.findDevice("line1").isPresent());
        /* 正文与版本历史留在库里：删掉之后"它当时是什么配置"仍要查得到 */
        assertEquals(1, countOf("SELECT COUNT(*) FROM config_document WHERE device_id=?", id));
        assertEquals(1, countOf("SELECT COUNT(*) FROM config_revision WHERE device_id=?", id));

        /* 再删一次不是"又删了一台"；同名设备可以重建（唯一索引只约束未删除行） */
        assertFalse(this.repo.deleteDevice("line1"));
        String again = this.repo.createDevice("line1", "etch", BODY_V1, true, "test");
        assertEquals(1, this.repo.listDevices().size());
        assertTrue(this.repo.findDevice("line1").isPresent());
        assertFalse(again.equals(id), "重建得到的是新设备行，不复用旧 id");
    }

    @Test
    void exportRoundTripsBytesExactly() throws Exception {
        setUp();
        String body = "{\"schemaVersion\":6,\"session\":{\"id\":\"round\"}}";
        this.repo.createDevice("round", null, body, true, "test");

        Path out = this.workDir.resolve("exported");
        assertEquals(1, this.repo.exportTo(out));
        assertEquals(body, Files.readString(out.resolve("round.json")));
    }

    @Test
    void listRevisionsComesBackNewestFirst() {
        setUp();
        this.repo.createDevice("r", null, "{\"v\":1}", true, "test");
        this.repo.saveDocument("r", "{\"v\":2}", null, "test", "");
        List<ConfigRepository.RevisionView> revisions = this.repo.listRevisions("r", 10);
        assertEquals(2, revisions.size());
        assertEquals(2, revisions.get(0).revision());
    }

    /** 直接查一行计数：软删之后仓储接口查不到，只能看表本身。 */
    private int countOf(String sql, String deviceId) throws SQLException {
        try (Connection connection = this.db.readConfig();
             var st = connection.prepareStatement(sql)) {
            st.setString(1, deviceId);
            try (var rs = st.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }
}
