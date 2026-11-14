package com.shimizukenta.secssim.api.http;

import java.util.Map;
import java.io.IOException;

import com.sun.net.httpserver.HttpExchange;

/**
 * 曲线预览域：分桶曲线（JSON）与二进制曲线（SCSP v1）。
 */
final class PreviewHandler extends HttpSupport {

    /** 预览桶数上下限，与运行时实现保持一致。 */
    private static final int PREVIEW_BUCKETS_MIN = 1;
    private static final int PREVIEW_BUCKETS_MAX = 500;

    PreviewHandler(ApiContext context, AccessControl access, AuditSink audit) {
        super(context, access, audit);
    }


    /**
     * 曲线预览：{@code /api/v1/preview?session=demo&from=0&to=60000&buckets=120}。
     *
     * <p>参数非法一律 400：静默回落默认值会让调用方以为自己的参数生效了。
     * schema v6 增加可选 {@code svids=401,402} 过滤与 {@code withEvents=false} 关闭事件标记。
     * </p>
     */
    void handlePreview(HttpExchange ex) throws IOException {
        if (!requireRole(ex, ApiRole.VIEWER)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }

        PreviewRequest request = readPreviewRequest(ex);
        if (request == null) {
            return;
        }

        String body = this.context.previewJson(request.sessionId(), request.fromMs(),
                request.toMs(), request.buckets(), request.svids(), request.withEvents(),
                request.planId());
        if (body == null) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
            return;
        }

        respond(ex, 200, body);
    }


    /**
     * 二进制曲线预览：{@code /api/v1/preview.bin?...}。
     *
     * <p>格式见 {@code SessionRuntime.previewBinary}：固定头 + 每条序列的
     * svid 与 min/max/avg 双精度数组，小端。给大曲线或不走 JSON 的客户端用。</p>
     */
    void handlePreviewBinary(HttpExchange ex) throws IOException {
        if (!requireRole(ex, ApiRole.VIEWER)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }

        PreviewRequest request = readPreviewRequest(ex);
        if (request == null) {
            return;
        }

        byte[] body = this.context.previewBinary(request.sessionId(), request.fromMs(),
                request.toMs(), request.buckets());
        if (body == null) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
            return;
        }
        respondBinary(ex, "application/octet-stream", "preview.bin", body);
    }


    PreviewRequest readPreviewRequest(HttpExchange ex) throws IOException {
        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());

        Long fromMs = longParam(query, "from");
        Long toMs = longParam(query, "to");
        Long buckets = longParam(query, "buckets");

        /* 缺省用默认值；写了但不是数字才报错 */
        if ((query.containsKey("from") && fromMs == null)
                || (query.containsKey("to") && toMs == null)
                || (query.containsKey("buckets") && buckets == null)) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-number\"}");
            return null;
        }

        long from = fromMs == null ? 0L : fromMs;
        long to = toMs == null ? 60_000L : toMs;
        long count = buckets == null ? 120L : buckets;

        if (to <= from) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-range\"}");
            return null;
        }
        /* windowMs < 0 捕获 to-from 溢出：from 为极小负数、to 为极大正数时差值会绕回负值 */
        long windowMs = to - from;
        if (windowMs < 0 || windowMs > MAX_PREVIEW_WINDOW_MS) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"range-too-large\"}");
            return null;
        }
        if (count < PREVIEW_BUCKETS_MIN || count > PREVIEW_BUCKETS_MAX) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-buckets\"}");
            return null;
        }

        String sessionId = query.getOrDefault("session", "");
        if (sessionId.isEmpty()) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"missing-session\"}");
            return null;
        }

        java.util.Set<Integer> svids = new java.util.LinkedHashSet<>();
        String rawSvids = query.get("svids");
        if (rawSvids != null && !rawSvids.isBlank()) {
            try {
                for (String piece : rawSvids.split(",")) {
                    if (!piece.isBlank()) {
                        svids.add(Integer.parseInt(piece.trim()));
                    }
                }
            } catch (NumberFormatException e) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-svids\"}");
                return null;
            }
        }

        boolean withEvents = true;
        String rawEvents = query.get("withEvents");
        if (rawEvents != null) {
            withEvents = !"false".equalsIgnoreCase(rawEvents) && !"0".equals(rawEvents);
        }

        /* 只看某一条编排计划：曲线与事件标记都只来自它 */
        String planId = query.getOrDefault("plan", "").trim();

        return new PreviewRequest(sessionId, from, to, (int) count, svids, withEvents, planId);
    }

    private record PreviewRequest(String sessionId, long fromMs, long toMs, int buckets,
                                  java.util.Set<Integer> svids, boolean withEvents, String planId) {
    }
}
