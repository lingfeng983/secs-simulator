package com.shimizukenta.secssim.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * 主机实体 / 主机模板（第三轮修订 12）与执行台账（异步批量落盘、保留裁剪）。
 */
class HostAndExecutionStoreTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    @Test
    void hostCrudWithSettingRevision() {
        try (Db db = Db.open(this.workDir)) {
            HostRepository hosts = new HostRepository(db);
            String id = hosts.create("产线 A", "127.0.0.1", 5000, 61, "{}", true, "联调");
            assertTrue(id.startsWith("host_"), id);

            assertEquals(1, hosts.list().size());
            assertEquals(1, hosts.find(id).get().settingRevision());

            long v2 = hosts.save(id, "产线 A 改", "192.168.8.30", 7000, 5, "{}", false, "");
            assertEquals(2, v2, "保存设置应使 setting_revision +1");
            assertEquals("192.168.8.30", hosts.find(id).get().targetHost());

            hosts.saveVariableCatalog(id, "{\"svids\":[]}");
            assertEquals("{\"svids\":[]}", hosts.find(id).get().variableCatalogJson());
            /* 变量目录不算设置：版本不涨 */
            assertEquals(2, hosts.find(id).get().settingRevision());

            hosts.delete(id);
            assertTrue(hosts.find(id).isEmpty());
        }
    }

    @Test
    void hostTemplateCrud() {
        try (Db db = Db.open(this.workDir)) {
            HostRepository hosts = new HostRepository(db);
            String tid = hosts.createTemplate("远端 CVD", "192.168.8.30", 7000, 5, "{}", "第三方");
            assertTrue(tid.startsWith("ht_"), tid);
            assertEquals(1, hosts.listTemplates().size());

            hosts.saveTemplate(tid, "远端 CVD 改", "192.168.8.31", 7001, 5, "{}", "");
            assertEquals("192.168.8.31", hosts.listTemplates().get(0).host());

            hosts.deleteTemplate(tid);
            assertTrue(hosts.listTemplates().isEmpty());
        }
    }

    @Test
    void planExecutionFlushesQueueAndQueries() throws Exception {
        try (Db db = Db.open(this.workDir);
             PlanExecutionRepository repo = new PlanExecutionRepository(db)) {
            long now = System.currentTimeMillis();
            for (int i = 0; i < 80; i++) {
                repo.append(new PlanExecutionRepository.Execution(
                        "dev1", "pl-1", 2001, "dev1|pl-1|" + now + "|" + i,
                        i % 10 == 0, now, now + i, i % 20 == 0 ? "FAILED" : "SENT", "[]", "[]", ""));
            }
            /* 批量落库是异步的：等 flush 窗口过去 */
            for (int waited = 0; waited < 30 && repo.query("dev1", null, null, true, 1000).size() < 80; waited++) {
                Thread.sleep(50);
            }
            List<PlanExecutionRepository.ExecutionRow> all = repo.query("dev1", null, null, true, 1000);
            assertEquals(80, all.size(), "幂等键互异，全部应落库");

            /* 默认查询不含试跑：80 条里 i%10==0 的 8 条是 dry_run */
            assertEquals(72, repo.query("dev1", null, null, false, 1000).size());
            assertEquals(4, repo.query("dev1", null, "FAILED", true, 1000).size());
            assertEquals(all.get(0).id(), all.stream().mapToLong(PlanExecutionRepository.ExecutionRow::id)
                    .max().orElse(0), "结果按 id 倒序");

            /* 幂等键重复不入库 */
            repo.append(new PlanExecutionRepository.Execution(
                    "dev1", "pl-1", 2001, "dev1|pl-1|" + now + "|0", false, now, now, "SENT", "[]", "[]", ""));
            Thread.sleep(300);
            assertEquals(80, repo.query("dev1", null, null, true, 1000).size());

            repo.trim();
            assertEquals(80, repo.query("dev1", null, null, true, 1000).size(), "远未到保留上限，不应裁剪");
        }
    }

    /**
     * fire 计数与台账共用一条异步队列：批内按 (deviceId, planId) 聚合后一次 upsert，
     * 发送线程既不 INSERT 台账也不抢写锁。
     */
    @Test
    void fireRecordsAreAggregatedInTheSameBatchAsLedger() throws Exception {
        try (Db db = Db.open(this.workDir);
             PlanExecutionRepository repo = new PlanExecutionRepository(db)) {
            long now = System.currentTimeMillis();
            repo.append(new PlanExecutionRepository.Execution(
                    "devF", "pf", 2001, "devF|pf|" + now, false, now, now, "SENT", "[]", "[]", ""));
            repo.appendFire(new PlanExecutionRepository.FireRecord("devF", "pf", true, ""));
            repo.appendFire(new PlanExecutionRepository.FireRecord("devF", "pf", true, ""));
            repo.appendFire(new PlanExecutionRepository.FireRecord("devF", "pf", false, "对端超时"));

            for (int waited = 0; waited < 30
                    && repo.query("devF", null, null, true, 10).isEmpty(); waited++) {
                Thread.sleep(50);
            }

            assertEquals(1, repo.query("devF", null, null, true, 10).size());
            List<RuntimeStateRepository.PlanStateRow> states =
                    new RuntimeStateRepository(db).planStates("devF");
            assertEquals(1, states.size(), "三条 fire 只产生一行运行态");
            assertEquals(3, states.get(0).fireCount(), "按批聚合累加");
            assertEquals(1, states.get(0).skippedCount(), "失败的一次计入 skipped");
            assertEquals("对端超时", states.get(0).lastError());
            assertTrue(states.get(0).lastFireAt() != null && states.get(0).lastFireAt() > 0);
        }
    }

    @Test
    void linkEventsAndOrphanCleanup() {
        try (Db db = Db.open(this.workDir)) {
            RuntimeStateRepository runtime = new RuntimeStateRepository(db);
            runtime.upsertDeviceLink("dev-keep", "CONNECTED", 1L);
            runtime.upsertDeviceLink("dev-gone", "CONNECTED", 1L);
            runtime.appendLinkEvent("device", "dev-keep", "DISCONNECTED", "CONNECTED", "测试");
            runtime.pinSvid("dev-gone", 1001, 42.0);

            assertEquals(42.0, runtime.pinnedSvids("dev-gone").get(1001L));

            runtime.cleanupOrphans(java.util.List.of("dev-keep"));
            assertTrue(runtime.deviceLink("dev-gone").isEmpty(), "孤儿链路应被清掉");
            assertTrue(runtime.pinnedSvids("dev-gone").isEmpty());
            assertTrue(runtime.deviceLink("dev-keep").isPresent());
            assertEquals(1, runtime.linkEvents("dev-keep", 10).size());

            HostRepository hosts = new HostRepository(db);
            String hid = hosts.create("h", "127.0.0.1", 5000, 0, "{}", false, "");
            runtime.upsertHostLink(hid, "CONNECTING", "127.0.0.1:5000", 1);
            runtime.updateHostPhase(hid, "FAILED", "目标未响应", 3L, nowPlus(5_000));
            RuntimeStateRepository.LinkRow link = runtime.hostLink(hid).get();
            assertEquals("FAILED", link.phase());
            assertEquals("目标未响应", link.lastError());
            assertFalse(hosts.list().isEmpty());
        }
    }

    private static Long nowPlus(long ms) {
        return System.currentTimeMillis() + ms;
    }
}
