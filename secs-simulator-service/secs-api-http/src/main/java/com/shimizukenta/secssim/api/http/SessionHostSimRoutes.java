package com.shimizukenta.secssim.api.http;

import java.io.IOException;

import com.sun.net.httpserver.HttpExchange;

/**
 * 会话域下的主机模拟路由：采集器状态查询、打开主机连接、以主机角色发报文、
 * 采集器配置与启停。
 *
 * <p>从 {@link SessionHandler} 抽出的独立路由段：这些分支只关心
 * {@code /sessions/{id}/host-sim/...}，与同一处理器上的会话命令/运行时编辑无关。</p>
 */
final class SessionHostSimRoutes extends HttpSupport {

    SessionHostSimRoutes(ApiContext context, AccessControl access, AuditSink audit) {
        super(context, access, audit);
    }

    /**
     * GET /api/v1/sessions/{id}/host-sim/collector 与 GET /api/v1/sessions/{id}/host-sim。
     *
     * @return {@code true} 表示路径属于主机模拟域且已处理（响应已写出）
     */
    boolean handleQuery(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/host-sim/collector")) {
            /* v6:采集器状态与数据 */
            String id = path.substring(ApiPaths.SESSION_PREFIX.length(),
                    path.length() - "/host-sim/collector".length());
            if (id.isBlank()) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-session-id\"}");
                return true;
            }
            String json = this.context.hostSimCollectorJson(id);
            if (json == null) {
                respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
                return true;
            }
            respond(ex, 200, json);
            return true;
        }
        if (path.endsWith("/host-sim")) {
            String id = path.substring(ApiPaths.SESSION_PREFIX.length(), path.length() - "/host-sim".length());
            if (id.isBlank()) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-session-id\"}");
                return true;
            }
            String json = this.context.hostSimJson(id);
            if (json == null) {
                respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
                return true;
            }
            respond(ex, 200, json);
            return true;
        }
        return false;
    }

    /**
     * 主机模拟器命令：打开连接 / 以主机角色发报文 / 采集器配置与启停。
     *
     * <p>鉴权在入口统一做：任何 host-sim 子路径都先要求 {@link ApiRole#OPERATOR}，
     * 拒绝时响应已写出，调用方必须立即返回。</p>
     *
     * @param ex    交换
     * @param parts 路径段(已去掉 /api/v1/sessions 前缀)
     * @return {@code true} 表示已处理（或已写出拒绝响应），调用方必须立即返回；
     *         {@code false} 表示子路径不属于主机模拟域，调用方继续匹配其他分支
     */
    boolean handleCommand(HttpExchange ex, String[] parts) throws IOException {
        if (!requireRole(ex, ApiRole.OPERATOR)) {
            return true;
        }
        if (parts.length == 2) {
            /* v6:请求体可选携带远程目标 {"host":..,"port":..,"deviceId":..};空体连本机 */
            String targetBody = readBody(ex, MAX_CONFIG_BYTES);
            if (targetBody == null) {
                /* 超限被 readBody 吞成 null：以前这里落到"空体"，于是一个超大的
                   远程目标请求被静默当成"连本机设备"，连到了完全不同的地方 */
                respond(ex, 400, "{\"ok\":false,\"error\":\"body-too-large\"}");
                return true;
            }
            if (targetBody.isBlank()) {
                targetBody = null;
            }
            /* 目标字段在入口校验：非法目标属于 400，不能和"连不上"共用 502 */
            if (targetBody != null) {
                try {
                    com.shimizukenta.jsonhub.JsonHub target =
                            com.shimizukenta.jsonhub.JsonHub.fromJson(targetBody);
                    if (!target.isObject()
                            || str(target, "host").isBlank()
                            || num(target, "port") < 1
                            || num(target, "port") > 65535) {
                        respond(ex, 400, "{\"ok\":false,\"error\":\"bad-target\"}");
                        return true;
                    }
                } catch (RuntimeException e) {
                    respond(ex, 400, "{\"ok\":false,\"error\":\"bad-target\"}");
                    return true;
                }
            }
            SessionOutcome outcome = this.context.openHostSim(parts[0], targetBody);
            audit(ex, "host-sim-open", parts[0], "outcome=" + outcome
                    + (targetBody == null ? "" : " remote"));
            switch (outcome) {
                case OK -> respond(ex, 201, "{\"ok\":true}");
                case CONFLICT -> respond(ex, 409, "{\"ok\":false,\"error\":\"host-busy-or-sim-open\"}");
                case UNKNOWN_SESSION -> respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
                default -> respond(ex, 502, "{\"ok\":false,\"error\":\"open-failed\"}");
            }
            return true;
        }
        if (parts.length == 3 && "send".equals(parts[2])) {
            /* 与 /host-sims/{id}/send 对齐：SML 上限 64 KiB，不是默认的 4 KiB。
               两条路径同义却给不同上限，短报文走哪条都行、长报文只有一条能发，
               排查起来毫无线索。 */
            String body = readBody(ex, MAX_SML_BYTES);
            if (body == null || body.trim().isEmpty()) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
                return true;
            }
            try {
                String result = this.context.sendHostSml(parts[0], body);
                if (result == null) {
                    respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
                    return true;
                }
                respond(ex, 200, result);
            } catch (IllegalArgumentException e) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"SML 解析失败: " + json(e.getMessage()) + "\"}");
            } catch (IllegalStateException e) {
                respond(ex, 409, "{\"ok\":false,\"error\":\"host-sim-not-open\"}");
            } catch (IOException e) {
                respond(ex, 502, "{\"ok\":false,\"error\":\"send-failed: " + json(e.getMessage()) + "\"}");
            }
            return true;
        }
        if (parts.length == 3 && "collector".equals(parts[2])) {
            String body = readBody(ex, MAX_CONFIG_BYTES);
            if (body == null || body.isBlank()) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
                return true;
            }
            CollectorResult result = this.context.setHostSimCollector(parts[0], body);
            respondCollector(ex, result, "host-sim-collector-config", parts[0]);
            return true;
        }
        if (parts.length == 4 && "collector".equals(parts[2])
                && ("start".equals(parts[3]) || "stop".equals(parts[3]))) {
            CollectorResult result = this.context.hostSimCollectorCommand(parts[0], "start".equals(parts[3]));
            respondCollector(ex, result, "host-sim-collector-" + parts[3], parts[0]);
            return true;
        }
        return false;
    }
}