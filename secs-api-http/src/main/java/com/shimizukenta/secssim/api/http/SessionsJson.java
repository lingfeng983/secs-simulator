package com.shimizukenta.secssim.api.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.jsonhub.JsonObjectPair;
import com.shimizukenta.secssim.app.session.SessionRuntime;

/**
 * 把会话列表渲染成 JSON。
 *
 * <p>放在 HTTP 层是因为它就是"传输表示"；运行时只负责提供
 * {@code sessionsJson()}，不需要认识 JSON 细节。
 * </p>
 */
public final class SessionsJson {

    private SessionsJson() {
    }

    /**
     * 渲染会话数组。
     *
     * @param sessions 会话
     * @return JSON 数组文本
     */
    public static String render(List<SessionRuntime> sessions) {
        JsonHubBuilder b = JsonHub.getBuilder();
        List<JsonHub> items = new ArrayList<>(sessions.size());

        for (SessionRuntime session : sessions) {
            items.add(b.object(
                    b.pair("id", session.id()),
                    b.pair("state", session.state().name()),
                    b.pair("controlState", session.controlState().name()),
                    b.pair("device", session.deviceName()),
                    b.pair("config", session.configName()),
                    b.pair("units", session.unitCount()),
                    b.pair("lastError", session.lastError() == null ? "" : session.lastError()),
                    b.pair("counters", countersJson(session.counters(), b))));
        }

        return b.array(items).toJson();
    }

    private static JsonHub countersJson(Map<String, Long> counters, JsonHubBuilder b) {
        List<JsonObjectPair> pairs = new ArrayList<>(counters.size());
        for (Map.Entry<String, Long> entry : counters.entrySet()) {
            pairs.add(b.pair(entry.getKey(), entry.getValue().longValue()));
        }
        return b.object(pairs);
    }
}
