package com.shimizukenta.secssim.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shimizukenta.secssim.api.http.ConfigOutcome;
import com.shimizukenta.secssim.app.config.ConfigException;
import com.shimizukenta.secssim.app.config.SessionConfig;
import com.shimizukenta.secssim.app.config.SessionConfigJson;
import com.shimizukenta.secssim.core.domain.EquipmentConstantDef;
import com.shimizukenta.secssim.core.domain.ValueType;

/**
 * 配置的读、校验与存。
 *
 * <p>这层要证明三件事，一件比一件重要：
 * 名字<b>不能变成路径</b>；编辑器<b>看到的和存回去的逐字节一致</b>；
 * 存不进去的配置<b>一定不会落盘</b>。
 * 最后这条是底线——存一份编译不过的配置，等于给下一次启动埋雷。
 * </p>
 */
class ConfigStoreTest {

    @TempDir
    Path workDir;

    private ConfigStore store;

    @BeforeEach
    void setUp() {
        this.store = new ConfigStore(this.workDir);
    }

    @Test
    void listsOnlyJsonRegularFilesSortedByName() throws Exception {
        write("b.json", config("b"));
        write("a.json", config("a"));
        Files.writeString(this.workDir.resolve("notes.txt"), "不是配置", StandardCharsets.UTF_8);
        Files.createDirectory(this.workDir.resolve("backup.json"));

        assertEquals(List.of("a.json", "b.json"),
                this.store.list().stream().map(ConfigStore.Entry::name).toList(),
                "子目录和非 json 文件都不是配置");
    }

    @Test
    void listJsonCarriesSizeAndModifiedTime() throws Exception {
        write("line1.json", config("line1"));

        String json = this.store.listJson();
        assertTrue(json.contains("\"name\":\"line1.json\""), json);
        assertTrue(json.contains("\"sizeBytes\":"), json);
        assertTrue(json.contains("\"modifiedMs\":"), json);
    }

    /**
     * 正文与修改时间必须来自同一次读取。
     *
     * <p>分两次取会出现「旧正文 + 新 mtime」：编辑器带着这个 mtime 保存时，
     * If-Match 判定一致，反而绕过并发保护覆盖别人的修改。</p>
     */
    @Test
    void readSnapshotReturnsTextAndModifiedTimeTogether() throws Exception {
        write("line1.json", config("line1"));

        com.shimizukenta.secssim.api.http.ApiContext.ConfigSnapshot typed = this.store.readSnapshot("line1.json");
        assertTrue(typed != null, "存在的配置必须有快照");
        assertEquals(config("line1"), typed.text());
        assertEquals(Long.valueOf(Files.getLastModifiedTime(this.workDir.resolve("line1.json")).toMillis()),
                typed.modifiedMs());

        assertNull(this.store.readSnapshot("missing.json"), "不存在时返回 null");
        assertNull(this.store.readSnapshot("../evil.json"), "非法名字不读文件");
    }

    /**
     * 编辑器看到的必须和磁盘上一模一样。
     *
     * <p>走模型往返的话，手写的额外键、空格和缩进都会被悄悄改掉——
     * 用户存一次，配置就"变了个样"，而他并没有要求任何转换。
     * </p>
     */
    @Test
    void readReturnsTheFileByteForByte() throws Exception {
        String original = "{\n  \"schemaVersion\": 5,\n  \"我自己加的键\": {\"保留我\": true},\n"
                + "  \"session\": {\"id\": \"line1\", \"passive\": true, \"bindHost\": \"127.0.0.1\","
                + " \"port\": 0, \"deviceId\": 61, \"seed\": 3},\n"
                + "  \"equipment\": {\"name\": \"L1\", \"idSpace\": {}, \"units\": ["
                + "{\"unitId\": 1, \"name\": \"CH1\", \"injectUnitId\": false, \"svids\": [],"
                + " \"reports\": [], \"ceids\": []}]},\n"
                + "  \"events\": {\"maxPerSec\": 5, \"plans\": []}\n}\n";
        write("line1.json", original);

        assertEquals(original, this.store.read("line1.json"));
    }

    @Test
    void readRefusesPathTraversalAndMissingFiles() {
        assertRefused("../evil.json");
        assertRefused("sub/dir.json");
        assertRefused("..\\evil.json");
        assertRefused("C:evil.json");
        assertRefused("line1.txt");
        assertRefused("");
        assertRefused("   ");
        assertRefused("nope.json");
    }

    @Test
    void validateAcceptsACompilableConfig() {
        assertEquals(List.of(), ConfigStore.validate(config("line1")));
    }

