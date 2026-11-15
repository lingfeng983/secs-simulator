package com.shimizukenta.secssim.app.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.jsonhub.JsonObjectPair;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.context.ContextTable;
import com.shimizukenta.secssim.core.domain.EquipmentConstantDef;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.value.DataSource;

/**
 * Schema v4 配置的读写。
 *
 * <p>只做两件事：把 JSON 变成领域模型，把领域模型变回 JSON。
 * <b>不做</b>语义校验（重复 ID、段重叠、跨单元引用……）——那是
 * {@code EquipmentCompiler} 的职责。分层的好处是：语法错误立刻报，
 * 语义错误在编译阶段一次性报全，两者不会互相掩盖。
 * </p>
 *
 * <p>各配置段由同包的辅助类解析/写回：{@code session} 见
 * {@link SessionSectionJson}，{@code equipment}/{@code units}/{@code reports}
 * 见 {@link EquipmentSectionJson}，{@code constants} 见 {@link ConstantsSectionJson}，
 * {@code context} 见 {@link ContextSectionJson}，{@code events} 见
 * {@link EventSectionJson}。这里只保留入口与编排。</p>
 */
public final class SessionConfigJson {

    private static final String ROOT = "$";

    private SessionConfigJson() {
    }

    /**
     * 解析 JSON 文本。
     *
     * @param json JSON
     * @return 配置
     * @throws ConfigException 语法或字段错误（带字段路径）
     */
    public static SessionConfig parse(String json) {
        String text = stripBom(json);
        JsonHub root;
        try {
            root = JsonHub.fromJson(text);
        } catch (RuntimeException e) {
            throw new ConfigException(ROOT, "JSON 解析失败: " + e.getMessage());
        }

        int schemaVersion = JsonAccess.integer(root, "schemaVersion", ROOT);
        if (schemaVersion < SessionConfig.MIN_SCHEMA_VERSION || schemaVersion > SessionConfig.SCHEMA_VERSION) {
            throw new ConfigException(ROOT + ".schemaVersion",
                    "不支持的版本: " + schemaVersion + "（本程序支持 " + SessionConfig.MIN_SCHEMA_VERSION
                            + ".." + SessionConfig.SCHEMA_VERSION + "）");
        }

        String sessionPath = ROOT + ".session";
        JsonHub session = JsonAccess.object(root, "session", ROOT);
        SessionSectionJson.SessionBasics basics = SessionSectionJson.parseBasics(session, sessionPath);

        EquipmentSpec equipment = EquipmentSectionJson.parse(root, basics.deviceId());
        EventSchedule schedule = EventSectionJson.parseEvents(root);
        int maxPerSec = EventSectionJson.parseMaxPerSec(root);
        boolean s6f11Wbit = EventSectionJson.parseS6f11Wbit(root);
        boolean s6f1Wbit = EventSectionJson.parseS6f1Wbit(root);
        CycleConfig cycle = CycleConfig.parse(root, ROOT);
        List<ContextTable.Def> context = ContextSectionJson.parse(root);
        Map<Integer, DataSource> svidSources = EquipmentSectionJson.parseSvidSources(root, equipment);
        Map<Integer, DataSource> ecidSources = EquipmentSectionJson.parseEcidSources(root, equipment);

        SessionSpec spec = SessionSectionJson.toSpec(basics, session, sessionPath, equipment);
        return new SessionConfig(spec, schedule, maxPerSec, s6f11Wbit, s6f1Wbit,
                cycle, context, svidSources, ecidSources);
    }

    /**
     * 去掉 UTF-8 BOM。
     *
     * <p>Windows 的记事本与 PowerShell 的 {@code Set-Content -Encoding utf8} 都会写 BOM，
     * 而 JSON 规范不允许 BOM。不处理的话，用户用记事本改一行配置就会看到
     * "JSON 解析失败"，且错误指向第 1 个字符，极难自行定位。
     * </p>
     */
    private static String stripBom(String text) {
        if (text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF') {
            return text.substring(1);
        }
        return text;
    }

    /**
     * 从文件读取。
     *
     * @param file 文件
     * @return 配置
     * @throws IOException 读取失败
     */
    public static SessionConfig read(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * 序列化为 JSON。
     *
     * @param config 配置
     * @return JSON
     */
    public static String toJson(SessionConfig config) {
        JsonHubBuilder b = JsonHub.getBuilder();

        List<JsonObjectPair> pairs = new ArrayList<>();
        pairs.add(b.pair("schemaVersion", SessionConfig.SCHEMA_VERSION));
        pairs.add(b.pair("session", SessionSectionJson.toJson(config.spec(), b)));
        pairs.add(b.pair("equipment", EquipmentSectionJson.toJson(config.spec().equipment(),
                config.svidSources(), config.ecidSources(), b)));
        pairs.add(b.pair("events", EventSectionJson.toJson(config, b)));
        if (config.cycle() != null) {
            pairs.add(b.pair("cycle", cycleJson(config.cycle(), b)));
        }
        if (!config.context().isEmpty()) {
            pairs.add(b.pair("context", ContextSectionJson.toJson(config.context(), b)));
        }

        JsonHub root = b.object(pairs);

        return root.prettyPrint();
    }

    /**
     * 写入文件（UTF-8）。
     *
     * @param file   文件
     * @param config 配置
     * @throws IOException 写入失败
     */
    public static void write(Path file, SessionConfig config) throws IOException {
        Files.writeString(file, toJson(config), StandardCharsets.UTF_8);
    }

    /**
     * 设备常量（ECV）数组。
     *
     * <p>公开给 S2F15 的原文回写复用：两条写盘路径必须用同一种元素形状，
     * 否则"编辑器保存"与"主机写常量"产出的文件会不一致。</p>
     *
     * @param constants 常量（按 id 排序后写出）
     * @param b         JSON builder
     * @return JSON 数组
     */
    public static JsonHub constantsJson(Collection<EquipmentConstantDef> constants, JsonHubBuilder b) {
        return ConstantsSectionJson.toJson(constants, b);
    }

    /**
     * 设备常量数组（带值来源）。
     *
     * @param constants 常量
     * @param sources   ECID → 值定义
     * @param b         构造器
     * @return JSON 数组
     */
    public static JsonHub constantsJson(Collection<EquipmentConstantDef> constants,
                                        Map<Integer, DataSource> sources, JsonHubBuilder b) {
        return ConstantsSectionJson.toJson(constants, sources, b);
    }

    /**
     * 周期段。
     *
     * @param cycle 周期定义
     * @param b     构造器
     * @return JSON
     */
    private static JsonHub cycleJson(CycleConfig cycle, JsonHubBuilder b) {
        List<JsonHub> events = new ArrayList<>();
        for (CycleConfig.Event event : cycle.events()) {
            events.add(b.object(
                    b.pair("atMs", event.atMs()),
                    b.pair("ceid", event.ceid())));
        }
        return b.object(
                b.pair("durationMs", cycle.durationMs()),
                b.pair("jitterMs", cycle.jitterMs()),
                b.pair("count", cycle.count()),
                b.pair("noisePerWafer", cycle.noisePerWafer()),
                b.pair("events", b.array(events)));
    }
}