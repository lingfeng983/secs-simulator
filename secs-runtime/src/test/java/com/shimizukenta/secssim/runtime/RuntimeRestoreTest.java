package com.shimizukenta.secssim.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.ManualEngineClock;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.runtime.session.DemoEquipment;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;
import com.shimizukenta.secssim.store.Db;
import com.shimizukenta.secssim.store.RuntimeStateRepository;

/**
 * 运行态「断开保留、重启恢复」的接线测试（M2 深水区）。
 *
 * <p>钉值 / 计划启用态 / 计划正文覆盖 / 目录编辑这些改动只在内存里生效是不够的：
 * 进程一重启界面就回到配置文件的样子，用户会以为"改的东西丢了"。这里钉住两件事——
 * <b>恢复顺序按依赖来</b>（先目录后钉值，否则运行时新增的 SVID 的钉值会被丢掉），
 * 以及<b>销毁清编辑但不清启停</b>。</p>
 */
class RuntimeRestoreTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    private static final String DEVICE = "dev_restore";

    @Test
    void restoreReplaysEditsInDependencyOrder() throws Exception {
        int svid = this.firstSvid();
        try (Db db = Db.open(this.workDir)) {
            RuntimeStateRepository state = new RuntimeStateRepository(db);
            /* 运行时新增一个 SVID，然后钉住它；再钉一个配置里本来就有的；加一条计划并启用 */
            state.saveCatalogEdit(DEVICE, "svid", 4242, "add",
                    "{\"id\":4242,\"slot\":\"EXTRA\",\"type\":\"F4\",\"owner\":\"GLOBAL\"}");
            state.savePlanOverride(DEVICE, "p1", "add",
                    "{\"id\":\"p1\",\"ceid\":2001,\"trigger\":{\"type\":\"periodic\",\"periodMs\":1000}}");
            state.pinSvid(DEVICE, 4242, 7.5);
            state.pinSvid(DEVICE, svid, 42.0);
            state.savePlanState(DEVICE, "p1", true, null, "");

            HsmsSessionRuntime session = this.newSession("restore-order");
            session.open();

            Bootstrap.restoreRuntimeEdits(session, DEVICE, state);

            String catalog = session.catalogJson();
            assertTrue(catalog.contains("\"svid\":4242"),
                    "运行时新增的 SVID 应恢复（先目录后钉值）: " + catalog);
            assertTrue(session.isSvidPinned(4242),
                    "新增 SVID 上的钉值应生效（目录必须先于钉值恢复）: " + catalog);
            assertTrue(session.isSvidPinned(svid), "配置里已有 SVID 的钉值应生效: " + catalog);

            String events = session.eventsJson();
            assertTrue(events.contains("p1"), "运行时计划正文应恢复: " + events);
            assertTrue(events.contains("\"enabled\":true"),
                    "计划启用态应恢复: " + events);
        }
    }

    @Test
    void destroyedDeviceKeepsPlanSwitchesButDropsEdits() {
        try (Db db = Db.open(this.workDir)) {
            RuntimeStateRepository state = new RuntimeStateRepository(db);
            state.pinSvid(DEVICE, 1001, 1.0);
            state.savePlanOverride(DEVICE, "p1", "add", "{\"id\":\"p1\"}");
            state.saveCatalogEdit(DEVICE, "ceid", 2001, "add", "{\"id\":2001}");
            state.savePlanState(DEVICE, "p1", true, null, "");

            new RuntimePersister(state, id -> id).clearEdits(DEVICE);

            assertTrue(state.pinnedSvids(DEVICE).isEmpty(), "销毁要清钉值");
            assertTrue(state.planOverrides(DEVICE).isEmpty(), "销毁要清计划覆盖");
            assertTrue(state.catalogEdits(DEVICE).isEmpty(), "销毁要清目录编辑");
            assertEquals(1, state.planStates(DEVICE).size(),
                    "启用态是用户的开关选择，销毁也不清（重连后照旧）");
        }
    }

    @Test
    void staleConnectedPhaseIsConvergedOnStartup() {
        try (Db db = Db.open(this.workDir)) {
            RuntimeStateRepository state = new RuntimeStateRepository(db);
            state.upsertDeviceLink("dev-a", "CONNECTED", 3L);
            state.upsertDeviceLink("dev-b", "LISTENING", 3L);
            state.upsertDeviceLink("dev-c", "DISCONNECTED", 3L);

            assertEquals(2, state.resetStaleDevicePhases(), "只改「还连着」的两行");
            assertEquals("DISCONNECTED", state.deviceLink("dev-a").orElseThrow().phase());
            assertEquals("DISCONNECTED", state.deviceLink("dev-b").orElseThrow().phase());
            assertEquals(3L, state.deviceLink("dev-a").orElseThrow().configRevision(),
                    "收敛相位不能顺手改配置版本");
        }
    }

    /* ---------- helpers ---------- */

    private HsmsSessionRuntime newSession(String id) {
        EquipmentSpec demo = DemoEquipment.twoChambers("RESTORE", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);
        SessionSpec spec = new SessionSpec(id, true, "127.0.0.1", 0, 10, 1L, demo);
        return new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));
    }

    /** 两腔演示设备的第一个 SVID（CH1 基址在模型里是确定的，这里按目录读出来）。 */
    private int firstSvid() {
        HsmsSessionRuntime probe = this.newSession("restore-probe");
        String catalog = probe.catalogJson();
        int at = catalog.indexOf("\"svid\":");
        return Integer.parseInt(catalog.substring(at + 7).split("[^0-9]")[0]);
    }
}
