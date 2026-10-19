package com.shimizukenta.secssim.app.session;

import java.io.IOException;
import java.util.Map;

/**
 * 会话的可操作句柄。
 *
 * <p>应用层只认这个接口；协议细节（HSMS-SS 主动/被动、SECS-I、分块、重连）
 * 全部留在实现侧。
 * </p>
 */
public interface SessionRuntime {

    /**
     * 会话标识。
     *
     * @return 标识
     */
    String id();

    /**
     * 打开端口。重复打开应抛异常，避免"打开了两次"这种静默错误。
     *
     * @throws IOException 绑定失败
     */
    void open() throws IOException;

    /**
     * 关闭端口（幂等）。
     */
    void close();

    /**
     * 当前状态。
     *
     * @return 状态
     */
    SessionState state();

    /**
     * 最近一次失败原因。
     *
     * @return 原因，无则为 {@code null}
     */
    String lastError();

    /**
     * 设备名（用于 API 展示）。
     *
     * @return 设备名，未知时为空串
     */
    default String deviceName() {
        return "";
    }

    /**
     * 单元数量。
     *
     * @return 数量，未知时为 0
     */
    default int unitCount() {
        return 0;
    }

    /**
     * 当前是否有对端在线（主机已连接并完成 select）。
     *
     * <p>没有对端时"上报事件"是发不出去的，这不是服务器故障而是设备状态，
     * 调用方应据此给出"无主机会话"而不是"内部错误"。
     * </p>
     *
     * @return 有对端返回 {@code true}
     */
    default boolean hasPeer() {
        return false;
    }

    /**
     * 运行计数（已上报事件数、被限流数、已发 S6F1 等）。
     *
     * @return 计数
     */
    default Map<String, Long> counters() {
        return Map.of();
    }

    /**
     * 事件编排列表（含启用状态）。
     *
     * @return JSON 文本
     */
    default String eventsJson() {
        return "{\"events\":[]}";
    }

    /**
     * 运行时启用/禁用一个编排计划。
     *
     * @param planId  计划标识
     * @param enabled 是否启用
     * @return 计划存在返回 {@code true}
     */
    default boolean setEventEnabled(String planId, boolean enabled) {
        return false;
    }

    /**
     * 预览某一时间段的曲线（分桶 min/max/avg）。
     *
     * @param fromMs  起始
     * @param toMs    结束
     * @param buckets 桶数
     * @return JSON 文本；不支持时返回空序列
     */
    default String previewJson(long fromMs, long toMs, int buckets) {
        return "{\"series\":[]}";
    }

    /**
     * 预览的二进制格式；不支持时返回 {@code null}。
     *
     * @param fromMs  起始
     * @param toMs    结束
     * @param buckets 桶数
     * @return 二进制正文，格式见实现侧文档
     */
    default byte[] previewBinary(long fromMs, long toMs, int buckets) {
        return null;
    }

    /**
     * 手动上报一个采集事件。
     *
     * <p>并非所有会话都支持（例如只做被动应答的场景），默认实现明确失败，
     * 而不是静默忽略——静默忽略会让调用方以为成功了。
     * </p>
     *
     * @param ceid CEID
     * @throws IOException 不支持、会话未打开或发送失败
     */
    default void emitCeid(int ceid) throws IOException {
        throw new IOException("session does not support collection events: " + this.id());
    }

    /**
     * 上报/清除一个报警（S5F1）。
     *
     * <p>默认实现明确失败，避免调用方以为报警已经发出。</p>
     *
     * @param alid     ALID
     * @param set      {@code true} 置位，{@code false} 清除
     * @param text     报警文本（ALTX），空串表示用默认文本
     * @param category 报警类别 0..7（写入 ALCD 低 3 位）
     * @throws IOException 不支持、会话未打开或发送失败
     */
    default void emitAlarm(int alid, boolean set, String text, int category) throws IOException {
        throw new IOException("session does not support alarms: " + this.id());
    }

    /**
     * 手动发送一条 SML 报文。
     *
     * <p>默认实现明确失败：不是所有会话实现都支持任意报文发送，
     * 静默忽略会让调用方以为报文已经发出。</p>
     *
     * @param sml SML 文本
     * @return 发送结果 JSON（含是否等待回复、回复内容）
     * @throws IOException 不支持、会话未打开或发送失败
     */
    default String sendSml(String sml) throws IOException {
        throw new IOException("session does not support manual SML messages: " + this.id());
    }

    /**
     * 当前活动报警的 JSON 数组。
     *
     * @return JSON 数组文本
     */
    default String alarmsJson() {
        return "[]";
    }

    /**
     * 最近终端消息的 JSON 数组（旧→新）。
     *
     * @return JSON 数组文本
     */
    default String terminalMessagesJson() {
        return "[]";
    }

    /**
     * SVID / RPTID / CEID 目录的 JSON 文本。
     *
     * <p>主机侧报文里只有数字，"2001 是什么"只有设备知道。目录接口把
     * "数字 → 语义"的映射摊开，界面上就不用让人猜 CEID 了。
     * </p>
     *
     * <p>默认返回三个空数组而不是 {@code null}：没有目录是一种合法状态
     * （例如探针），不该让调用方处理"可能没有"这种情况。
     * </p>
     *
     * @return JSON 文本
     */
    default String catalogJson() {
        return "{\"ceids\":[],\"reports\":[],\"svids\":[]}";
    }
}
