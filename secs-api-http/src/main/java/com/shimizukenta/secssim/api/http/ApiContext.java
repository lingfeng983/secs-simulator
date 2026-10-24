package com.shimizukenta.secssim.api.http;

import java.io.IOException;
import java.util.List;

/**
 * HTTP 层需要的运行时信息。
 *
 * <p>把"我是什么形态、版本多少、有哪些附加字段"抽成接口，
 * 使 HTTP 层不认识运行时的具体实现，运行时不认识 HTTP 的具体细节。
 * </p>
 */
public interface ApiContext {

    /**
     * 运行形态标识。
     *
     * @return 如 {@code integrated}
     */
    String mode();

    /**
     * 版本号。
     *
     * @return 版本
     */
    String version();

    /**
     * 追加到 health JSON 里的字段，形如 {@code ,"hsmsPort":15000}。
     *
     * @return 附加字段，无则为空串
     */
    String extraHealthJson();

    /**
     * 请求优雅停机。
     */
    void requestShutdown();

    /**
     * 当前日志级别。
     *
     * @return 级别名（小写）
     */
    default String logLevel() {
        return com.shimizukenta.secssim.app.log.Log.level().name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 运行时切换日志级别。
     *
     * @param level 级别名（error/info/debug，不分大小写）
     * @return 切换是否成功
     */
    default boolean setLogLevel(String level) {
        try {
            com.shimizukenta.secssim.app.log.Log.setLevel(
                    com.shimizukenta.secssim.app.log.Log.Level.parse(level));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 会话列表的 JSON 数组（由运行时组装，HTTP 层只做转发）。
     *
     * @return JSON 数组文本
     */
    default String sessionsJson() {
        return "[]";
    }

    /**
     * 手动触发一个采集事件。
     *
     * @param sessionId 会话标识
     * @param ceid      CEID
     * @return 结果
     */
    default TriggerOutcome triggerCeid(String sessionId, int ceid) {
        return TriggerOutcome.UNKNOWN_SESSION;
    }

    /**
     * 置位/清除一个报警（S5F1）。
     *
     * @param sessionId 会话标识
     * @param alid      ALID
     * @param set       {@code true} 置位，{@code false} 清除
     * @param text      报警文本，空串用默认文本
     * @param category  类别 0..7
     * @return 结果
     */
    default TriggerOutcome triggerAlarm(String sessionId, int alid, boolean set, String text, int category) {
        return TriggerOutcome.UNKNOWN_SESSION;
    }

    /**
     * 会话的活动报警 JSON；会话不存在时返回 {@code null}。
     *
     * @param sessionId 会话标识
     * @return JSON 数组文本或 {@code null}
     */
    default String alarmsJson(String sessionId) {
        return null;
    }

    /**
     * 会话的终端消息 JSON；会话不存在时返回 {@code null}。
     *
     * @param sessionId 会话标识
     * @return JSON 数组文本或 {@code null}
     */
    default String terminalMessagesJson(String sessionId) {
        return null;
    }

    /**
     * 会话的 SVID / RPTID / CEID 目录 JSON；会话不存在时返回 {@code null}。
     *
     * <p>界面靠它把"2001 到底是哪个事件"讲清楚，不用让人猜。
     * </p>
     *
     * @param sessionId 会话标识
     * @return JSON 文本或 {@code null}
     */
    default String catalogJson(String sessionId) {
        return null;
    }

    /**
     * 事件编排列表。
     *
     * @param sessionId 会话标识
     * @return JSON 文本；会话不存在时返回 {@code null}
     */
    default String eventsJson(String sessionId) {
        return null;
    }

    /**
     * 运行时启用/禁用一个编排计划。
     *
     * @param sessionId 会话标识
     * @param planId    计划标识
     * @param enabled   是否启用
     * @return 结果
     */
    default EventOutcome setEventEnabled(String sessionId, String planId, boolean enabled) {
        return EventOutcome.UNKNOWN_SESSION;
    }

    /**
     * 曲线预览（分桶 min/max/avg）。
     *
     * @param sessionId 会话标识
     * @param fromMs    起始
     * @param toMs      结束
     * @param buckets   桶数
     * @return JSON 文本；会话不存在时返回 {@code null}
     */
    default String previewJson(String sessionId, long fromMs, long toMs, int buckets) {
        return null;
    }

    /**
     * 曲线预览的二进制正文。
     *
     * @param sessionId 会话标识
     * @param fromMs    起始
     * @param toMs      结束
     * @param buckets   桶数
     * @return 二进制正文；会话不存在或不支持时返回 {@code null}
     */
    default byte[] previewBinary(String sessionId, long fromMs, long toMs, int buckets) {
        return null;
    }

    /**
     * 最近报文历史（JSON）。
     *
     * @param limit     最多返回条数
     * @param sessionId 只取这个会话；空串表示全部
     * @param direction {@code in} / {@code out}；空串表示全部
     * @return JSON 文本；报文日志不可用时返回 {@code null}
     */
    default String messagesJson(int limit, String sessionId, String direction) {
        return null;
    }

    /**
     * 最近报文历史（JSON Lines），供导出。
     *
     * @param limit     最多返回条数
     * @param sessionId 只取这个会话；空串表示全部
     * @param direction {@code in} / {@code out}；空串表示全部
     * @return 每行一个 JSON 对象；报文日志不可用时返回 {@code null}
     */
    default String messagesJsonl(int limit, String sessionId, String direction) {
        return null;
    }

    /**
     * 最近审计记录。
     *
     * @param limit 最多返回条数
     * @return JSON 文本；审计不可用时返回 {@code null}
     */
    default String auditJson(int limit) {
        return null;
    }

    /**
     * 手动发送一条 SML 报文。
     *
     * @param sessionId 会话标识
     * @param sml       SML 文本
     * @return 发送结果 JSON；会话不存在时返回 {@code null}
     * @throws IllegalArgumentException SML 语法错误
     * @throws IllegalStateException    会话未打开或没有对端
     * @throws IOException              发送失败
     */
    default String sendSml(String sessionId, String sml) throws IOException {
        return null;
    }

    /**
     * 新建一个会话。
     *
     * @param configName {@code demo} 或配置目录下的文件名
     * @return 结果
     */
    default SessionOutcome createSession(String configName) {
        return createSession(SessionCreateRequest.of(configName));
    }

    /**
     * 新建一个会话，允许用部署期覆盖替换配置里的连接标识。
     *
     * <p>默认实现忽略覆盖、按配置原文建——不支持覆盖的形态（如 CLI 演示）自然退化。</p>
     *
     * @param request 配置名 + 部署期覆盖
     * @return 结果
     */
    default SessionOutcome createSession(SessionCreateRequest request) {
        /* 不带覆盖时委托给旧的 String 重载：已有实现（含测试桩）不用跟着改 */
        if (request.sessionId() == null && request.bindHost() == null
                && request.port() == null && request.deviceId() == null) {
            return createSession(request.config());
        }
        return SessionOutcome.FAILED;
    }

    /**
     * 关闭并移除一个会话。
     *
     * @param sessionId 会话标识
     * @return 结果
     */
    default SessionOutcome closeSession(String sessionId) {
        return SessionOutcome.UNKNOWN_SESSION;
    }

    /**
     * 按配置重新装载一个会话（热重载）。
     *
     * @param sessionId 会话标识
     * @return 结果
     */
    default SessionOutcome reloadSession(String sessionId) {
        return SessionOutcome.UNKNOWN_SESSION;
    }

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
}
