package com.shimizukenta.secssim.api.http;

import java.util.List;

/**
 * {@link ApiContext} 的配置域：配置文件读写、校验、模板库与草稿预览。
 *
 * <p>从 {@code ApiContext} 按职责拆出，方法集合与默认语义保持不变；
 * 嵌套类型 {@link ApiContext.ConfigSnapshot} 仍留在 {@code ApiContext} 上。</p>
 */
public interface ApiContextConfigOps {

    /**
     * 配置目录里的配置文件名列表 JSON。
     *
     * @return JSON 数组文本
     */
    default String configsJson() {
        return "[]";
    }

    /**
     * 读一份配置的原始文本。
     *
     * <p>给的是<b>原文</b>而不是模型反序列化结果：编辑器必须看到用户写的每一个键。
     * </p>
     *
     * @param name 文件名
     * @return 原文；不存在时返回 {@code null}
     */
    default String configText(String name) {
        return null;
    }

    /**
     * 一次读取配置正文与修改时间。
     *
     * <p>编辑器保存时把 {@code modifiedMs} 作为 If-Match 带回。分两次取会出现
     * 「旧正文 + 新 mtime」：保存时被判为"没有人改过"，于是<b>绕过</b>并发保护、
     * 覆盖掉别人的修改。默认实现是兼容旧调用方的退化版本，运行时实现应一次性读。</p>
     *
     * @param name 文件名
     * @return 快照；文件不存在返回 {@code null}
     */
    default ApiContext.ConfigSnapshot configSnapshot(String name) {
        String text = configText(name);
        if (text == null) {
            return null;
        }
        return new ApiContext.ConfigSnapshot(text, configModifiedMs(name));
    }

    /**
     * 校验一段配置文本（不落盘）。
     *
     * @param text JSON 文本
     * @return 问题清单；空表示合法
     */
    default List<String> validateConfig(String text) {
        return List.of("当前形态不支持配置校验");
    }

    /**
     * 新建一份配置（内容是能直接编译的骨架）。
     *
     * @param name 文件名
     * @return 结果
     */
    default ConfigResult createConfig(String name) {
        return ConfigResult.notFound("当前形态不支持新建配置");
    }

    /**
     * 内置设备模板目录的 JSON 数组。
     *
     * @return JSON 文本（{@code [{id,label,description},…]}）
     */
    default String configTemplatesJson() {
        return "[]";
    }

    /**
     * 按内置设备模板新建一份配置。
     *
     * <p>默认实现忽略模板退回骨架：旧实现只认骨架，语义仍成立。</p>
     *
     * @param name       文件名
     * @param templateId 模板标识；未知模板按非法请求返回问题清单
     * @return 结果
     */
    default ConfigResult createConfig(String name, String templateId) {
        return this.createConfig(name);
    }

    /**
     * 删除一份配置（= 从设备清单里删掉一台设备）。
     *
     * <p>实现方要先停掉这台设备的会话再删：配置没了、端口还在监听，
     * 清单里就查不到是谁占着端口了。</p>
     *
     * @param name 文件名
     * @return 结果
     */
    default ConfigResult deleteConfig(String name) {
        return ConfigResult.notFound("当前形态不支持删除配置");
    }

    /** 全部模板（内置 + 自定义）的 JSON 数组。 */
    default String templatesJson() {
        return this.configTemplatesJson();
    }

    /** 模板原文；不存在返回空。 */
    default java.util.Optional<String> templateText(String id) {
        return java.util.Optional.empty();
    }

    /** 新建/更新自定义模板。 */
    default ConfigResult saveTemplate(String id, String text) {
        return ConfigResult.notFound("当前形态不支持模板管理");
    }

    /** 删除自定义模板。 */
    default ConfigResult deleteTemplate(String id) {
        return ConfigResult.notFound("当前形态不支持模板管理");
    }

    /** 全部参数模板（内置 + 自定义，条目带模板体）的 JSON 数组。 */
    default String paramTemplatesJson() {
        return "[]";
    }

    /**
     * 新建/更新参数模板。
     *
     * <p>{@code id} 为空 = 新建（服务端生成 id）；非空 = 指定 id 建或改
     * 自定义模板（内置只读拒绝）。实现必须先校验模板体再入库。</p>
     *
     * @param id          模板 id；空表示新建
     * @param name        显示名
     * @param description 说明
     * @param body        模板体 JSON 原文
     * @return 保存结果（带落库后的 id）
     */
    default ParamTemplateSaved saveParamTemplate(String id, String name, String description, String body) {
        return new ParamTemplateSaved(ConfigResult.notFound("当前形态不支持参数模板"), null);
    }

    /** 删除自定义参数模板；内置拒绝。 */
    default ConfigResult deleteParamTemplate(String id) {
        return ConfigResult.notFound("当前形态不支持参数模板");
    }

    /**
     * 保存一份配置。
     *
     * <p>实现必须<b>先校验再落盘</b>：存一份编译不过的配置等于给下一次启动埋雷。
     * </p>
     *
     * @param name 文件名
     * @param text JSON 文本
     * @return 结果（带问题清单）
     */
    default ConfigResult saveConfig(String name, String text) {
        return ConfigResult.notFound("当前形态不支持保存配置");
    }

    /**
     * 取一份配置的最后修改时间（epoch 毫秒）。
     *
     * <p>与 {@link #saveConfig(String, String, Long)} 配合做保存时的并发保护；
     * 取不到（不支持或文件不存在）返回 {@code null}。</p>
     *
     * @param name 文件名
     * @return 修改时间；未知时 {@code null}
     */
    default Long configModifiedMs(String name) {
        return null;
    }

    /**
     * 保存一份配置（带并发保护）。
     *
     * <p>{@code expectedModifiedMs} 非空且与当前文件不一致时必须拒绝覆盖，
     * 返回 CONFLICT——否则两个编辑窗口会互相静默覆盖。</p>
     *
     * @param name               文件名
     * @param text               JSON 文本
     * @param expectedModifiedMs 期望的最后修改时间；null 表示不检查
     * @return 结果（带问题清单）
     */
    default ConfigResult saveConfig(String name, String text, Long expectedModifiedMs) {
        return this.saveConfig(name, text);
    }

    /** 按配置<b>草稿文本</b>算曲线预览（不落盘、不需要运行中的会话）。返回 null 表示编译失败。 */
    default String configPreviewJson(String text, long fromMs, long toMs, int buckets) {
        return null;
    }

    /**
     * 草稿预览（schema v6:SVID 过滤 + 模拟事件时间线,效果叠加进曲线）。
     *
     * @param text       配置草稿原文
     * @param fromMs     起始（模拟时钟）
     * @param toMs       结束
     * @param buckets    桶数
     * @param svids      只画这些 SVID;空/null 不过滤
     * @param withEvents 是否模拟事件时间线并叠加效果
     * @return JSON 文本；编译失败返回 {@code null}
     */
    default String configPreviewJson(String text, long fromMs, long toMs, int buckets,
                                     java.util.Set<Integer> svids, boolean withEvents) {
        return configPreviewJson(text, fromMs, toMs, buckets);
    }
}