package com.shimizukenta.secssim.app.session;

/**
 * 设备控制状态（E5 的简化模型）。
 *
 * <p>协议层原本只有一个 {@code communicatable} 布尔值，界面无法区分
 * "没连上"、"链路已建立但设备离线"和"主机已把设备置为 ONLINE"。</p>
 *
 * <ul>
 *   <li>{@link #OFFLINE}：没有选中的主机，或主机刚发过 S1F15；</li>
 *   <li>{@link #COMMUNICATING}：HSMS selected / S1F13 已建立通信，但设备仍离线；</li>
 *   <li>{@link #ONLINE}：主机 S1F17 已被接受。</li>
 * </ul>
 *
 * <p>这是一个面向操作的简化状态，不是完整的 E5 控制状态机
 * （ATTEMPT ONLINE / ONLINE LOCAL 等细分暂不建模）。</p>
 */
public enum EquipmentControlState {
    /** 离线 / 无选中主机。 */
    OFFLINE,
    /** 链路已建立，设备尚未 ONLINE。 */
    COMMUNICATING,
    /** 主机已请求并获准 ONLINE。 */
    ONLINE
}
