package com.shimizukenta.secssim.app.config;

import java.util.ArrayList;
import java.util.List;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.jsonhub.JsonObjectPair;
import com.shimizukenta.secssim.app.session.HsmsTimeouts;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.app.session.TraceLimits;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;

/**
 * {@code session} 段的读写：会话标识/网络参数、HSMS 超时、Trace 容量限制。
 */
final class SessionSectionJson {

    private SessionSectionJson() {
    }

    /** session 段的标量字段（会话参数的其余部分在 {@link SessionSpec} 里拼装）。 */
    record SessionBasics(String id, boolean passive, String bindHost, int port, int deviceId, long seed) {
    }

    /**
     * 解析 session 段的标量字段。
     *
     * @param session     session 段
     * @param sessionPath 字段路径
     * @return 标量字段
     */
    static SessionBasics parseBasics(JsonHub session, String sessionPath) {
        String id = JsonAccess.string(session, "id", sessionPath);
        boolean passive = JsonAccess.bool(session, "passive", true, sessionPath);
        String bindHost = JsonAccess.optionalString(session, "bindHost", sessionPath).orElse("0.0.0.0");
        /* 端口必填：设备的 SECS 口以配置（数据库）为准，没有兜底 */
        int port = JsonAccess.optionalInt(session, "port", sessionPath).orElseGet(() -> {
            throw new ConfigException(sessionPath + ".port", "端口必须填写（1..65535）");
        });
        if (port < 1 || port > 65_535) {
            throw new ConfigException(sessionPath + ".port", "端口必须填写（1..65535），实际 " + port);
        }
        /* 设备号缺省 0：与界面「新建设备 / 新建主机」的默认值一致，
           配置文件不写 deviceId 时两侧都按 0 对齐，连得上 */
        int deviceId = JsonAccess.optionalInt(session, "deviceId", sessionPath).orElse(0);
        long seed = session.containsKey("seed") ? JsonAccess.longValue(session, "seed", sessionPath) : 0L;

        return new SessionBasics(id, passive, bindHost, port, deviceId, seed);
    }

    /**
     * 拼装会话参数（含超时与 Trace 容量）。
     *
     * @param basics       session 标量字段
     * @param session      session 段
     * @param sessionPath  字段路径
     * @param equipment    设备定义
     * @return 会话参数
     */
    static SessionSpec toSpec(SessionBasics basics, JsonHub session, String sessionPath, EquipmentSpec equipment) {
        HsmsTimeouts timeouts = parseTimeouts(session, sessionPath);
        TraceLimits traceLimits = parseTraceLimits(session, sessionPath);
        return new SessionSpec(basics.id(), basics.passive(), basics.bindHost(), basics.port(),
                basics.deviceId(), basics.seed(), equipment, timeouts, traceLimits);
    }

    /**
     * 读 session.timeouts 段（可选）：HSMS-SS 超时，秒，必须大于 0。
     *
     * <p>形如 {@code "timeouts": {"t3": 60, "t7": 15}}；没写的键用协议层默认。</p>
     */
    private static HsmsTimeouts parseTimeouts(JsonHub session, String path) {
        if (!session.containsKey("timeouts")) {
            return HsmsTimeouts.DEFAULTS;
        }
        JsonHub timeouts = JsonAccess.object(session, "timeouts", path + ".timeouts");
        Double t3 = optionalPositiveSeconds(timeouts, "t3", path + ".timeouts");
        Double t5 = optionalPositiveSeconds(timeouts, "t5", path + ".timeouts");
        Double t6 = optionalPositiveSeconds(timeouts, "t6", path + ".timeouts");
        Double t7 = optionalPositiveSeconds(timeouts, "t7", path + ".timeouts");
        Double t8 = optionalPositiveSeconds(timeouts, "t8", path + ".timeouts");
        try {
            return new HsmsTimeouts(t3, t5, t6, t7, t8);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(path + ".timeouts", e.getMessage());
        }
    }

    private static Double optionalPositiveSeconds(JsonHub timeouts, String key, String path) {
        return JsonAccess.optionalDouble(timeouts, key, path + "." + key)
                .stream()
                .boxed()
                .findFirst()
                .map(value -> {
                    if (value <= 0) {
                        throw new ConfigException(path + "." + key, "必须大于 0（秒）");
                    }
                    return value;
                })
                .orElse(null);
    }

