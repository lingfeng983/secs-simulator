package com.shimizukenta.secssim.runtime.session;

import com.shimizukenta.secssim.app.session.EquipmentControlState;

/**
 * 控制状态：设备当前能不能主动上报。
 *
 * <p>E30 的两条约束，本项目历史上只满足第一条：</p>
 * <ol>
 *   <li>链路没 SELECTED（{@code communicatable=false}）时不能发——发送必然失败；</li>
 *   <li>链路选上了但主机还没建立通信（S1F13 之前）或已 S1F15 下线时，
 *       也只能发 S1F13/S9Fx 这类少数报文。<b>主动上报必须等 ONLINE 之后</b>。</li>
 * </ol>
 *
 * <p>修复前 {@code controlState} 只用于界面显示，OFFLINE 之后 S6F11/S6F1 照发不误，
 * 主机侧会看到"设备说它下线了，却还在上报数据"——严格的 GEM 主机按违规处理。</p>
 *
 * <p>状态搬迁：建链 → {@code COMMUNICATING}（此时主机还没发 S1F13）；
 * S1F15/S1F17 → {@code OFFLINE}/{@code ONLINE}；断链 → {@code OFFLINE}。
 * 所以真正能发主动上报的只有 {@code ONLINE} 这一档，其余两档都要拦住。</p>
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
     * @return 允许返回 {@code true}
     */
    boolean canSendUnsolicited() {
        return this.communicatable && this.state == EquipmentControlState.ONLINE;
    }
}
