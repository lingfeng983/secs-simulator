package com.shimizukenta.secssim.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.jsonhub.JsonObjectPair;
import com.shimizukenta.jsonhub.JsonString;
import com.shimizukenta.secssim.api.http.ConfigResult;
import com.shimizukenta.secssim.app.config.ConfigException;
import com.shimizukenta.secssim.app.config.SessionConfig;
import com.shimizukenta.secssim.app.config.SessionConfigJson;
import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.domain.EquipmentConstantDef;
import com.shimizukenta.secssim.store.ConfigRepository;
import com.shimizukenta.secssim.store.StoreException;
import com.shimizukenta.secssim.store.TemplateRepository;

/**
 * 配置门面（3.0 / ADR 0017）：v1 的「work-dir 文件」语义原样保留，
 * 存储换成 config.db 的 config_document。
 *
 * <p><b>正文仍是权威</b>：编辑器操作的还是原始 JSON 文本，不经过领域模型往返；
 * <b>先校验再入库</b>的判据不变。文件名（{@code line1.json}）继续是 v1 的身份——
 * 门面把它映射成设备的 legacy_id（文件名 stem），正文与版本存
 * {@code config_document}，修改时间用 {@code updated_at}（毫秒）承担 If-Match
 * （设计 §2.5 的 v1 兼容规则）。</p>
 *
 * <p>S2F15 的常量回写走 {@link #writeConstants}：只替换 constants 子树、
 * 升版本但不写快照、不产生配置漂移（{@link ConfigRepository#updateConstants}）。</p>
 */
public final class ConfigStore {

    /** 配置文件名后缀。 */
    private static final String CONFIG_SUFFIX = ".json";

    private final ConfigRepository repository;
    private final TemplateRepository templates;
    private final Path workDir;

    /**
     * 创建配置门面。
     *
     * @param repository 配置仓储（含投影器）
     * @param templates  设备模板仓储
     * @param workDir    工作目录（legacy 文件归档仍在它下面）
     */
    public ConfigStore(ConfigRepository repository, TemplateRepository templates, Path workDir) {
        this.repository = repository;
        this.templates = templates;
        this.workDir = workDir;
    }

    /** 配置目录。 */
    public Path workDir() {
        return this.workDir;
    }

    /**
     * 全部配置（按名称排序）。
     *
     * <p>3.0 里这就是设备列表：v1 的「配置文件名」= legacy_id + {@code .json}。</p>
     */
    public List<Entry> list() {
        List<Entry> entries = new ArrayList<>();
        for (ConfigRepository.DeviceView device : this.repository.listDevices()) {
            String body = this.repository.readDocument(device.legacyId() != null
                            ? device.legacyId() : device.id())
                    .map(ConfigRepository.DocumentView::body)
                    .orElse("");
            String name = (device.legacyId() != null ? device.legacyId() : device.name()) + CONFIG_SUFFIX;
            entries.add(new Entry(name, body.getBytes(StandardCharsets.UTF_8).length,
                    device.updatedAt(), device.templateId() == null ? "" : device.templateId()));
        }
        entries.sort(Comparator.comparing(Entry::name));
        return entries;
    }

