package com.shimizukenta.secssim.runtime.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.app.session.EquipmentControlState;

/**
 * 主动上报的放行条件（严格 GEM）。
 *
 * <p>S6F11 / S6F1 只在设备 {@code ONLINE} 时允许：只建链（{@code COMMUNICATING}，
 * 即"已连接但离线"）不算在线，此时上报属于违规。上线有两条路——主机发 S1F17，
 * 或操作员手动置为在线——两者最终都落到 {@code ONLINE} 这一档。</p>
 */
class ControlStateHolderTest {

    @Test
    void requiresOnlineForUnsolicitedMessages() {
        ControlStateHolder holder = new ControlStateHolder(state -> { });

        assertFalse(holder.canSendUnsolicited(), "没建链时不能发");

        holder.communicatable(true);
        assertFalse(holder.canSendUnsolicited(), "链路已选中但还没通信，仍不能发");

        holder.change(EquipmentControlState.COMMUNICATING);
        assertFalse(holder.canSendUnsolicited(), "已连接但离线（COMMUNICATING）不能发 S6F11");

        holder.change(EquipmentControlState.ONLINE);
        assertTrue(holder.canSendUnsolicited(), "ONLINE 才允许主动上报");

        holder.change(EquipmentControlState.OFFLINE);
        assertFalse(holder.canSendUnsolicited(), "S1F15 之后必须停发");

        holder.change(EquipmentControlState.ONLINE);
        holder.communicatable(false);
        assertFalse(holder.canSendUnsolicited(), "链路断开后必须停发");
    }

    @Test
    void onlineCanBeDrivenByOperatorAsWellAsByHost() {
        ControlStateHolder holder = new ControlStateHolder(state -> { });
        holder.communicatable(true);
        holder.change(EquipmentControlState.COMMUNICATING);

        /* 手动上线等价于主机发 S1F17：状态到 ONLINE，之后事件才真正发出去 */
        holder.change(EquipmentControlState.ONLINE);
        assertTrue(holder.canSendUnsolicited());

        /* 手动下线等价于 S1F15 */
        holder.change(EquipmentControlState.OFFLINE);
        assertFalse(holder.canSendUnsolicited());
    }
}
