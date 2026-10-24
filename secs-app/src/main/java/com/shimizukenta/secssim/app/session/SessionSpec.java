package com.shimizukenta.secssim.app.session;

import java.util.Objects;

import com.shimizukenta.secssim.core.domain.EquipmentSpec;

/**
 * 一个会话的启动参数。
 *
 * @param id        会话标识（进程内唯一）
 * @param passive   {@code true} 为被动（监听），{@code false} 为主动（连接主机）
 * @param bindHost  监听/连接地址
 * @param port      端口，0 表示由系统分配
 * @param deviceId  SECS device-id
 * @param seed      数据种子
 * @param equipment 设备配置
 * @param timeouts  HSMS 超时；null 用协议层默认
 */
public record SessionSpec(String id, boolean passive, String bindHost, int port, int deviceId,
                          long seed, EquipmentSpec equipment, HsmsTimeouts timeouts) {

    /**
     * 便捷构造：超时用协议层默认值。
     */
    public SessionSpec(String id, boolean passive, String bindHost, int port, int deviceId,
                       long seed, EquipmentSpec equipment) {
        this(id, passive, bindHost, port, deviceId, seed, equipment, HsmsTimeouts.DEFAULTS);
    }

    /**
     * 校验并创建。
     *
     * @param id        会话标识
     * @param passive   是否被动
     * @param bindHost  地址
     * @param port      端口
     * @param deviceId  device-id
     * @param seed      种子
     * @param equipment 设备配置
     */
    public SessionSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("session id must not be blank");
        }
        Objects.requireNonNull(bindHost, "bindHost");
        Objects.requireNonNull(equipment, "equipment");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (deviceId < 0 || deviceId > 32767) {
            throw new IllegalArgumentException("deviceId out of range: " + deviceId);
        }
        timeouts = timeouts == null ? HsmsTimeouts.DEFAULTS : timeouts;
    }

    /**
     * 设备名（用于日志与 API）。
     *
     * @return 设备名
     */
    public String name() {
        return this.equipment.name();
    }
}
