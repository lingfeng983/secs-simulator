package com.shimizukenta.secssim.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.secssim.api.http.ConfigOutcome;
import com.shimizukenta.secssim.api.http.ConfigResult;
import com.shimizukenta.secssim.app.config.ConfigException;
import com.shimizukenta.secssim.app.config.SessionConfigJson;
import com.shimizukenta.secssim.app.log.Log;

/**
 * 设备模板仓库：内置模板 + {@code <work-dir>/templates/*.json} 自定义模板。
 *
 * <p>模板是完整设备配置：新建时复制成 {@code <设备名>.json}，再做会话 id/端口/设备号替换。
 * 自定义模板可增删改查；内置模板只读，避免用户把出厂起点删掉。</p>
 */
public final class TemplateStore {

    private static final String SUFFIX = ".json";

    private final Path dir;

    public TemplateStore(Path workDir) {
        this.dir = workDir.resolve("templates");
    }

    public record Entry(String id, String label, String description, String source, long sizeBytes, long modifiedMs) {
    }

    /** 内置模板 + 自定义模板，按 id 排序。 */
    public List<Entry> list() {
        List<Entry> entries = new ArrayList<>();
        for (DeviceTemplates.Entry builtin : DeviceTemplates.catalog()) {
            entries.add(new Entry(builtin.id(), builtin.label(), builtin.description(), "builtin", 0L, 0L));
        }
        if (Files.isDirectory(this.dir)) {
            try (Stream<Path> stream = Files.list(this.dir)) {
                for (Path file : stream.filter(Files::isRegularFile)
                        .filter((p) -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(SUFFIX))
                        .sorted(Comparator.comparing((p) -> p.getFileName().toString()))
                        .toList()) {
                    String id = baseName(file);
                    if (DeviceTemplates.catalog().stream().anyMatch((entry) -> entry.id().equals(id))) {
                        continue;
                    }
                    String label = id;
                    String description = "自定义模板";
                    try {
                        JsonHub root = JsonHub.fromJson(Files.readString(file, StandardCharsets.UTF_8));
                        JsonHub equipment = root.get("equipment");
                        if (equipment != null && equipment.isObject()) {
                            String name = equipment.get("name").optionalString().orElse("");
                            if (!name.isBlank()) {
                                label = name;
                            }
                        }
                    } catch (RuntimeException | IOException e) {
                        description = "自定义模板（内容无法读取）";
                    }
                    try {
                        entries.add(new Entry(id, label, description, "custom",
                                Files.size(file), Files.getLastModifiedTime(file).toMillis()));
                    } catch (IOException e) {
                        entries.add(new Entry(id, label, description, "custom", 0L, 0L));
                    }
                }
            } catch (IOException e) {
                Log.error("读取模板目录失败: " + this.dir, e);
            }
        }
        entries.sort(Comparator.comparing(Entry::id));
        return entries;
    }

    public String listJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Entry entry : this.list()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"id\":\"").append(escape(entry.id()))
                    .append("\",\"label\":\"").append(escape(entry.label()))
                    .append("\",\"description\":\"").append(escape(entry.description()))
                    .append("\",\"source\":\"").append(entry.source())
                    .append("\",\"sizeBytes\":").append(entry.sizeBytes())
                    .append(",\"modifiedMs\":").append(entry.modifiedMs())
                    .append('}');
        }
        return sb.append(']').toString();
    }

    /** 自定义模板原文；内置模板生成一份以 {@code device.json} 为名的可编辑副本。 */
    public Optional<String> text(String id) {
        String safe = sanitize(id);
        if (safe.isEmpty()) {
            return Optional.empty();
        }
        Path file = this.dir.resolve(safe + SUFFIX);
        if (Files.isRegularFile(file)) {
            try {
                return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.error("读取模板失败: " + file, e);
                return Optional.empty();
            }
        }
        return DeviceTemplates.json(safe, "device.json");
    }

    /** 新建/更新自定义模板；内置 id 不允许覆盖。 */
    public ConfigResult save(String id, String text) {
        String safe = sanitize(id);
        if (safe.isEmpty()) {
            return ConfigResult.invalid(List.of("模板 id 不能为空（只允许字母/数字/._-）"));
        }
        if (DeviceTemplates.catalog().stream().anyMatch((entry) -> entry.id().equals(safe))) {
            return ConfigResult.conflict("内置模板 " + safe + " 只读，请另存为新 id");
        }
        List<String> problems = ConfigStore.validate(text);
        if (!problems.isEmpty()) {
            return ConfigResult.invalid(problems);
        }
        try {
            Files.createDirectories(this.dir);
            Path file = this.dir.resolve(safe + SUFFIX);
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return ConfigResult.ok();
        } catch (IOException e) {
            Log.error("保存模板失败: " + safe, e);
            return ConfigResult.failed("写盘失败：" + e.getMessage());
        }
    }

    public ConfigResult delete(String id) {
        String safe = sanitize(id);
        if (DeviceTemplates.catalog().stream().anyMatch((entry) -> entry.id().equals(safe))) {
            return ConfigResult.conflict("内置模板不能删除");
        }
        Path file = this.dir.resolve(safe + SUFFIX);
        try {
            if (!Files.isRegularFile(file)) {
                return ConfigResult.notFound("模板不存在: " + safe);
            }
            Files.delete(file);
            return ConfigResult.ok();
        } catch (IOException e) {
            Log.error("删除模板失败: " + safe, e);
            return ConfigResult.failed("删除失败：" + e.getMessage());
        }
    }

    /** 自定义模板原文（用于新建设备）；不存在返回空。 */
    public Optional<String> customText(String id) {
        String safe = sanitize(id);
        Path file = this.dir.resolve(safe + SUFFIX);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static String sanitize(String id) {
        return id == null ? "" : id.trim().replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private static String baseName(Path file) {
        String name = file.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(SUFFIX)
                ? name.substring(0, name.length() - SUFFIX.length())
                : name;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 给配置文本写入/替换顶层 template 标记。 */
    public static String withTemplate(String text, String templateId) {
        try {
            JsonHub root = JsonHub.fromJson(text);
            if (!root.isObject()) {
                return text;
            }
            var b = JsonHub.getBuilder();
            List<com.shimizukenta.jsonhub.JsonObjectPair> pairs = new ArrayList<>();
            boolean replaced = false;
            for (var key : root.keySet()) {
                String name = key.unescaped();
                if ("template".equals(name)) {
                    pairs.add(b.pair("template", templateId));
                    replaced = true;
                } else {
                    pairs.add(b.pair(name, root.get(name)));
                }
            }
            if (!replaced) {
                pairs.add(b.pair("template", templateId));
            }
            return b.object(pairs).toJson();
        } catch (RuntimeException e) {
            return text;
        }
    }
}
