package com.shimizukenta.secssim.api.http;

/**
 * API 路径常量。
 *
 * <p>原先这些常量散在 {@code HttpApiServer} 里，拆成多个 Handler 后
 * 各处都要引用，集中到这里才不用跨类互相引用。</p>
 */
final class ApiPaths {

    /** 健康检查。 */
    static final String HEALTH = "/api/v1/health";

    /** 关机（POST，需管理员）。 */
    static final String SHUTDOWN = "/api/v1/shutdown";

    /** 一次性 SSE 票据。 */
    static final String TICKET = "/api/v1/ticket";

    /** SSE 事件流。 */
    static final String EVENTS = "/api/v1/events";

    /** 会话集合。 */
    static final String SESSIONS = "/api/v1/sessions";

    /** 批量建会话。 */
    static final String SESSIONS_BULK = "/api/v1/sessions/bulk";

    /** 单个会话前缀。 */
    static final String SESSION_PREFIX = "/api/v1/sessions/";

    /** 工作台总览。 */
    static final String OVERVIEW = "/api/v1/overview";

    /** 报文历史。 */
    static final String MESSAGES = "/api/v1/messages";

    /** 审计历史。 */
    static final String AUDIT = "/api/v1/audit";

    /** 日志级别。 */
    static final String LOG_LEVEL = "/api/v1/log-level";

    /** 配置目录。 */
    static final String CONFIGS = "/api/v1/configs";

    /** 单份配置前缀。 */
    static final String CONFIG_PREFIX = "/api/v1/configs/";

    /** 内置设备模板目录。 */
    static final String CONFIG_TEMPLATES = "/api/v1/config-templates";

    /** 模板管理（内置 + 自定义）。 */
    static final String TEMPLATES = "/api/v1/templates";

    /** 单个模板前缀。 */
    static final String TEMPLATE_PREFIX = "/api/v1/templates/";

    /** 配置草稿曲线预览。 */
    static final String CONFIG_PREVIEW = "/api/v1/config-preview";

    /** 曲线预览。 */
    static final String PREVIEW = "/api/v1/preview";

    /** 二进制曲线预览。 */
    static final String PREVIEW_BINARY = "/api/v1/preview.bin";

    /** 主机实例集合。 */
    static final String HOST_SIMS = "/api/v1/host-sims";

    /** 单个主机实例前缀。 */
    static final String HOST_SIM_PREFIX = "/api/v1/host-sims/";

    /** v2 主机集合。 */
    static final String HOSTS_V2 = "/api/v2/hosts";

    /** v2 单主机前缀。 */
    static final String HOST_V2_PREFIX = "/api/v2/hosts/";

    /** v2 主机模板集合。 */
    static final String HOST_TEMPLATES_V2 = "/api/v2/host-templates";

    /** v2 设备域统一前缀（配置版本历史 + 运行态台账共用一个 HttpServer 上下文）。 */
    static final String DEVICE_V2_PREFIX = "/api/v2/devices/";

    /** v2 设备配置版本历史前缀（与 {@link #DEVICE_V2_PREFIX} 同值，保留原名以减小改动面）。 */
    static final String DEVICE_REVISIONS_V2 = DEVICE_V2_PREFIX;

    /** v2 单个主机模板前缀。 */
    static final String HOST_TEMPLATE_V2_PREFIX = "/api/v2/host-templates/";

    /** 只校验不落盘的子路径。 */
    static final String VALIDATE_SUFFIX = "validate";

    private ApiPaths() {
    }
}
