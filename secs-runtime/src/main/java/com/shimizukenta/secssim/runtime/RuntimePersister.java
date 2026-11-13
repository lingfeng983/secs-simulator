package com.shimizukenta.secssim.runtime;

import java.util.function.Function;

import com.shimizukenta.secssim.app.log.Log;
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