    @Test
    void validateExplainsWhatIsBroken() {
        List<String> broken = ConfigStore.validate("{ 这不是 JSON");
        assertFalse(broken.isEmpty(), "解析失败也要说出原因，不能只说'不合法'");
        assertFalse(broken.get(0).isBlank(), broken.toString());

        /* 设备模型编译不过：两个单元抢同一个 unitId */
        List<String> duplicateUnit = ConfigStore.validate("""
                {"schemaVersion": 5,
                 "session": {"id": "line1", "passive": true, "bindHost": "127.0.0.1",
                             "port": 0, "deviceId": 61, "seed": 3},
                 "equipment": {"name": "L1", "idSpace": {},
                   "units": [{"unitId": 1, "name": "CH1", "injectUnitId": false,
                              "svids": [], "reports": [], "ceids": []},
                             {"unitId": 1, "name": "CH2", "injectUnitId": false,
                              "svids": [], "reports": [], "ceids": []}]},
                 "events": {"maxPerSec": 5, "plans": []}}
                """);
        assertFalse(duplicateUnit.isEmpty(), "重复 unitId 必须被挡住");
    }

    /** 存不进去的配置，一个字节都不许动磁盘。 */
    @Test
    void invalidConfigIsNeverWritten() throws Exception {
        write("line1.json", config("line1"));

        var result = this.store.save("line1.json", "{ 这不是 JSON");

        assertEquals(ConfigOutcome.INVALID, result.outcome());
        assertFalse(result.problems().isEmpty(), "必须带出问题清单，界面才有东西显示");
        assertEquals(config("line1"), Files.readString(this.workDir.resolve("line1.json")),
                "校验不过时原文件必须原封不动");
    }

    @Test
    void validConfigIsWrittenBackExactly() throws Exception {
        write("line1.json", config("line1"));
        String edited = config("line1").replace("\"L1\"", "\"L1-EDITED\"");

        var result = this.store.save("line1.json", edited);

        assertEquals(ConfigOutcome.OK, result.outcome());
        assertEquals(edited, Files.readString(this.workDir.resolve("line1.json")));
    }

    @Test
    void savingLeavesNoTemporaryFilesBehind() throws Exception {
        assertEquals(ConfigOutcome.OK, this.store.save("line1.json", config("line1")).outcome());

        try (var files = Files.list(this.workDir)) {
            assertEquals(List.of("line1.json"),
                    files.map(p -> p.getFileName().toString()).sorted().toList(),
                    "原子写盘的临时文件必须已经清掉");
        }
    }

    /** 新建配置是编辑器的正经活儿：不能要求用户先去资源管理器里手搓一个 json。 */
    @Test
    void saveCanCreateANewConfig() {
        var result = this.store.save("brand-new.json", config("new"));

        assertEquals(ConfigOutcome.OK, result.outcome());
        assertTrue(Files.isRegularFile(this.workDir.resolve("brand-new.json")));
        assertTrue(this.store.list().stream().anyMatch(e -> e.name().equals("brand-new.json")));
    }

    /**
     * 新建出来的骨架必须自己就能编译。
     *
     * <p>否则用户新建完一打开就是红的，编辑器就成了噪声源。
     * </p>
     */
    @Test
    void createWritesACompilableSkeleton() throws Exception {
        var result = this.store.create("new-line.json");

        assertEquals(ConfigOutcome.OK, result.outcome());
        String text = Files.readString(this.workDir.resolve("new-line.json"), StandardCharsets.UTF_8);
        assertTrue(text.contains("\"new-line\""), "会话 id 应取自文件名: " + text);
        assertEquals(List.of(), ConfigStore.validate(text), "骨架必须自己就能编译");
    }

    @Test
    void createRefusesToOverwriteAnExistingConfig() throws Exception {
        write("line1.json", config("line1"));

        var result = this.store.create("line1.json");

        assertEquals(ConfigOutcome.CONFLICT, result.outcome());
        assertEquals(config("line1"), Files.readString(this.workDir.resolve("line1.json")),
                "新建不得悄悄覆盖别人正在用的配置");
    }

    @Test
    void createRejectsBadNames() {
        assertEquals(ConfigOutcome.NOT_FOUND, this.store.create("../evil.json").outcome());
        assertEquals(ConfigOutcome.NOT_FOUND, this.store.create("line1.txt").outcome());
        assertFalse(Files.exists(this.workDir.getParent().resolve("evil.json")));
    }

    @Test
    void saveRefusesToEscapeTheWorkDir() {
        assertEquals(ConfigOutcome.NOT_FOUND, this.store.save("../evil.json", config("x")).outcome());
        assertEquals(ConfigOutcome.NOT_FOUND, this.store.save("sub/dir.json", config("x")).outcome());

        assertFalse(Files.exists(this.workDir.getParent().resolve("evil.json")),
                "路径穿越必须在解析阶段就被挡住");
    }

    /**
     * 中文文件名要能存取。
     *
     * <p>现场的配置几乎都带中文名，UTF-8 出问题的话，
     * 用户看到的会是"明明文件在那儿，编辑器却说没有"。
     * </p>
     */
    @Test
    void chineseFileNamesSurviveRoundTrip() throws Exception {
        assertEquals(ConfigOutcome.OK, this.store.save("模板-温度.json", config("t")).outcome());

        assertTrue(this.store.read("模板-温度.json").contains("\"t\""));
        assertTrue(this.store.list().stream().anyMatch(e -> e.name().equals("模板-温度.json")));
    }

