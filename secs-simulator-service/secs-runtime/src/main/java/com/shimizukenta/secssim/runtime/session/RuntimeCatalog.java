package com.shimizukenta.secssim.runtime.session;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.shimizukenta.secssim.app.session.HostReportTable;
import com.shimizukenta.secssim.core.compile.ResolvedCeid;
import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.core.compile.ResolvedReport;
import com.shimizukenta.secssim.core.compile.ResolvedSvid;

/**
 * 运行时目录：设备模型里的 CEID / RPTID / SVID 的增删改。
 *
 * <p>这些是"会话开着的时候临时加一条"的编辑——不写回配置文件，
 * 重载会话后按配置恢复。模型本身由会话层持有，这里只负责
 * 校验引用合法性并算出新的模型。</p>
 */
final class RuntimeCatalog {

    /** 当前设备模型。 */
    private final java.util.function.Supplier<ResolvedEquipment> modelSupplier;

    /** 主机定义的报告/链接（S2F33/S2F35 写入的那些也算数）。 */
    private final HostReportTable hostReports;

    /** 换模型后的回调：重建引用模型的视图。 */
    private java.util.function.Consumer<ResolvedEquipment> onModelChanged;

    /** 值存储：新增 SVID 时要给它绑一条零曲线。 */
    private com.shimizukenta.secssim.core.value.ValueStore store;

    RuntimeCatalog(java.util.function.Supplier<ResolvedEquipment> modelSupplier,
                  HostReportTable hostReports) {
        this.modelSupplier = modelSupplier;
        this.hostReports = hostReports;
    }

    void onModelChanged(java.util.function.Consumer<ResolvedEquipment> callback) {
        this.onModelChanged = callback;
    }

    /** 设置值存储（新增 SVID 时绑定零曲线）。 */
    void store(com.shimizukenta.secssim.core.value.ValueStore store) {
        this.store = store;
    }

    /** 当前设备模型。 */
    private ResolvedEquipment model() {
        return this.modelSupplier.get();
    }

    /** 换模型：交给会话层重建视图。 */
    private void refreshModelViews(ResolvedEquipment next) {
        java.util.function.Consumer<ResolvedEquipment> callback = this.onModelChanged;
        if (callback != null) {
            callback.accept(next);
        }
    }


    /**
     * 运行时新增报告；RPTID 重复返回 {@code false}。
     *
     * @param report 报告定义
     * @return 是否成功
     */
    synchronized boolean addReport(ResolvedReport report) {
        requireKnownSvids(report.svidIds());
        if (model().reports().containsKey(report.id())) {
            return false;
        }
        java.util.Map<Integer, ResolvedReport> reports = new java.util.HashMap<>(model().reports());
        reports.put(report.id(), report);
        refreshModelViews(model().withReports(reports));
        return true;
    }


    /**
     * 运行时更新报告；不存在返回 {@code false}。
     *
     * @param report 报告定义
     * @return 是否成功
     */
    synchronized boolean updateReport(ResolvedReport report) {
        requireKnownSvids(report.svidIds());
        if (!model().reports().containsKey(report.id())) {
            return false;
        }
        java.util.Map<Integer, ResolvedReport> reports = new java.util.HashMap<>(model().reports());
        reports.put(report.id(), report);
        refreshModelViews(model().withReports(reports));
        return true;
    }


    /**
     * 运行时删除报告。
     *
     * @param rptid 绝对 RPTID
     * @return 是否删除了报告
     */
    synchronized boolean removeReport(int rptid) {
        if (!model().reports().containsKey(rptid)) {
            return false;
        }
        java.util.Map<Integer, ResolvedReport> reports = new java.util.HashMap<>(model().reports());
        reports.remove(rptid);
        refreshModelViews(model().withReports(reports));
        return true;
    }


    /**
     * 运行时新增采集事件；CEID 重复返回 {@code false}。
     *
     * @param def 事件定义
     * @return 是否成功
     */
    synchronized boolean addCeid(ResolvedCeid def) {
        requireKnownRptids(def.reportIds());
        if (model().ceids().containsKey(def.id())) {
            return false;
        }
        java.util.Map<Integer, ResolvedCeid> ceids = new java.util.HashMap<>(model().ceids());
        java.util.Map<Integer, String> unitByCeid = new java.util.HashMap<>(model().unitByCeid());
        ceids.put(def.id(), def);
        unitByCeid.putIfAbsent(def.id(), def.owner());
        refreshModelViews(model().withCeids(ceids, unitByCeid));
        return true;
    }


