package com.shimizukenta.secssim.runtime.session;

import com.shimizukenta.secssim.app.session.TraceLimits;
import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.core.context.ContextTable;

/**
 * 设备模型相关的派生视图：S6F11 组装器与 Trace。
 *
 * <p>模型热替换时整体重建这两份引用，下一次组装/采样立刻用新模型，不必重建会话。</p>
 */
final class SessionModelViews {

    private final HsmsSessionRuntime host;
    private final TraceLimits traceLimits;

    SessionModelViews(HsmsSessionRuntime host, TraceLimits traceLimits) {
        this.host = host;
        this.traceLimits = traceLimits;
    }

    /**
     * 接入 v7 数据模型：晶圆属性表 + 设备常量取值器。
     *
     * <p>装配器持有的是引用而不是快照：热生效时整体替换这两份，
     * S6F11 下一次组装立刻用新的属性/常量，不必重建会话。</p>
     *
     * @param context 晶圆属性表
     * @param ecids   设备常量取值器
     */
    void dataModel(ContextTable context, EcidValues ecids) {
        this.host.contextTable = context == null ? ContextTable.empty() : context;
        this.host.ecidValues = ecids;
        this.rebuildAssembler();
        if (this.host.hostSide != null) {
            this.host.hostSide.ecidValues(ecids);
        }
    }

    /** 换设备模型后重建引用它的视图（S6F11 组装器与 Trace）。 */
    void refresh(ResolvedEquipment next) {
        this.host.model = next;
        this.rebuildAssembler();
        /* 容量限制跟随会话配置，热重载时不变 */
        this.host.tracePump.traceLimits(this.traceLimits.maxTracesOrDefault(),
                this.traceLimits.maxSvidCountOrDefault());
        this.host.tracePump.reset(this.host.model, this.host.values);
    }

    private void rebuildAssembler() {
        this.host.assembler = new S6F11Assembler(this.host.model, this.host.values,
                this.host.hostReports, this.host.contextTable, this.host.ecidValues);
    }
}