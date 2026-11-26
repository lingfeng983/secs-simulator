package com.shimizukenta.secssim.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import com.shimizukenta.secssim.app.event.EventBus;
import com.shimizukenta.secssim.app.event.NoticeBus;
import com.shimizukenta.secssim.app.session.HsmsConnectionTarget;
import com.shimizukenta.secssim.app.session.SessionManager;
import com.shimizukenta.secssim.api.http.SessionOutcome;
import com.shimizukenta.secssim.store.Db;
import com.shimizukenta.secssim.store.HostRepository;
import com.shimizukenta.secssim.store.RuntimeStateRepository;

/**
 * 主机实例上限专项（UP-03）：先拒再建，超限不能在持久层留下装不回来的孤儿行。
 */
class HostSimRegistryTest {

    /* Db 会把 sqlite-jdbc 本地库释放到 workDir；JVM 持有该 dll，JUnit 自动清理会失败。
       与 DbTest 一样把清理交给操作系统临时目录策略。 */
    @Test
    void refusesBeyondLimitWithoutPersistingOrphan(
            @TempDir(cleanup = CleanupMode.NEVER) java.nio.file.Path workDir) throws Exception {
        try (Db db = Db.open(workDir)) {
            HostRepository hosts = new HostRepository(db);
            HostSimRegistry registry = new HostSimRegistry(
                    new SessionManager(), new EventBus(), hosts,
                    new RuntimeStateRepository(db), new NoticeBus());
            HsmsConnectionTarget target = new HsmsConnectionTarget("127.0.0.1", 50_000, 10);
            try {
                for (int i = 0; i < HostSimRegistry.MAX_HOST_INSTANCES; i++) {
                    HostSimRegistry.CreateResult result =
                            registry.open("h" + i, target, false, false);
                    assertEquals(SessionOutcome.OK, result.outcome(), "第 " + (i + 1) + " 台应创建成功");
                }
                assertEquals(HostSimRegistry.MAX_HOST_INSTANCES, hosts.list().size());

                HostSimRegistry.CreateResult over = registry.open("h-overflow", target, false, false);
                assertEquals(SessionOutcome.FAILED, over.outcome(), "超出上限必须先拒绝");
                assertNull(over.hostId(), "拒绝时不能返回 id");
                assertEquals(HostSimRegistry.MAX_HOST_INSTANCES, hosts.list().size(),
                        "拒绝不能写持久行，否则重启后装不回来");
            } finally {
                registry.close();
            }
        }
    }
}
