package com.shimizukenta.secssim.app.session;

/**
 * 主机模拟器连接一台设备会话所需的目标信息。
 *
 * @param bindHost 设备绑定的地址
 * @param port     设备监听的 HSMS 端口
 * @param deviceId 设备的 device-id
 */
public record HsmsConnectionTarget(String bindHost, int port, int deviceId) {
}
