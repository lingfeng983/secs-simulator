package com.shimizukenta.secssim.runtime;

/**
 * 部署期覆盖：从同一份设备配置模板批量开设备时，连接标识逐台不同。
 *
 * <p>配置文件里的 {@code session} 段是"这份设备长什么样"的默认值；
 * 多台设备复用一份配置时，只有 id / 端口 / 绑定地址 / device-id 需要每台单独给。
 * 覆盖只作用于运行中的会话实例，<b>不写回配置文件</b>——
 * 否则第 2 台会把第 1 台的端口改掉。重载会话时同一组覆盖会原样再套一遍。</p>
 *
 * @param sessionId 会话标识；{@code null} 用配置里的
 * @param bindHost  绑定地址；{@code null} 用配置里的
 * @param port      HSMS 端口；{@code null} 用配置里的
 * @param deviceId  device-id；{@code null} 用配置里的
 */
public record SessionOverrides(String sessionId, String bindHost, Integer port, Integer deviceId) {

    /** 不做任何覆盖。 */
    public static final SessionOverrides NONE = new SessionOverrides(null, null, null, null);

    /**
     * 是否全部字段都未指定。
     *
     * @return 全空返回 {@code true}
     */
    public boolean isEmpty() {
        return this.sessionId == null && this.bindHost == null && this.port == null && this.deviceId == null;
    }
}
