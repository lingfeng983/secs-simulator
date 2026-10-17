package com.shimizukenta.secssim.app.session;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主机通过 S2F33 / S2F35 / S2F37 下发的动态报告定义。
 *
 * <p>它是本地配置之上的<b>覆盖层</b>（见 ADR 0006）：主机定义过的条目以主机为准，
 * 主机没碰过的仍按本地配置；主机删除某条目时回落到本地配置，而不是变成"没有报告"。
 * </p>
 *
 * <p>纯数据结构，不依赖协议与 JSON，可以脱离链路单测。</p>
 */
public final class HostReportTable {

    private final Map<Integer, List<Integer>> reports = new ConcurrentHashMap<>();
    private final Map<Integer, List<Integer>> links = new ConcurrentHashMap<>();
    private final Set<Integer> disabledCeids = ConcurrentHashMap.newKeySet();

    private volatile boolean allDisabled;

    /**
     * 定义/替换一个报告。空的 VID 列表表示删除该报告。
     *
     * @param rptid 报告 ID
     * @param svids VID 列表
     */
    public void defineReport(int rptid, List<Integer> svids) {
        if (svids == null || svids.isEmpty()) {
            this.reports.remove(rptid);
        } else {
            this.reports.put(rptid, List.copyOf(svids));
        }
    }

    /**
     * 清空全部主机定义的报告（S2F33 传空列表）。
     */
    public void clearReports() {
        this.reports.clear();
    }

    /**
     * 建立事件与报告的链接。空的报告列表表示解除该事件的链接。
     *
     * @param ceid  CEID
     * @param rptid 报告列表
     */
    public void link(int ceid, List<Integer> rptid) {
        if (rptid == null || rptid.isEmpty()) {
            this.links.remove(ceid);
        } else {
            this.links.put(ceid, List.copyOf(rptid));
        }
    }

    /**
     * 解除全部链接（S2F35 传空列表）。
     */
    public void clearLinks() {
        this.links.clear();
    }

    /**
     * 启用/禁用事件上报。
     *
     * <p>CEID 列表为空时表示"全部"：启用则清除全部禁用状态，禁用则整体关闭。
     * </p>
     *
     * @param enabled 是否启用
     * @param ceids   目标事件；空表示全部
     */
    public void setEnabled(boolean enabled, Collection<Integer> ceids) {
        if (ceids == null || ceids.isEmpty()) {
            this.allDisabled = !enabled;
            this.disabledCeids.clear();
            return;
        }

        if (enabled) {
            this.disabledCeids.removeAll(ceids);
        } else {
            this.disabledCeids.addAll(new HashSet<>(ceids));
        }
    }

    /**
     * 某事件是否允许上报。
     *
     * @param ceid CEID
     * @return 允许返回 {@code true}
     */
    public boolean isEnabled(int ceid) {
        return !this.allDisabled && !this.disabledCeids.contains(ceid);
    }

    /**
     * 主机为该事件定义的报告列表。
     *
     * @param ceid CEID
     * @return 有主机定义时返回报告列表
     */
    public Optional<List<Integer>> reportsFor(int ceid) {
        return Optional.ofNullable(this.links.get(ceid));
    }

    /**
     * 主机为某报告定义的 VID 列表。
     *
     * @param rptid 报告 ID
     * @return 有主机定义时返回 VID 列表
     */
    public Optional<List<Integer>> svidsOf(int rptid) {
        return Optional.ofNullable(this.reports.get(rptid));
    }

    /**
     * 主机定义的报告数量。
     *
     * @return 数量
     */
    public int reportCount() {
        return this.reports.size();
    }

    /**
     * 主机定义的链接数量。
     *
     * @return 数量
     */
    public int linkCount() {
        return this.links.size();
    }

    /**
     * 是否整体被禁用。
     *
     * @return 整体禁用返回 {@code true}
     */
    public boolean isAllDisabled() {
        return this.allDisabled;
    }
}