    /**
     * 读 session.trace 段（可选）：Trace（S2F23）容量限制。
     *
     * <p>形如 {@code "trace": {"maxTraces": 4, "maxSvidCount": 32}}；没写的键用
     * 内置默认（同时 1 条、每条 ≤10 个 SVID，与多数真机一致）。</p>
     */
    private static TraceLimits parseTraceLimits(JsonHub session, String path) {
        if (!session.containsKey("trace")) {
            return TraceLimits.DEFAULTS;
        }
        JsonHub trace = JsonAccess.object(session, "trace", path + ".trace");
        Integer maxTraces = optionalPositiveInt(trace, "maxTraces", path + ".trace");
        Integer maxSvidCount = optionalPositiveInt(trace, "maxSvidCount", path + ".trace");
        try {
            return new TraceLimits(maxTraces, maxSvidCount);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(path + ".trace", e.getMessage());
        }
    }

    private static Integer optionalPositiveInt(JsonHub trace, String key, String path) {
        return JsonAccess.optionalInt(trace, key, path + "." + key)
                .stream()
                .boxed()
                .findFirst()
                .map(value -> {
                    if (value < 1) {
                        throw new ConfigException(path + "." + key, "必须 ≥ 1");
                    }
                    return value;
                })
                .orElse(null);
    }

    /**
     * 写回 session 段。
     *
     * @param spec 会话参数
     * @param b    构造器
     * @return JSON
     */
    static JsonHub toJson(SessionSpec spec, JsonHubBuilder b) {
        List<JsonObjectPair> pairs = new ArrayList<>();
        pairs.add(b.pair("id", spec.id()));
        pairs.add(b.pair("passive", spec.passive()));
        pairs.add(b.pair("bindHost", spec.bindHost()));
        pairs.add(b.pair("port", spec.port()));
        pairs.add(b.pair("deviceId", spec.deviceId()));
        pairs.add(b.pair("seed", spec.seed()));
        addTimeouts(pairs, spec.timeouts(), b);
        addTraceLimits(pairs, spec.traceLimits(), b);
        return b.object(pairs);
    }

    /**
     * 把 HSMS 超时写回 session 段。
     *
     * <p>只写显式给过的键：没写的键在文件里保持"没有"，语义上等于协议层默认。
     * 这段必须参与往返——否则 S2F15 写一次设备常量，配置文件里的超时就会消失。</p>
     */
    private static void addTimeouts(List<JsonObjectPair> pairs, HsmsTimeouts timeouts, JsonHubBuilder b) {
        if (timeouts == null) {
            return;
        }

        List<JsonObjectPair> values = new ArrayList<>();
        if (timeouts.t3() != null) {
            values.add(b.pair("t3", timeouts.t3()));
        }
        if (timeouts.t5() != null) {
            values.add(b.pair("t5", timeouts.t5()));
        }
        if (timeouts.t6() != null) {
            values.add(b.pair("t6", timeouts.t6()));
        }
        if (timeouts.t7() != null) {
            values.add(b.pair("t7", timeouts.t7()));
        }
        if (timeouts.t8() != null) {
            values.add(b.pair("t8", timeouts.t8()));
        }
        if (!values.isEmpty()) {
            pairs.add(b.pair("timeouts", b.object(values)));
        }
    }

    /**
     * 把 Trace 容量限制写回 session 段。
     *
     * <p>只写显式给过的键：没写的键在文件里保持"没有"，语义上等于内置默认。
     * 与 timeouts 一样必须参与往返——否则 S2F15 写一次常量，容量配置就丢了。</p>
     */
    private static void addTraceLimits(List<JsonObjectPair> pairs, TraceLimits traceLimits, JsonHubBuilder b) {
        if (traceLimits == null) {
            return;
        }

        List<JsonObjectPair> values = new ArrayList<>();
        if (traceLimits.maxTraces() != null) {
            values.add(b.pair("maxTraces", traceLimits.maxTraces()));
        }
        if (traceLimits.maxSvidCount() != null) {
            values.add(b.pair("maxSvidCount", traceLimits.maxSvidCount()));
        }
        if (!values.isEmpty()) {
            pairs.add(b.pair("trace", b.object(values)));
        }
    }
}