    /**
     * 运行时更新采集事件(符号名/绑定报告)。
     *
     * @param def 事件定义
     * @return 是否成功
     */
    synchronized boolean updateCeid(ResolvedCeid def) {
        requireKnownRptids(def.reportIds());
        if (!model().ceids().containsKey(def.id())) {
            return false;
        }
        java.util.Map<Integer, ResolvedCeid> ceids = new java.util.HashMap<>(model().ceids());
        ceids.put(def.id(), def);
        refreshModelViews(model().withCeids(ceids, model().unitByCeid()));
        return true;
    }


    /**
     * 运行时删除采集事件。
     *
     * @param ceid 绝对 CEID
     * @return 是否删除了事件
     */
    synchronized boolean removeCeid(int ceid) {
        if (!model().ceids().containsKey(ceid)) {
            return false;
        }
        java.util.Map<Integer, ResolvedCeid> ceids = new java.util.HashMap<>(model().ceids());
        ceids.remove(ceid);
        java.util.Map<Integer, String> unitByCeid = new java.util.HashMap<>(model().unitByCeid());
        unitByCeid.remove(ceid);
        refreshModelViews(model().withCeids(ceids, unitByCeid));
        return true;
    }


    /**
     * 运行时新增状态变量；SVID 重复返回 {@code false}。
     *
     * <p>新变量绑定恒 0 曲线（界面上用「设定」钉住实际值）。</p>
     *
     * @param def 变量定义
     * @return 是否成功
     */
    synchronized boolean addSvid(ResolvedSvid def) {
        if (model().svids().containsKey(def.id())) {
            return false;
        }
        java.util.Map<Integer, ResolvedSvid> svids = new java.util.HashMap<>(model().svids());
        svids.put(def.id(), def);
        refreshModelViews(model().withSvids(svids));
        this.store.bind(def.id(), new com.shimizukenta.secssim.core.curve.ConstantCurve(0.0));
        return true;
    }


    /**
     * 运行时更新状态变量（符号名/归属/类型/单位）；不存在返回 {@code false}。
     *
     * @param def 变量定义
     * @return 是否成功
     */
    synchronized boolean updateSvid(ResolvedSvid def) {
        if (!model().svids().containsKey(def.id())) {
            return false;
        }
        java.util.Map<Integer, ResolvedSvid> svids = new java.util.HashMap<>(model().svids());
        svids.put(def.id(), def);
        refreshModelViews(model().withSvids(svids));
        return true;
    }


    /**
     * 运行时删除状态变量（连同其值绑定）。
     *
     * @param svid 绝对 SVID
     * @return 是否删除了变量
     */
    synchronized boolean removeSvid(int svid) {
        if (!model().svids().containsKey(svid)) {
            return false;
        }
        java.util.Map<Integer, ResolvedSvid> svids = new java.util.HashMap<>(model().svids());
        svids.remove(svid);
        refreshModelViews(model().withSvids(svids));
        this.store.unbind(svid);
        return true;
    }


    /* 引用校验:和配置编译的 UNKNOWN_CEID 同语义——编辑入口当场说清,不留悬空引用。 */

    void requireKnownCeid(int ceid) {
        if (!model().ceids().containsKey(ceid) && !this.hostReports.definedCeids().contains(ceid)) {
            throw new IllegalArgumentException("CEID 未定义: " + ceid + "（先在「事件设置」里定义它）");
        }
    }


    void requireKnownSvids(List<Integer> svids) {
        for (int svid : svids) {
            if (!model().svids().containsKey(svid)) {
                throw new IllegalArgumentException("SVID 未定义: " + svid + "（先在「状态变量」里定义它）");
            }
        }
    }


    void requireKnownRptids(List<Integer> rptids) {
        for (int rptid : rptids) {
            if (!model().reports().containsKey(rptid) && !this.hostReports.definedRptids().contains(rptid)) {
                throw new IllegalArgumentException("RPTID 未定义: " + rptid + "（先定义报告再绑定事件）");
            }
        }
    }
}
