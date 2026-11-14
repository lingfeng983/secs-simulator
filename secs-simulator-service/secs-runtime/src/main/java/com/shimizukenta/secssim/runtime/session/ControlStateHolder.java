package com.shimizukenta.secssim.runtime.session;

import com.shimizukenta.secssim.app.session.EquipmentControlState;

/**
 * 控制状态：设备当前能不能主动上报。
 *
 * <p>严格按 GEM（E30/E5）来，主动上报（S6F11 / S6F1）只在 {@code ONLINE} 允许：</p>
 * <ol>
 *   <li>链路没 SELECTED（{@code communicatable=false}）时不能发——发送必然失败；</li>
 *   <li>链路选上了但设备还没 ONLINE（{@code COMMUNICATING}，即"已连接但离线"）时不能发；</li>
 *   <li>主机用 S1F15 把设备置回 {@code OFFLINE} 后也不能发。</li>
 * </ol>
 *
 * <p>上线有两条路：主机发 S1F17（Request ON-LINE），或操作员在界面上直接把设备
 * 置为在线（{@link #change(EquipmentControlState)}）。两条路都只是把状态推到
 * {@code ONLINE}，之后事件才真正开始发——这正是"启动计划"和"设备在线"两件事
 * 要分开的原因：计划启动了但设备没在线，消息仍然不发。</p>
 *
 * <p>状态搬迁：建链 → {@code COMMUNICATING}（已连接、仍离线）；
 * S1F15/S1F17 → {@code OFFLINE}/{@code ONLINE}；断链 → {@code OFFLINE}。</p>
 */
final class ControlStateHolder {

    /** 当前控制状态。 */
    private volatile EquipmentControlState state = EquipmentControlState.OFFLINE;

    /** 链路是否已 SELECTED。 */
    private volatile boolean communicatable;

    /** 状态迁移时发布通知。 */
    private final java.util.function.Consumer<EquipmentControlState> onChange;

    ControlStateHolder(java.util.function.Consumer<EquipmentControlState> onChange) {
        this.onChange = onChange;
    }

    /** 当前控制状态。 */
    EquipmentControlState state() {
        return this.state;
    }

    /** 链路是否已 SELECTED。 */
    boolean communicatable() {
        return this.communicatable;
    }

    /** 设置链路通信状态。 */
    void communicatable(boolean communicatable) {
        this.communicatable = communicatable;
    }

    /**
     * 迁移控制状态。
     *
     * @param next 新状态
     */
    void change(EquipmentControlState next) {
        if (this.state == next) {
            return;
        }
        this.state = next;
        this.onChange.accept(next);
    }

    /**
     * 是否允许主动发送（S6F11 / S6F1 这类 unsolicited）。
     *
     * <p>判据就是 GEM 的控制状态：链路已 SELECTED <b>且</b> 设备 {@code ONLINE}。
     * 只建链没上线（{@code COMMUNICATING}）时不发——此时设备在主机眼里还是
     * OFF-LINE，发 S6F11 属于违规。</p>
     *
     * @return 允许返回 {@code true}
     */
    boolean canSendUnsolicited() {
        return this.communicatable && this.state == EquipmentControlState.ONLINE;
    }
}
