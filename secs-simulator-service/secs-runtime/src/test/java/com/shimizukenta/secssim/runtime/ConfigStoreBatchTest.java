package com.shimizukenta.secssim.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import com.shimizukenta.secssim.api.http.ConfigOutcome;
import com.shimizukenta.secssim.app.config.SessionConfig;
import com.shimizukenta.secssim.app.config.SessionConfigJson;
import com.shimizukenta.secssim.store.ConfigRepository;
import com.shimizukenta.secssim.store.Db;
import com.shimizukenta.secssim.store.TemplateRepository;

/**
 * 批量新建设备：设备名 = 批次前缀 + 序号，Device ID 恒为 0，名字不许重复。
 *
 * <p>三条约束一条都不能松：名字是清单里的唯一键（同名两台设备，删哪台、
 * 点开哪台都说不清）；Device ID 逐台递增只会让现场对着主机侧配置多改一遍；
 * 重名必须整批拒绝——建到一半才发现撞名，留下半批设备让人自己收拾是最糟的结果。</p>
 */
class ConfigStoreBatchTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    private Db db;
    private ConfigStore store;

    @BeforeEach
    void setUp() {
        this.db = Db.open(this.workDir);
        this.store = new ConfigStore(new ConfigRepository(this.db),
                new TemplateRepository(this.db), this.workDir);
    }

    /* ---------------- 命名规则（纯函数） ---------------- */

    @Test
    void namesArePrefixPlusSequence() {
        assertEquals(List.of("dev-1", "dev-2", "dev-3"),
                DeviceBatchPlan.deviceNames("dev-", 1, 3));
        assertEquals(List.of("cell-7"),
                DeviceBatchPlan.deviceNames("cell-", 7, 1),
                "序号只从起始值起算，不从 1 起");
        assertEquals(List.of("dev-3.json", "dev-4.json"),
                DeviceBatchPlan.configNames("dev-", 3, 2),
                "配置文件名的 stem 必须与设备名一致");
    }

    @Test
    void prefixCannotBecomeAPathOrBreakJson() {
        for (String prefix : List.of("", "   ", "dev 1", "dev/1", "dev\\1", "dev\"1", "dev\t1")) {
            assertTrue(DeviceBatchPlan.problems(prefix, 1, 2, 5100).size() > 0,
                    "前缀「" + prefix + "」必须被拒");
        }
        assertEquals(List.of(), DeviceBatchPlan.problems("dev-", 1, 2, 5100));
        assertEquals(List.of(), DeviceBatchPlan.problems("批次-", 1, 2, 5100),
                "中文前缀不是路径也不是引号，应放行");
    }

    @Test
    void countAndPortRangeAreBounded() {
        assertTrue(DeviceBatchPlan.problems("d-", 1, 0, 5100).size() > 0, "0 台没有意义");
        assertTrue(DeviceBatchPlan.problems("d-", 1, 101, 5100).size() > 0, "上限 100 台");
        assertTrue(DeviceBatchPlan.problems("d-", 1, 2, 0).size() > 0, "端口从 1 起");
        assertTrue(DeviceBatchPlan.problems("d-", 1, 3, 65534).size() > 0, "端口段不能越界");
        assertEquals(List.of(), DeviceBatchPlan.problems("d-", 0, 1, 65535));
    }

    @Test
    void conflictsAreCaseInsensitiveAndComplete() {
        List<String> conflicts = DeviceBatchPlan.conflicts(
                List.of("Dev-1.json", "dev-2.json"), List.of("dev-1.JSON", "other.json"));
        assertEquals(List.of("Dev-1.json"), conflicts, "大小写不同的同名也算撞名");
    }

    /* ---------------- 建出来的配置 ---------------- */

    @Test
    void batchWritesConnectionAndDeviceIdZero() throws Exception {
        var result = this.store.createNamed("dev-1.json", "etch", "dev-1", 5100, "127.0.0.1", true, 0);
        assertEquals(ConfigOutcome.OK, result.outcome(), result.problems().toString());

        String text = this.store.read("dev-1.json");
        SessionConfig config = SessionConfigJson.parse(text);
        assertEquals("dev-1", config.spec().id(), "会话 id 跟文件名走");
        assertEquals("dev-1", config.spec().name(), "设备名 = 前缀 + 序号");
        assertEquals(5100, config.spec().port(), "端口按这一台自己的值写进正文");
        assertEquals("127.0.0.1", config.spec().bindHost());
        assertTrue(config.spec().passive());
        assertEquals(0, config.spec().deviceId(), "批量新建的 device-id 恒为 0");
        assertEquals(List.of(), ConfigStore.validate(text), "批量建出来的配置必须自己能编译");
    }

    @Test
    void deviceIdZeroDoesNotCollideAcrossABatch() {
        for (int i = 1; i <= 3; i++) {
            var result = this.store.createNamed("dev-" + i + ".json", "etch",
                    "dev-" + i, 5100 + i, "0.0.0.0", true, 0);
            assertEquals(ConfigOutcome.OK, result.outcome(),
                    "第 " + i + " 台应建成功: " + result.problems());
        }
        assertEquals(3, this.store.list().size(), "device-id 相同不是冲突：每台一份配置");
    }

    @Test
    void duplicateNameIsRefusedSoNamesStayUnique() {
        assertEquals(ConfigOutcome.OK, this.store
                .createNamed("dev-1.json", "etch", "dev-1", 5100, "0.0.0.0", true, 0).outcome());

        var again = this.store.createNamed("dev-1.json", "etch", "dev-1", 5200, "0.0.0.0", true, 0);
        assertEquals(ConfigOutcome.CONFLICT, again.outcome(), "同名设备不许建第二台");
        assertEquals(1, this.store.list().size(), "冲突不该顺手多建一台");
    }

    @Test
    void duplicateNameIsDetectedBeforeWriting() {
        this.store.createNamed("dev-3.json", "etch", "dev-3", 5100, "0.0.0.0", true, 0);

        List<String> names = DeviceBatchPlan.configNames("dev-", 1, 5);
        List<String> taken = this.store.list().stream().map(ConfigStore.Entry::name).toList();

        assertEquals(List.of("dev-3.json"), DeviceBatchPlan.conflicts(names, taken));
    }
}
