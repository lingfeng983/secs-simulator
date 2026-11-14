package com.shimizukenta.secssim.runtime.session;

import com.shimizukenta.secssim.core.compile.ResolvedCeid;
import com.shimizukenta.secssim.core.compile.ResolvedReport;
import com.shimizukenta.secssim.core.compile.ResolvedSvid;

/**
 * 运行时编辑入口：钉住状态变量的值，增删改 CEID / RPTID / SVID。
 *
 * <p>这些改动只作用于当前会话，不写回配置文件；重载会话后按配置恢复。
 * 真正的校验与模型重建在 {@link RuntimeCatalog} 里。</p>
 */
final class RuntimeEditsFacade {

    /** 运行时目录。 */
    private final RuntimeCatalog catalog;

    /** 设备模型（判断 SVID 是否存在）。 */
    private final java.util.function.Supplier<com.shimizukenta.secssim.core.compile.ResolvedEquipment> model;

    /** 效果叠加层（钉值落在这一层）。 */
    private final java.util.function.Supplier<EffectValueSource> effectValues;

    RuntimeEditsFacade(RuntimeCatalog catalog,
                        java.util.function.Supplier<com.shimizukenta.secssim.core.compile.ResolvedEquipment> model,
                        java.util.function.Supplier<EffectValueSource> effectValues) {
        this.catalog = catalog;
        this.model = model;
        this.effectValues = effectValues;
    }

    /**
     * 「设定」一个状态变量的值：钉住,覆盖曲线与事件效果,直到取消设定或热重载。
     *
     * @param svid  绝对 SVID
     * @param value 设定值
     * @return 是否成功（变量必须存在）
     */
    boolean pinSvidValue(int svid, double value) {
        if (!this.model.get().svids().containsKey(svid)) {
            return false;
        }
        this.effectValues.get().pin(svid, value);
        return true;
    }

    /**
     * 取消「设定」。
     *
     * @param svid 绝对 SVID
     * @return 原来是否有设定
     */
    boolean unpinSvidValue(int svid) {
        return this.effectValues.get().unpin(svid);
    }

    /**
     * 是否被「设定」。
     *
     * @param svid 绝对 SVID
     * @return 已设定返回 {@code true}
     */
    boolean isSvidPinned(int svid) {
        return this.effectValues.get().isPinned(svid);
    }

    /** 新增一个 RPTID 定义。 */
    boolean addReport(ResolvedReport report) {
        return this.catalog.addReport(report);
    }

    /** 更新一个 RPTID 定义。 */
    boolean updateReport(ResolvedReport report) {
        return this.catalog.updateReport(report);
    }

    /** 删除一个 RPTID 定义。 */
    boolean removeReport(int rptid) {
        return this.catalog.removeReport(rptid);
    }

    /** 新增一个 CEID 定义。 */
    boolean addCeid(ResolvedCeid def) {
        return this.catalog.addCeid(def);
    }

    /** 更新一个 CEID 定义。 */
    boolean updateCeid(ResolvedCeid def) {
        return this.catalog.updateCeid(def);
    }

    /** 删除一个 CEID 定义。 */
    boolean removeCeid(int ceid) {
        return this.catalog.removeCeid(ceid);
    }

    /** 新增一个 SVID 定义。 */
    boolean addSvid(ResolvedSvid def) {
        return this.catalog.addSvid(def);
    }

    /** 更新一个 SVID 定义。 */
    boolean updateSvid(ResolvedSvid def) {
        return this.catalog.updateSvid(def);
    }

    /** 删除一个 SVID 定义。 */
    boolean removeSvid(int svid) {
        return this.catalog.removeSvid(svid);
    }
}