    /** 目录内容的 JSON 数组（与 v1 形状一致）。 */
    public String listJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Entry entry : this.list()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"name\":\"").append(escape(entry.name()))
                    .append("\",\"sizeBytes\":").append(entry.sizeBytes())
                    .append(",\"modifiedMs\":").append(entry.modifiedMs())
                    .append(",\"template\":\"").append(escape(entry.template()))
                    .append("\"}");
        }
        return sb.append(']').toString();
    }

    /**
     * 一次读取正文与修改时间（编辑器保存的并发基准）。
     *
     * @param name 文件名
     * @return 快照；设备不存在或名字非法返回 {@code null}
     */
    public com.shimizukenta.secssim.api.http.ApiContext.ConfigSnapshot readSnapshot(String name) {
        String key;
        try {
            key = keyOf(name);
        } catch (ConfigException e) {
            return null;
        }
        return this.repository.readDocument(key)
                .map(doc -> new com.shimizukenta.secssim.api.http.ApiContext.ConfigSnapshot(
                        doc.body(), doc.updatedAt()))
                .orElse(null);
    }

    /** 取一份配置的最后「修改时间」（= 文档 updated_at，epoch 毫秒）。 */
    public Long modifiedMs(String name) {
        String key;
        try {
            key = keyOf(name);
        } catch (ConfigException e) {
            return null;
        }
        return this.repository.readDocument(key)
                .map(ConfigRepository.DocumentView::updatedAt)
                .orElse(null);
    }

    /** 一个配置文件的元信息。 */
    public record Entry(String name, long sizeBytes, long modifiedMs, String template) {
    }

    /** 读原始文本。 */
    public String read(String name) throws ConfigException, IOException {
        String key = keyOf(name);
        return this.repository.readDocument(key)
                .map(ConfigRepository.DocumentView::body)
                .orElseThrow(() -> new ConfigException("$.name", "配置不存在: " + name));
    }

    /**
     * 校验一段配置文本（不落盘）。
     *
     * <p>判据与 {@code SessionLauncher.prepare} 一致：解析、编译设备模型、
     * 模板图建一遍。静态方法保留：TemplateStore 与接口校验共用这一份。</p>
     *
     * @param text JSON 文本
     * @return 问题列表；空表示合法
     */
    public static List<String> validate(String text) {
        SessionConfig config;
        try {
            config = SessionConfigJson.parse(text);
        } catch (ConfigException e) {
            return List.of(e.getMessage());
        } catch (RuntimeException e) {
            return List.of("解析失败：" + e);
        }

        CompilationResult compiled;
        try {
            compiled = config.compile();
        } catch (RuntimeException e) {
            return List.of("编译失败：" + e);
        }
        if (!compiled.ok()) {
            return compiled.errors().stream().map(Object::toString).toList();
        }

        try {
            if (config.hasCustomValues()) {
                /* 模板 + SVID 直配(v6)统一在模板图里过单写者/环/未知引用 */
                config.buildValues(compiled.equipment());
            }
        } catch (RuntimeException e) {
            return List.of("值来源配置错误：" + e);
        }

        return List.of();
    }

    /** 新建一份配置，内容是能直接编译的骨架。 */
    public ConfigResult create(String name) {
        return this.create(name, DeviceTemplates.BARE_ID);
    }

    /**
     * 按内置设备模板新建一份配置（= 新建设备实体）。
     *
     * @param name       文件名
     * @param templateId 模板标识；未知模板按非法请求拒绝
     * @return 结果
     */
    public ConfigResult create(String name, String templateId) {
        String stem;
        try {
            stem = stemOf(name);
        } catch (ConfigException e) {
            return ConfigResult.notFound(e.getMessage());
        }
        if (this.repository.findDevice(stem).isPresent()) {
            return ConfigResult.conflict("已经有同名的配置了: " + name);
        }
        String text = DeviceTemplates.json(templateId, name).orElse(null);
        if (text == null) {
            return ConfigResult.invalid(List.of(
                    "未知模板: " + templateId + "（可用: " + DeviceTemplates.ids() + "）"));
        }
        return this.createFromTemplateText(name, text, templateId);
    }

    /** 用任意模板文本新建配置：先补顶层 {@code template} 标记，再校验、入库。 */
    public ConfigResult createFromText(String name, String text, String templateId) {
        String stem;
        try {
            stem = stemOf(name);
        } catch (ConfigException e) {
            return ConfigResult.notFound(e.getMessage());
        }
        if (this.repository.findDevice(stem).isPresent()) {
            return ConfigResult.conflict("已经有同名的配置了: " + name);
        }
        String marked = TemplateStore.withTemplate(text, templateId);
        List<String> problems = validate(marked);
        if (!problems.isEmpty()) {
            Log.error("配置骨架生成失败: " + name, null);
            return ConfigResult.invalid(problems);
        }
        try {
            this.repository.createDevice(stem, stem, templateId, marked, true, "ui");
            Log.info("已新建配置: " + name);
            return ConfigResult.ok();
        } catch (StoreException e) {
            Log.error("新建配置失败: " + name, e);
            return ConfigResult.failed(e.getMessage());
        }
    }

    /** 用自定义模板新建设备：把 session.id / equipment.name 改成名 stem，保证设备名与文件名一致。 */
    public ConfigResult createFromTemplateText(String name, String text, String templateId) {
        String stem;
        try {
            stem = stemOf(name);
        } catch (ConfigException e) {
            return ConfigResult.notFound(e.getMessage());
        }
        try {
            JsonHub root = JsonHub.fromJson(text);
            if (root.isObject()) {
                JsonHubBuilder b = JsonHub.getBuilder();
                List<JsonObjectPair> pairs = new ArrayList<>();
                for (JsonString key : root.keySet()) {
                    String field = key.unescaped();
                    if ("session".equals(field) && root.get(field).isObject()) {
                        JsonHub session = root.get(field);
                        List<JsonObjectPair> sessionPairs = new ArrayList<>();
                        for (JsonString sessionKey : session.keySet()) {
                            String nameKey = sessionKey.unescaped();
                            sessionPairs.add("id".equals(nameKey)
                                    ? b.pair("id", stem)
                                    : b.pair(nameKey, session.get(nameKey)));
                        }
                        pairs.add(b.pair("session", b.object(sessionPairs)));
                    } else if ("equipment".equals(field) && root.get(field).isObject()) {
                        JsonHub equipment = root.get(field);
                        List<JsonObjectPair> equipmentPairs = new ArrayList<>();
                        for (JsonString equipmentKey : equipment.keySet()) {
                            String nameKey = equipmentKey.unescaped();
                            equipmentPairs.add("name".equals(nameKey)
                                    ? b.pair("name", stem)
                                    : b.pair(nameKey, equipment.get(nameKey)));
                        }
                        pairs.add(b.pair("equipment", b.object(equipmentPairs)));
                    } else {
                        pairs.add(b.pair(field, root.get(field)));
                    }
                }
                text = b.object(pairs).toJson();
            }
        } catch (RuntimeException e) {
            /* 原样交给校验报错 */
        }
        return this.createFromText(name, text, templateId);
    }

    /** 保存配置：先校验，通过后入库（无并发检查）。 */
    public ConfigResult save(String name, String text) {
        return this.save(name, text, null);
    }

    /**
     * 保存配置（带并发保护）。
     *
     * <p>{@code expectedModifiedMs} 非空时（If-Match 带回的修改时间），与文档的
     * {@code updated_at} 比对——不等说明别的窗口/进程改过，拒绝覆盖并回 CONFLICT。</p>
     *
     * @param name               文件名
     * @param text               JSON 文本
     * @param expectedModifiedMs 期望的最后修改时间；null 表示不检查
     * @return 校验/入库结果，问题清单随结果带回
     */
    public ConfigResult save(String name, String text, Long expectedModifiedMs) {
        String key;
        try {
            key = keyOf(name);
        } catch (ConfigException e) {
            return ConfigResult.notFound(e.getMessage());
        }

        List<String> problems = validate(text);
        if (!problems.isEmpty()) {
            /* 存一份编译不过的配置，等于给下一次启动埋雷 */
            return ConfigResult.invalid(problems);
        }

        try {
            if (this.repository.findDevice(key).isEmpty()) {
                /* v1 语义保留：save 可以从零建一份配置（模板编辑器场景） */
                this.repository.createDevice(key, key, null, text, true, "ui");
            } else {
                this.repository.saveDocument(key, text, null, expectedModifiedMs, "ui", "");
            }
            Log.info("配置已保存: " + name);
            return ConfigResult.ok();
        } catch (StoreException e) {
            if (e.kind() == StoreException.Kind.CONFLICT) {
                return ConfigResult.conflict(e.getMessage());
            }
            Log.error("配置保存失败: " + name, e);
            return ConfigResult.failed(e.getMessage());
        }
    }

    /**
     * 把设备常量写回配置正文（S2F15 用）。
     *
     * <p>做法是「读正文 → 只替换 equipment.constants 节点 → 重新序列化 →
     * {@link ConfigRepository#updateConstants}」：其余段、未知键、schemaVersion
     * 全部原样保留。版本照常 +1，但不写快照、运行版本被追平——现场高频写常量
     * 不该产生永久「配置待生效」，也不该撑爆版本历史（设计 §2.3.2）。</p>
     *
     * @param name      配置名（文件名）
     * @param constants ECID → 常量（调用方传入的是快照）
     * @throws IOException 读不了、解析不了或写不了
     */
    public void writeConstants(String name, Map<Integer, EquipmentConstantDef> constants) throws IOException {
        String key;
        try {
            key = keyOf(name);
        } catch (ConfigException e) {
            throw new IOException(e.getMessage(), e);
        }
        String text = this.repository.readDocument(key)
                .map(ConfigRepository.DocumentView::body)
                .orElseThrow(() -> new IOException("读不了配置 " + name + ": 设备不存在"));

        JsonHub root;
        try {
            root = JsonHub.fromJson(text);
        } catch (RuntimeException e) {
            /* 解析失败就不覆盖：宁可写失败，也不要把原文弄丢 */
            throw new IOException("配置无法解析，拒绝覆盖 " + name + ": " + e.getMessage(), e);
        }
        if (!root.isObject() || !root.containsKey("equipment") || !root.get("equipment").isObject()) {
            throw new IOException("配置没有 equipment 段，拒绝覆盖 " + name);
        }

        JsonHubBuilder b = JsonHub.getBuilder();
        JsonHub equipment = root.get("equipment");
        List<JsonObjectPair> equipmentPairs = new ArrayList<>();
        boolean replaced = false;

        for (JsonString key2 : equipment.keySet()) {
            String field = key2.unescaped();
            if ("constants".equals(field)) {
                equipmentPairs.add(b.pair(field, SessionConfigJson.constantsJson(constants.values(), b)));
                replaced = true;
            } else {
                equipmentPairs.add(b.pair(field, equipment.get(field)));
            }
        }
        if (!replaced) {
            equipmentPairs.add(b.pair("constants", SessionConfigJson.constantsJson(constants.values(), b)));
        }

        JsonHub newEquipment = b.object(equipmentPairs);
        List<JsonObjectPair> rootPairs = new ArrayList<>();
        for (JsonString key2 : root.keySet()) {
            String field = key2.unescaped();
            rootPairs.add(b.pair(field, "equipment".equals(field) ? newEquipment : root.get(field)));
        }

        try {
            this.repository.updateConstants(key, b.object(rootPairs).prettyPrint(), "device");
        } catch (StoreException e) {
            throw new IOException("常量落库失败: " + e.getMessage(), e);
        }
    }

    /**
     * 版本历史（新→旧；3.0 的 config_revision 快照）。
     *
     * @param name  文件名
     * @param limit 条数上限
     * @return JSON 数组；设备不存在返回 {@code null}
     */
    public String revisionsJson(String name, int limit) {
        String key;
        try {
            key = keyOf(name);
        } catch (ConfigException e) {
            return null;
        }
        if (this.repository.findDevice(key).isEmpty()) {
            return null;
        }
        com.shimizukenta.jsonhub.JsonHubBuilder b = com.shimizukenta.jsonhub.JsonHub.getBuilder();
        List<com.shimizukenta.jsonhub.JsonHub> items = new ArrayList<>();
        for (ConfigRepository.RevisionView revision : this.repository.listRevisions(key, Math.max(1, limit))) {
            items.add(b.object(
                    b.pair("revision", revision.revision()),
                    b.pair("sha256", revision.sha256()),
                    b.pair("createdAt", revision.createdAt()),
                    b.pair("createdBy", revision.createdBy()),
                    b.pair("note", revision.note())));
        }
        return b.object(b.pair("revisions", b.array(items))).toJson();
    }

    /**
     * 校验配置文件名，只允许文件名（静态保留：SessionLauncher 共用这一份判据）。
     *
     * @param name  请求给的名字
     * @param field 出错时要指出的字段路径
     * @return 去掉首尾空白后的文件名
     * @throws ConfigException 名字为空、后缀不对或带路径
     */
    static String requireFileName(String name, String field) throws ConfigException {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new ConfigException(field, "不能为空");
        }
        if (!trimmed.endsWith(CONFIG_SUFFIX)) {
            throw new ConfigException(field, "必须是 " + CONFIG_SUFFIX + " 文件");
        }
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..") || trimmed.contains(":")) {
            throw new ConfigException(field, "只能是 --work-dir 下的文件名，不能带路径");
        }

        try {
            Path.of(trimmed);
        } catch (java.nio.file.InvalidPathException e) {
            throw new ConfigException(field, "不是合法的文件名");
        }
        return trimmed;
    }

    /** 校验名字并定位设备 key（stem）；设备不存在抛 ConfigException。 */
    String resolve(String name) throws ConfigException {
        String key = keyOf(name);
        if (this.repository.findDevice(key).isEmpty()) {
            throw new ConfigException("$.name", "配置不存在: " + name);
        }
        return key;
    }

    /** v1 文件名 → 设备 key（去掉 .json 的 stem）。 */
    private static String keyOf(String name) throws ConfigException {
        String trimmed = requireFileName(name, "$.name");
        return trimmed.substring(0, trimmed.length() - CONFIG_SUFFIX.length());
    }

    private static String stemOf(String name) throws ConfigException {
        String trimmed = requireFileName(name, "$.name");
        return trimmed.substring(0, trimmed.length() - CONFIG_SUFFIX.length());
    }

    /** JSON 字符串转义；只处理会破坏 JSON 结构的字符，中文原样保留。 */
    private static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\r' -> sb.append("\\r");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
