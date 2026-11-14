package com.shimizukenta.secssim.runtime;

import java.util.function.Function;

import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;
import com.shimizukenta.secssim.store.RuntimeStateRepository;

/**
 * 运行态改动写穿到 runtime.db（设计 §2.4「断开保留、重连恢复」的数据侧）。
 *
 * <p>钉值、计划启用态、运行时计划正文、目录编辑这些改动的第一现场在内存对象里，
 * 这里负责把**成功的那一次**同步落库；重启后由 {@code Bootstrap} 的注册观察者
 * 按设备回填。写库是辅助动作：runtime.db 掉了、磁盘满了都不该让正在处理的
 * HTTP 请求 500——异常只记日志。</p>
 *
 * <p>设备键统一用 config.db 的 ULID（v1 的 legacy stem 由调用方归一），
 * 与台账、{@code device_link} 的归属保持一致。</p>
 */
final class RuntimePersister {

    private final RuntimeStateRepository state;

    /** 会话 id / v1 legacy stem → config.db ULID。 */
    private final Function<String, String> deviceKey;

    RuntimePersister(RuntimeStateRepository state, Function<String, String> deviceKey) {
        this.state = state;
        this.deviceKey = deviceKey == null ? (id -> id) : deviceKey;
    }

    /** 钉住一个状态变量的值。 */
    void pin(String sessionId, long svid, double value) {
        this.guard("钉值", () -> this.state.pinSvid(this.key(sessionId), svid, value));
    }

    /** 取消钉值（原本没钉住也照样删，幂等）。 */
    void unpin(String sessionId, long svid) {
        this.guard("取消钉值", () -> this.state.unpinSvid(this.key(sessionId), svid));
    }

    /** 计划启用态。 */
    void planState(String sessionId, String planId, boolean enabled) {
        this.guard("计划启用态",
                () -> this.state.savePlanState(this.key(sessionId), planId, enabled, null, ""));
    }

    /** 运行时计划正文覆盖（add / update / remove）。 */
    void planOverride(String sessionId, String planId, String op, String bodyJson) {
        this.guard("计划覆盖",
                () -> this.state.savePlanOverride(this.key(sessionId), planId, op, bodyJson));
    }

    /** 运行时目录编辑（kind = ceid / report / svid）。 */
    void catalogEdit(String sessionId, String kind, long key, String op, String bodyJson) {
        this.guard("目录编辑", () -> this.state.saveCatalogEdit(
                this.key(sessionId), kind, key, op, bodyJson));
    }

    /** 主机动态报告（含 S2F37 启用态），重连后恢复用。 */
    void hostReport(String deviceId, String payloadJson) {
        this.guard("主机动态报告", () -> this.state.saveHostReport(this.key(deviceId), payloadJson));
    }

    /** 链路相位（设备侧）。 */
    void devicePhase(String deviceId, String phase, String lastError) {
        this.guard("设备链路相位",
                () -> this.state.updateDevicePhase(this.key(deviceId), phase, lastError));
    }

    /**
     * 清空运行态编辑（钉值 / 计划覆盖 / 目录编辑）。
     *
     * <p>销毁与重载都要清：前者是「设备没了」，后者是「按配置恢复」。
     * 计划启用态不清——那是用户明确的开关选择，断开重连后要保留。</p>
     */
    void clearEdits(String deviceId) {
        this.guard("清空运行态编辑", () -> this.state.clearRuntimeEdits(this.key(deviceId)));
    }

    /**
     * 运行态编辑的快照（重载失败时用来回滚）。
     *
     * <p>重载必须"先清库再重建"——不清的话，新会话注册时观察者会把刚清掉的
     * 钉值/计划又装回去。但重建可能失败（配置非法、端口被占），那时旧会话虽然
     * 被 {@code SessionLauncher} 拉回来了，运行态编辑却已经被删掉，
     * 用户会莫名其妙地丢掉钉值与计划正文。所以在清之前先抓一份快照。</p>
     *
     * @param deviceId 会话 / 设备 id
     * @return 快照；读库失败返回 {@code null}（降级：失败时无法回滚）
     */
    Edits snapshot(String deviceId) {
        String key = this.key(deviceId);
        try {
            return new Edits(this.state.pinnedSvids(key),
                    this.state.planStates(key),
                    this.state.planOverrides(key),
                    this.state.catalogEdits(key));
        } catch (RuntimeException e) {
            Log.error("读运行态编辑失败（重载失败时将无法回滚）: " + key + " " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 把快照写回 runtime.db（{@link #snapshot} 的逆操作）。
     *
     * @param deviceId 会话 / 设备 id
     * @param edits    快照；{@code null} 表示没有可回滚的内容
     */
    void restore(String deviceId, Edits edits) {
        if (edits == null) {
            return;
        }
        String key = this.key(deviceId);
        edits.pins().forEach((svid, value) ->
                this.guard("回写钉值", () -> this.state.pinSvid(key, svid, value)));
        for (RuntimeStateRepository.PlanStateRow row : edits.planStates()) {
            this.guard("回写计划启用态", () -> this.state.savePlanState(
                    key, row.planId(), row.enabled(), row.nextFireAt(), row.lastError()));
        }
        for (RuntimeStateRepository.OverrideRow row : edits.planOverrides()) {
            this.guard("回写计划覆盖",
                    () -> this.state.savePlanOverride(key, row.key(), row.op(), row.bodyJson()));
        }
        for (RuntimeStateRepository.OverrideRow row : edits.catalogEdits()) {
            String raw = row.key();
            int colon = raw.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String kind = raw.substring(0, colon);
            final long entryKey;
            try {
                entryKey = Long.parseLong(raw.substring(colon + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            this.guard("回写目录编辑",
                    () -> this.state.saveCatalogEdit(key, kind, entryKey, row.op(), row.bodyJson()));
        }
    }

    /**
     * 把运行态编辑重新播到一个活着的会话上（回滚之后用）。
     *
     * <p>回滚只把行写回库；被拉回来的旧会话是在"库还空着"的时候注册的，
     * 内存里并没有这些编辑。不补这一步，界面会显示钉值已丢，而库里还留着。</p>
     *
     * @param session  目标会话
     * @param deviceId 会话 / 设备 id
     */
    void replay(HsmsSessionRuntime session, String deviceId) {
        Bootstrap.restoreRuntimeEdits(session, this.key(deviceId), this.state);
    }

    /** 运行态编辑快照（钉值 / 计划启用态 / 计划覆盖 / 目录编辑）。 */
    record Edits(java.util.Map<Long, Double> pins,
                 java.util.List<RuntimeStateRepository.PlanStateRow> planStates,
                 java.util.List<RuntimeStateRepository.OverrideRow> planOverrides,
                 java.util.List<RuntimeStateRepository.OverrideRow> catalogEdits) {
    }

    private String key(String id) {
        try {
            String canonical = this.deviceKey.apply(id);
            return canonical == null || canonical.isBlank() ? id : canonical;
        } catch (RuntimeException e) {
            /* 配置库临时读不了：退回原键，宁可让运行态暂时归错也不能把请求带崩 */
            return id;
        }
    }

    private void guard(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            Log.error("写 " + what + " 到 runtime.db 失败（业务已生效，重启后可能丢失）: "
                    + e.getMessage(), e);
        }
    }
}
