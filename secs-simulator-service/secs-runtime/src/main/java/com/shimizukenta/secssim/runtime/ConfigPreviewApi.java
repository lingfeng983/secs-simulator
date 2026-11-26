package com.shimizukenta.secssim.runtime;

import com.shimizukenta.secssim.app.log.Log;

/**
 * 配置草稿预览（从 RuntimeApi 抽出，评审 2026-11-24 A2 千行拆分）。
 *
 * <p>与 validate 同一条编译路径：失败返回 null，界面显示校验问题而不是半张图。
 * 值来源统一走 ValueSources——草稿预览与真实启动必须是同一套语义。</p>
 */
final class ConfigPreviewApi {

    private ConfigPreviewApi() {
        /* 纯静态工具 */
    }

    static String configPreviewJson(String text, long fromMs, long toMs, int buckets,
                             java.util.Set<Integer> svids, boolean withEvents) {
        /* 与 validate 同一条编译路径:失败返回 null,界面显示校验问题而不是半张图 */
        try {
            java.nio.file.Path temp = java.nio.file.Files.createTempFile("secs-preview-", ".json");
            try {
                java.nio.file.Files.writeString(temp, text, java.nio.charset.StandardCharsets.UTF_8);
                com.shimizukenta.secssim.app.config.SessionConfig config =
                        com.shimizukenta.secssim.app.config.SessionConfigJson.read(temp);
                com.shimizukenta.secssim.core.compile.CompilationResult compiled = config.compile();
                if (!compiled.ok()) {
                    return null;
                }
                /* 值来源统一走 ValueSources:草稿预览与真实启动必须是同一套语义 */
                com.shimizukenta.secssim.core.value.ValueSource values =
                        com.shimizukenta.secssim.app.config.ValueSources.of(config, compiled.equipment());
                java.util.List<com.shimizukenta.secssim.runtime.session.PreviewSupport.PreviewEvent> events =
                java.util.List.of();
                java.util.List<com.shimizukenta.secssim.runtime.session.PreviewSupport.PreviewEvent> cycleEvents =
                java.util.List.of();
                /* cycle 已从编排表拆出（M3）：草稿预览的事件时间线 = 手写计划模拟
                   ∪ CycleRunner.simulateFires（锚 = 窗口起点，"如果现在启动"）；
                   值曲线的草稿时间线也从窗口起点起算，预览与实跑同锚（M3 追加 3） */
                com.shimizukenta.secssim.core.event.EventSchedule schedule = config.schedule();
                if (withEvents) {
                    /* 草稿没有真实锚点：计划统一按「窗口起点启动」求值（设计 §2.4） */
                    java.util.Map<String, Long> previewAnchors = schedule.plans().stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    com.shimizukenta.secssim.core.event.EventPlan::id,
                                    plan -> fromMs,
                                    (a, b) -> a));
                    java.util.List<com.shimizukenta.secssim.runtime.session.PreviewSupport.PreviewEvent> planMarks =
                            com.shimizukenta.secssim.runtime.session.EventSimulator.simulate(
                                            schedule, values, config.spec().seed(), fromMs, toMs,
                                            com.shimizukenta.secssim.runtime.session.EventSimulator.DEFAULT_MAX_EVENTS,
                                            previewAnchors)
                                    .stream()
                                    .map(fire -> new com.shimizukenta.secssim.runtime.session.PreviewSupport.PreviewEvent(
                                            fire.atMs(), fire.ceid(), fire.planId()))
                                    .toList();
                    /* 工艺周期标记独立一袋：周期预览与事件计划预览各算各的（2026-11-17 拆分） */
                    java.util.List<com.shimizukenta.secssim.runtime.session.PreviewSupport.PreviewEvent> cycleMarks =
                            new java.util.ArrayList<>();
                    if (config.cycle() != null) {
                        for (long[] mark : com.shimizukenta.secssim.runtime.session.CycleRunner.simulateFires(
                                config.cycle(), config.spec().seed(), fromMs, fromMs, toMs)) {
                            cycleMarks.add(new com.shimizukenta.secssim.runtime.session.PreviewSupport.PreviewEvent(
                                    mark[0], (int) mark[1], "cycle"));
                        }
                    }
                    events = java.util.List.copyOf(planMarks);
                    cycleEvents = java.util.List.copyOf(cycleMarks);
                }
                com.shimizukenta.secssim.runtime.session.PreviewSupport.PreviewData computed =
                        com.shimizukenta.secssim.runtime.session.PreviewSupport.compute(
                                values, compiled.equipment(), fromMs, toMs, buckets, svids, events);
                /* 曲线之外把两袋标记原样带回：周期预览用 cycleEvents、事件计划预览用 events */
                com.shimizukenta.secssim.runtime.session.PreviewSupport.PreviewData data =
                        new com.shimizukenta.secssim.runtime.session.PreviewSupport.PreviewData(
                                computed.fromMs(), computed.toMs(), computed.buckets(), computed.bucketMs(),
                                computed.svidTotal(), computed.series(), events, cycleEvents);
                return com.shimizukenta.secssim.runtime.session.PreviewSupport.toJson(data);
            } finally {
                java.nio.file.Files.deleteIfExists(temp);
            }
        } catch (IllegalArgumentException e) {
            /* 参数类问题（分桶数、时间窗等）：界面按"草稿非法"展示（HTTP 422） */
            Log.error("配置草稿预览参数无效: " + e.getMessage(), e);
            return null;
        } catch (com.shimizukenta.secssim.app.config.ConfigException
                | com.shimizukenta.secssim.core.template.ExpressionException e) {
            /* 草稿的语法/字段/表达式错误都属于用户输入，继续映射为 null（422）。
               表达式里的 p.* 引用只在求值期才暴露（解析期不校验），必须一起接住，
               否则用户写错一个参数会看到 500 而不是"配置非法" */
            Log.error("配置草稿预览失败: " + e.getMessage(), e);
            return null;
        } catch (java.io.IOException e) {
            /* 临时文件读写失败是环境问题：上抛成 500，不再伪装成"草稿非法" */
            throw new IllegalStateException("配置草稿预览失败", e);
        }
    }
}
