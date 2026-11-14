package com.shimizukenta.secssim.runtime;

import java.util.List;

import com.shimizukenta.secssim.api.http.ConfigResult;
import com.shimizukenta.secssim.api.http.ParamTemplateSaved;
import com.shimizukenta.secssim.app.config.ConfigException;
import com.shimizukenta.secssim.app.config.ParamTemplateBody;
import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.store.Ids;
import com.shimizukenta.secssim.store.ParamTemplateRepository;
import com.shimizukenta.secssim.store.StoreException;

/**
 * 参数模板门面：所有设备共享的「参数变化逻辑」库的读写入口。
 *
 * <p>保存前先经 {@link ParamTemplateBody} 归一化并校验（未知引用/环/重复 id
 * 走模板图同一道门），库里不会有起不来的模板；列表直接带模板体，
 * 设备绑定（前端复制进配置）不用二次请求。</p>
 */
public final class ParamTemplateStore {

    private final ParamTemplateRepository repository;

    /**
     * 创建门面。
     *
     * @param repository 参数模板仓储
     */
    public ParamTemplateStore(ParamTemplateRepository repository) {
        this.repository = repository;
    }

    /** 全部模板（内置在前）的 JSON 数组，条目直接带模板体。 */
    public String listJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (ParamTemplateRepository.ParamTemplateView view : this.repository.list()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"id\":\"").append(Json.escape(view.id()))
                    .append("\",\"name\":\"").append(Json.escape(view.name()))
                    .append("\",\"description\":\"").append(Json.escape(view.description()))
                    .append("\",\"source\":\"").append(view.source())
                    .append("\",\"readonly\":").append(view.readonly())
                    .append(",\"body\":").append(view.body())
                    .append(",\"updatedAt\":").append(view.updatedAt())
                    .append("}");
        }
        return sb.append(']').toString();
    }

    /**
     * 新建/更新参数模板。
     *
     * <p>{@code id} 为空 = 新建（服务端生成 id）；非空 = 指定 id 建或改自定义模板
     * （内置只读拒绝）。模板体先归一化为完整形再入库。</p>
     *
     * @param id          模板 id；空表示新建
     * @param name        显示名
     * @param description 说明
     * @param bodyText    模板体 JSON 原文（单曲线简形或完整形）
     * @return 保存结果（带落库后的 id）
     */
    public ParamTemplateSaved save(String id, String name, String description, String bodyText) {
        if (name == null || name.isBlank()) {
            return ParamTemplateSaved.invalid(List.of("模板名不能为空"));
        }
        String normalized;
        try {
            normalized = ParamTemplateBody.normalize(bodyText);
        } catch (ConfigException e) {
            return ParamTemplateSaved.invalid(List.of(e.getMessage()));
        }
        String requestedId = id == null ? "" : id.trim();
        try {
            String finalId;
            if (requestedId.isEmpty()) {
                finalId = Ids.nextParamTemplate();
                this.repository.createCustom(finalId, name.trim(),
                        description == null ? "" : description.trim(), normalized);
            } else if (this.repository.find(requestedId).isPresent()) {
                finalId = requestedId;
                this.repository.saveCustom(requestedId, name.trim(),
                        description == null ? "" : description.trim(), normalized);
            } else {
                finalId = requestedId;
                this.repository.createCustom(requestedId, name.trim(),
                        description == null ? "" : description.trim(), normalized);
            }
            Log.info("保存参数模板: " + finalId + "（" + name.trim() + "）");
            return ParamTemplateSaved.ok(finalId);
        } catch (StoreException e) {
            if (e.kind() == StoreException.Kind.CONFLICT) {
                return new ParamTemplateSaved(ConfigResult.conflict(e.getMessage()), null);
            }
            if (e.kind() == StoreException.Kind.NOT_FOUND) {
                return new ParamTemplateSaved(ConfigResult.notFound(e.getMessage()), null);
            }
            Log.error("保存参数模板失败: " + requestedId, e);
            return new ParamTemplateSaved(ConfigResult.failed("保存失败：" + e.getMessage()), null);
        }
    }

    /** 删除自定义参数模板；内置不能删。 */
    public ConfigResult delete(String id) {
        try {
            this.repository.deleteCustom(id == null ? "" : id.trim());
            Log.info("删除参数模板: " + id);
            return ConfigResult.ok();
        } catch (StoreException e) {
            if (e.kind() == StoreException.Kind.CONFLICT) {
                return ConfigResult.conflict(e.getMessage());
            }
            if (e.kind() == StoreException.Kind.NOT_FOUND) {
                return ConfigResult.notFound(e.getMessage());
            }
            Log.error("删除参数模板失败: " + id, e);
            return ConfigResult.failed("删除失败：" + e.getMessage());
        }
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