    /** 目录层级不支持：接口给的是文件名，给了目录就等于开放了任意路径。 */
    @Test
    void subdirectoriesAreNotConfigs() {
        assertEquals(ConfigOutcome.NOT_FOUND, this.store.save("腔体/模板.json", config("x")).outcome());
        assertFalse(Files.exists(this.workDir.resolve("腔体")));
    }

    /**
     * 两处校验必须同源。
     *
     * <p>建会话和存配置指向同一个目录，各自写一份规则的话，
     * 迟早会出现"这里能读、那里读不了"这种只在一条路径上复现的问题。
     * </p>
     */
    @Test
    void launcherAndStoreAgreeOnTheSameNames() {
        for (String bad : List.of("../evil.json", "sub/dir.json", "line1.txt", "", "   ")) {
            assertThrows(ConfigException.class,
                    () -> ConfigStore.requireFileName(bad, "$.name"), "应拒绝: '" + bad + "'");
            assertEquals(ConfigOutcome.NOT_FOUND,
                    this.store.save(bad, config("x")).outcome(), "应拒绝: '" + bad + "'");
        }
    }

    /**
     * S2F15 回写只换 constants：其它段、未知键、schemaVersion 都不能动。
     *
     * <p>历史上这里走的是“模型往返”，会悄悄丢掉 session.timeouts 和手写的未知键，
     * 还会把 v4 配置升级成 v5。主机只是写一个温度限值，不该顺手改掉别的配置。</p>
     */
    @Test
    void writeConstantsPreservesEverythingElse() throws Exception {
        String original = """
                {"schemaVersion": 4,
                 "我自己加的键": {"保留我": true},
                 "session": {"id": "line1", "passive": true, "bindHost": "127.0.0.1",
                             "port": 0, "deviceId": 61, "seed": 3,
                             "timeouts": {"t3": 60}},
                 "equipment": {"name": "L1", "idSpace": {},
                   "constants": [{"id": 501, "name": "TEMP_LIMIT", "type": "F4", "value": 1.0, "unit": "C"}],
                   "units": [{ "unitId": 1, "name": "CH1", "injectUnitId": false,
                     "svids": [{ "offset": 1, "slot": "TEMP", "type": "F4", "unit": "C" }],
                     "reports": [], "ceids": [] }]},
                 "events": {"maxPerSec": 5, "plans": []}}
                """;
        write("line1.json", original);

        ConfigStore.writeConstants(this.workDir.resolve("line1.json"), Map.of(
                501, new EquipmentConstantDef(501, "TEMP_LIMIT", ValueType.F4, 5.0, "C"),
                502, new EquipmentConstantDef(502, "BATCH_SIZE", ValueType.U2, 100.0)));

        String saved = Files.readString(this.workDir.resolve("line1.json"), StandardCharsets.UTF_8);

        assertTrue(saved.contains("\"我自己加的键\""), "未知键必须保留: " + saved);
        assertTrue(saved.contains("\"schemaVersion\": 4"), "不应被悄悄升级版本: " + saved);
        assertTrue(saved.contains("\"timeouts\""), "session.timeouts 不能被丢掉: " + saved);
        assertTrue(saved.contains("\"t3\""), saved);

        SessionConfig parsed = SessionConfigJson.parse(saved);
        assertEquals(60.0, parsed.spec().timeouts().t3(), "超时值本身也要保留");
        assertEquals(List.of(501, 502),
                parsed.spec().equipment().constants().stream().map(EquipmentConstantDef::id).toList(),
                "常量应替换旧值并按 id 排序");
        assertEquals(5.0, parsed.spec().equipment().constants().get(0).value(), "501 应被覆盖成新值");
        assertEquals(100.0, parsed.spec().equipment().constants().get(1).value());

        assertEquals(List.of(), ConfigStore.validate(saved), "回写后的配置仍必须可编译");
    }

    @Test
    void writeConstantsRefusesAnUnparsableFile() throws Exception {
        write("broken.json", "{ 这不是 JSON");

        assertThrows(IOException.class,
                () -> ConfigStore.writeConstants(this.workDir.resolve("broken.json"), Map.of()));
        assertEquals("{ 这不是 JSON", Files.readString(this.workDir.resolve("broken.json")),
                "解析失败时原文必须原封不动");
    }

    private void assertRefused(String name) {
        assertThrows(ConfigException.class, () -> this.store.read(name), "应拒绝: '" + name + "'");
    }

    private void write(String name, String content) throws IOException {
        Path file = this.workDir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** 一份最小可编译配置。 */
    private static String config(String id) {
        return """
                {"schemaVersion": 5,
                 "session": {"id": "%s", "passive": true, "bindHost": "127.0.0.1",
                             "port": 0, "deviceId": 61, "seed": 3},
                 "equipment": {"name": "L1", "idSpace": {},
                   "units": [{ "unitId": 1, "name": "CH1", "injectUnitId": false,
                     "svids": [{ "offset": 1, "slot": "TEMP", "type": "F4", "unit": "C" }],
                     "reports": [], "ceids": [] }]},
                 "events": {"maxPerSec": 5, "plans": []}}
                """.formatted(id);
    }
}
