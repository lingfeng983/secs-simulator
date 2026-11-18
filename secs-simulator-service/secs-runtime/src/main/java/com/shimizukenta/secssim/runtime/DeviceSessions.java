package com.shimizukenta.secssim.runtime;

import java.util.Optional;

import com.shimizukenta.secssim.app.session.SessionManager;
import com.shimizukenta.secssim.app.session.SessionRuntime;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;

/**
 * 设备实体与会话实例之间的映射。
 *
 * <p>B1 之后三者各有独立身份：设备实体是 config.db 的 {@code dev_} ULID，
 * 配置是设备名 + {@code .json}，会话是进程内的 {@code ses_} ULID。会话只记
 * 「我用的是哪份配置」，设备 id 经配置名反查。这里就是那张映射表——
 * 它是<b>两个实体之间的关系</b>，不是同一个 id 的别名归一。</p>
 *
 * <p>运行态（device_link / 台账 / 钉值）一律按设备 id 归属；HTTP 的 v2 设备端点
 * 允许用设备 id 或配置名 stem 寻址，两者都落到同一台设备。</p>
 */
final class DeviceSessions {

    private final SessionManager sessions;
    private final SessionLauncher launcher;
    private final ConfigStore configs;

    DeviceSessions(SessionManager sessions, SessionLauncher launcher, ConfigStore configs) {
        this.sessions = sessions;
        this.launcher = launcher;
        this.configs = configs;
    }

    /**
     * 会话 id / 配置名 / 设备 id → 设备实体 id。
     *
     * <p>识别不了（配置被删、演示会话没有来源）时原样返回，让调用方按原键处理；
     * 不在这里抛异常——运行态写库是辅助动作，不该把请求带崩。</p>
     *
     * @param key 会话 id、配置名（带不带 {@code .json}）或设备 id
     * @return 设备 id；识别不了返回原键
     */
    String deviceIdOf(String key) {
        if (key == null || key.isBlank()) {
            return key;
        }
        Optional<String> direct = this.configs.deviceIdOf(key);
        if (direct.isPresent()) {
            return direct.get();
        }
        return this.launcher.sourceOf(key)
                .flatMap(this.configs::deviceIdOf)
                .orElse(key);
    }

    /**
     * 按设备 id / 配置名找它当前运行的会话。
     *
     * @param deviceId 设备 id 或配置名 stem
     * @return 会话；没在运行返回 {@code null}
     */
    HsmsSessionRuntime sessionOf(String deviceId) {
        String target = this.deviceIdOf(deviceId);
        for (SessionRuntime runtime : this.sessions.list()) {
            if (runtime instanceof HsmsSessionRuntime hsms && target.equals(this.deviceIdOf(hsms.id()))) {
                return hsms;
            }
        }
        return null;
    }

    /**
     * 按设备 id / 配置名找它当前运行的会话 id。
     *
     * @param deviceId 设备 id 或配置名 stem
     * @return 会话 id；没在运行返回 {@code empty}
     */
    Optional<String> sessionIdOf(String deviceId) {
        HsmsSessionRuntime session = this.sessionOf(deviceId);
        return session == null ? Optional.empty() : Optional.of(session.id());
    }
}
