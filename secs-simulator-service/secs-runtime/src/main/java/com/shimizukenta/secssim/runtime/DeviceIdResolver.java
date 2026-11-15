package com.shimizukenta.secssim.runtime;

import com.shimizukenta.secssim.app.session.SessionManager;
import com.shimizukenta.secssim.app.session.SessionRuntime;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;
import com.shimizukenta.secssim.store.ConfigRepository;

/**
 * v2 设备 id 归一与「按设备找会话」的查询。
 *
 * <p>v1 会话 id 是 legacy stem，v2 设备 id 是 config.db 的 ULID；
 * 两者在台账、链路与运行时编辑里必须归一到同一个键。</p>
 */
final class DeviceIdResolver {

    private final SessionManager sessions;
    private final ConfigRepository configRepository;

    DeviceIdResolver(SessionManager sessions, ConfigRepository configRepository) {
        this.sessions = sessions;
        this.configRepository = configRepository;
    }

    /** v2 设备 id 与 v1 legacy stem 都归一到 config.db 的 ULID（台账按它归属）。 */
    String canonical(String key) {
        if (key == null || key.isBlank()) {
            return key;
        }
        try {
            return this.configRepository.findDevice(key)
                    .map(ConfigRepository.DeviceView::id).orElse(key);
        } catch (RuntimeException e) {
            /* 配置库临时不可读时按原 key 查，不让台账查询整条挂掉 */
            return key;
        }
    }

    /** 按归一后的设备 id 找会话：v1 会话 id 是 legacy stem，v2 设备 id 是 ULID。 */
    HsmsSessionRuntime sessionFor(String deviceId) {
        String canonical = this.canonical(deviceId);
        for (SessionRuntime runtime : this.sessions.list()) {
            if (runtime instanceof HsmsSessionRuntime hsms
                    && canonical.equals(this.canonical(hsms.id()))) {
                return hsms;
            }
        }
        return null;
    }
}