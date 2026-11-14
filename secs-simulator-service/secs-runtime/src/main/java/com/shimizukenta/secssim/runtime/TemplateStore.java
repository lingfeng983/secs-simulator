package com.shimizukenta.secssim.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.shimizukenta.secssim.api.http.ConfigResult;
import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.store.StoreException;
import com.shimizukenta.secssim.store.TemplateRepository;

/**
 * 设备模板门面（3.0 / ADR 0017）：v1 的「work-dir/templates/*.json」语义换成
 * config.db 的 device_template 表（内置只读种子 + 自定义），公开方法签名不变。
 */
public final class TemplateStore {

    /** 模板目录（legacy 归档位置，仅为兼容保留 workDir 引用）。 */
    private final Path dir;
    private final TemplateRepository repository;

    /**
     * 创建模板门面。
     *
     * @param workDir    工作目录
     * @param repository 模板仓储
     */
    public TemplateStore(Path workDir, TemplateRepository repository) {
        this.dir = workDir == null ? null : workDir.resolve("templates");
        this.repository = repository;
    }

    /** 目录内容的 JSON 数组（形状与 v1 一致）。 */
    public String listJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Entry entry : this.list()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"id\":\"").append(entry.id())
                    .append("\",\"label\":\"").append(Json.escape(entry.label()))
                    .append("\",\"description\":\"").append(Json.escape(entry.description()))
                    .append("\",\"source\":\"").append(entry.source())
                    .append("\",\"sizeBytes\":").append(entry.sizeBytes())
                    .append(",\"modifiedMs\":").append(entry.modifiedMs())
                    .append("}");
        }
        return sb.append(']').toString();
    }

    /** 全部模板（内置在前，按 id 排序）。 */
    public List<Entry> list() {
        return this.repository.list().stream()
                .map(view -> new Entry(view.id(), view.label(), view.description(), view.source(),
                        view.body().getBytes(StandardCharsets.UTF_8).length, view.updatedAt()))
                .toList();
    }

    /** 一个模板的元信息。 */
    public record Entry(String id, String label, String description, String source,
                        long sizeBytes, long modifiedMs) {
    }

    /** 模板原文；内置缺失时回退到代码内建的生成器。 */
    public Optional<String> text(String id) {
        String safe = sanitize(id);
        if (safe.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> body = this.repository.body(safe);
        if (body.isPresent()) {
            return body;
        }
        return DeviceTemplates.json(safe, "device.json");
    }

    /** 新建/更新自定义模板；内置 id 不允许覆盖。 */
    public ConfigResult save(String id, String text) {
        String safe = sanitize(id);
        if (safe.isEmpty()) {
            return ConfigResult.invalid(List.of("模板 id 不能为空（只允许字母/数字/._-）"));
        }
        List<String> problems = ConfigStore.validate(text);
        if (!problems.isEmpty()) {
            return ConfigResult.invalid(problems);
        }
        try {
            if (this.repository.find(safe).isPresent()) {
                this.repository.saveCustom(safe, text);
            } else {
                this.repository.createCustom(safe, safe, "自定义模板", text);
            }
            return ConfigResult.ok();
        } catch (StoreException e) {
            if (e.kind() == StoreException.Kind.CONFLICT) {
                return ConfigResult.conflict(e.getMessage());
            }
            Log.error("保存模板失败: " + safe, e);
            return ConfigResult.failed("保存失败：" + e.getMessage());
        }
    }

    /** 删除自定义模板；内置不能删。 */
    public ConfigResult delete(String id) {
        String safe = sanitize(id);
        try {
            this.repository.deleteCustom(safe);
            return ConfigResult.ok();
        } catch (StoreException e) {
            if (e.kind() == StoreException.Kind.CONFLICT) {
                return ConfigResult.conflict(e.getMessage());
            }
            if (e.kind() == StoreException.Kind.NOT_FOUND) {
                return ConfigResult.notFound(e.getMessage());
            }
            Log.error("删除模板失败: " + safe, e);
            return ConfigResult.failed("删除失败：" + e.getMessage());
        }
    }

    /** 自定义模板原文（用于新建设备）；不存在或非自定义返回空。 */
    public Optional<String> customText(String id) {
        String safe = sanitize(id);
        if (safe.isEmpty()) {
            return Optional.empty();
        }
        return this.repository.find(safe)
                .filter(view -> "custom".equals(view.source()))
                .map(TemplateRepository.TemplateView::body);
    }

    /**
     * 模板 id 清洗：只留字母/数字/点/下划线/中划线，压掉首尾连接符。
     *
     * @param raw 原始 id
     * @return 清洗后的 id；全被滤掉时返回空串
     */
    public static String sanitize(String raw) {
        String cleaned = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9._-]", "-");
        while (cleaned.startsWith("-")) {
            cleaned = cleaned.substring(1);
        }
        while (cleaned.endsWith("-")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    /** 给模板正文补顶层 {@code template} 标记（v1 行为保留）。 */
    public static String withTemplate(String text, String templateId) {
        if (templateId == null || templateId.isBlank()) {
            return text;
        }
        try {
            com.shimizukenta.jsonhub.JsonHub root = com.shimizukenta.jsonhub.JsonHub.fromJson(text);
            if (root.isObject() && !root.containsKey("template")) {
                var b = com.shimizukenta.jsonhub.JsonHub.getBuilder();
                var pairs = new java.util.ArrayList<com.shimizukenta.jsonhub.JsonObjectPair>();
                pairs.add(b.pair("template", templateId));
                for (com.shimizukenta.jsonhub.JsonString key : root.keySet()) {
                    pairs.add(b.pair(key.unescaped(), root.get(key.unescaped())));
                }
                return b.object(pairs).prettyPrint();
            }
        } catch (RuntimeException e) {
            /* 无法解析就原样返回，交给统一校验报错 */
        }
        return text;
    }

    /** JSON 转义工具（listJson 用）。 */
    private static final class Json {
        private Json() {
        }

        static String escape(String value) {
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
}
