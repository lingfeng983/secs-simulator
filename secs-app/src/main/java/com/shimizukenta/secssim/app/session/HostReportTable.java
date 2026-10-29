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

    /** 逐事件禁用；{@code allDisabled == false} 时生效。 */
    private final Set<Integer> disabledCeids = ConcurrentHashMap.newKeySet();

    /**
     * "全部禁用"之后被单独重新启用的事件；{@code allDisabled == true} 时生效。
     *
     * <p>没有这张覆盖表时，S2F37 的「先全部禁用、再启用某几个事件」是无效的：
     * 启用操作只把 CEID 从 {@link #disabledCeids} 移除，而 {@code allDisabled}
     * 仍是 true，{@link #isEnabled(int)} 恒返回 false——主机以为已生效，
     * 设备却永远不上报。</p>
     */
    private final Set<Integer> enabledCeids = ConcurrentHashMap.newKeySet();

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
    public synchronized void setEnabled(boolean enabled, Collection<Integer> ceids) {
        if (ceids == null || ceids.isEmpty()) {
            this.allDisabled = !enabled;
            this.disabledCeids.clear();
            this.enabledCeids.clear();
            return;
        }

        if (enabled) {
            if (this.allDisabled) {
                this.enabledCeids.addAll(ceids);
            } else {
                this.disabledCeids.removeAll(ceids);
            }
        } else {
            if (this.allDisabled) {
                this.enabledCeids.removeAll(ceids);
            } else {
                this.disabledCeids.addAll(new HashSet<>(ceids));
            }
        }
    }

    /**
     * 某事件是否允许上报。
     *
     * @param ceid CEID
     * @return 允许返回 {@code true}
     */
    public synchronized boolean isEnabled(int ceid) {
        return this.allDisabled
                ? this.enabledCeids.contains(ceid)
                : !this.disabledCeids.contains(ceid);
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

    /**
     * 主机定义过的报告 ID 快照。
     *
     * <p>返回快照而不是 live 视图：调用方多半要遍历并渲染成 JSON，
     * 拿到的是"某一刻的主机定义"比"边遍历边变化"可预期得多。
     * </p>
     *
     * @return 报告 ID 副本
     */
    public Set<Integer> definedRptids() {
        return Set.copyOf(this.reports.keySet());
    }

    /**
     * 主机定义过链接的事件 ID 快照。
     *
     * @return CEID 副本
     */
    public Set<Integer> definedCeids() {
        return Set.copyOf(this.links.keySet());
    }
}
