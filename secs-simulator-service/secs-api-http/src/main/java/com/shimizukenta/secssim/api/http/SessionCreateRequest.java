package com.shimizukenta.secssim.api.http;

/**
 * 一次"新建会话"请求：设备配置模板 + 部署期覆盖。
 *
 * <p>多台设备复用同一份设备配置时，只有连接标识需要逐台给；
 * 覆盖只作用于运行中的会话实例，不写回配置文件。</p>
 *
 * @param config    设备配置文件名（{@code demo} 或 work-dir 下的文件名）
 * @param sessionId 会话标识；{@code null} 用配置里的
 * @param bindHost  绑定地址；{@code null} 用配置里的
 * @param port      HSMS 端口；{@code null} 用配置里的
 * @param deviceId  device-id；{@code null} 用配置里的
 */
public record SessionCreateRequest(
        String config,
        String sessionId,
        String bindHost,
        Integer port,
        Integer deviceId) {

    /** 只有配置名、不做覆盖的请求。 */
    public static SessionCreateRequest of(String config) {
        return new SessionCreateRequest(config, null, null, null, null);
    }
}
