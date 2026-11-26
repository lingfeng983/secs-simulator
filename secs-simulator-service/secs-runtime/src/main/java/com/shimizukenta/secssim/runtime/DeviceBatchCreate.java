package com.shimizukenta.secssim.runtime;

import java.util.ArrayList;
import java.util.List;

import com.shimizukenta.secssim.api.http.ConfigOutcome;
import com.shimizukenta.secssim.api.http.ConfigResult;
import com.shimizukenta.secssim.api.http.DeviceBatch;
import com.shimizukenta.secssim.api.http.SessionCreateRequest;
import com.shimizukenta.secssim.api.http.SessionCreated;
import com.shimizukenta.secssim.api.http.SessionOutcome;
import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.app.session.SessionManager;
import com.shimizukenta.secssim.app.session.SessionRuntime;

/**
 * 批量新建设备（从 RuntimeApi 抽出，评审 2026-11-24 A2 千行拆分）。
 *
 * <p>一台一份配置，设备名 = 批次前缀 + 序号；重名整批拒绝；
 * 逐台失败记进 failed 继续下一台。语义与拆分前逐行等价。</p>
 */
final class DeviceBatchCreate {

    private DeviceBatchCreate() {
        /* 纯静态工具 */
    }

    /**
     * 批量新建设备：一台一份配置，设备名 = 批次前缀 + 序号。
     *
     * <p>先把整批名字算出来查一遍重名：建到第 4 台才发现撞名，留下三台半成品
     * 让人自己收拾，是最糟的结果。重名就整批拒绝，一台都不建。</p>
     *
     * <p>逐台建配置 + 按需启动会话：配置建失败的那台记进 {@code failed} 继续下一台
     * （端口被占是单台的事）；会话起不来不影响设备本身——设备已经建好，
     * 界面里以「停止」态出现，点「启动」还能再试。</p>
     *
     * @param request 模板、前缀、起始序号、台数、端口段与启动开关
     * @return 逐台结果
     */
    static DeviceBatch.Result createDevicesBatch(DeviceBatch.Request request,
            ConfigStore configs, TemplateStore templates, SessionManager sessions,
            java.util.function.Function<SessionCreateRequest, SessionCreated> sessionCreator) {
        String prefix = request.prefix() == null ? "" : request.prefix().trim();
        List<String> problems = DeviceBatchPlan.problems(
                prefix, request.start(), request.count(), request.portBase());
        if (!problems.isEmpty()) {
            /* 入参错不是"名字撞了"：分开装，HTTP 层才能分别映射成 400 与 409 */
            return new DeviceBatch.Result(List.of(), List.of(), List.of(), problems);
        }
        int count = request.count();
        int start = request.start();
        int portBase = request.portBase();

        List<String> devices = DeviceBatchPlan.deviceNames(prefix, start, count);
        List<String> names = DeviceBatchPlan.configNames(prefix, start, count);
        List<String> taken = new ArrayList<>();
        for (ConfigStore.Entry entry : configs.list()) {
            taken.add(entry.name());
        }
        /* 名字唯一是硬要求：撞上的话整批拒绝，一台都不建 */
        List<String> conflicts = DeviceBatchPlan.conflicts(names, taken);
        if (!conflicts.isEmpty()) {
            return new DeviceBatch.Result(List.of(), List.of(), conflicts);
        }

        /* 端口占用只看活会话：配置里写了端口但设备没启动的，端口本来就是空的，
           起新会话不会撞 */
        java.util.Set<Integer> usedPorts = new java.util.HashSet<>();
        for (SessionRuntime session : sessions.list()) {
            com.shimizukenta.secssim.app.session.HsmsConnectionTarget target = session.hostTarget();
            if (target != null && target.port() > 0) {
                usedPorts.add(target.port());
            }
        }

        String templateId = request.templateId() == null || request.templateId().isBlank()
                ? DeviceTemplates.BARE_ID : request.templateId().trim();
        String custom = templates.customText(templateId).orElse(null);
        String bindHost = request.bindHost() == null || request.bindHost().isBlank()
                ? "0.0.0.0" : request.bindHost().trim();

        List<DeviceBatch.Created> created = new ArrayList<>(count);
        List<DeviceBatch.Failed> failed = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = names.get(i);
            String deviceName = devices.get(i);
            int port = portBase + i;
            /* 批量新建的 device-id 恒为 0：这一批是同一角色下的多台设备，
               逐台编 device-id 只会让现场对着主机侧配置多改一遍 */
            int deviceId = 0;
            if (usedPorts.contains(port)) {
                failed.add(new DeviceBatch.Failed(name, "port-in-use",
                        "端口 " + port + " 已被其他设备占用"));
                continue;
            }
            ConfigResult result = custom == null
                    ? configs.createNamed(name, templateId, deviceName,
                            port, bindHost, request.passive(), deviceId)
                    : createNamedFromText(configs, name, custom, templateId, deviceName,
                            port, bindHost, request.passive(), deviceId);
            if (result.outcome() != ConfigOutcome.OK) {
                failed.add(new DeviceBatch.Failed(name, outcomeCode(result.outcome()),
                        result.problems().isEmpty() ? "新建失败" : String.join("；", result.problems())));
                continue;
            }
            usedPorts.add(port);
            String sessionId = "";
            String sessionError = "";
            if (request.startNow()) {
                SessionCreated started = sessionCreator.apply(
                        new SessionCreateRequest(name, bindHost, port, deviceId));
                if (started.outcome() == SessionOutcome.OK) {
                    sessionId = started.sessionId();
                } else {
                    /* 配置已经建好，会话起不来只是这一台暂时不能用：
                       记进结果，界面显示「停止」，点「启动」还能再试 */
                    sessionError = started.outcome().name().toLowerCase(java.util.Locale.ROOT);
                    Log.warn("批量新建的设备 " + name + " 已建好，但会话没起来: " + sessionError);
                }
            }
            created.add(new DeviceBatch.Created(name, deviceName, port, deviceId, sessionId, sessionError));
        }
        return new DeviceBatch.Result(created, failed, List.of());
    }

    /** 自定义模板路径下的「按模板建一台带名字的设备」：把模板正文改成这台设备的样子。 */
    private static ConfigResult createNamedFromText(ConfigStore configs, String name, String custom, String templateId,
                                             String deviceName, int port, String bindHost,
                                             boolean passive, int deviceId) {
        String patched = ConfigStore.rewriteConnection(custom, name.replaceAll("\\.json$", ""),
                deviceName, port, bindHost, passive, deviceId);
        return configs.createFromText(name, patched, templateId);
    }

    /** 结果码 → 批量响应里的 error 字段。 */
    private static String outcomeCode(ConfigOutcome outcome) {
        return switch (outcome) {
            case CONFLICT -> "conflict";
            case INVALID -> "invalid";
            case NOT_FOUND -> "bad-name";
            default -> "failed";
        };
    }
}
