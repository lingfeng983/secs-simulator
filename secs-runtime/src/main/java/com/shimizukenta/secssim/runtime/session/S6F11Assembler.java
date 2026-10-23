package com.shimizukenta.secssim.runtime.session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Builder;
import com.shimizukenta.secssim.app.session.HostReportTable;
import com.shimizukenta.secssim.core.compile.ResolvedCeid;
import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.core.compile.ResolvedReport;
import com.shimizukenta.secssim.core.compile.ResolvedSvid;
import com.shimizukenta.secssim.core.domain.UnitSpec;
import com.shimizukenta.secssim.core.value.ValueSource;

/**
 * 组装 S6F11 报文体。
 *
 * <p>E30 的结构是：</p>
 * <pre>
 * S6F11 W: &lt;L[3]
 *             &lt;U4 DATAID&gt;
 *             &lt;U4 CEID&gt;
 *             &lt;L[n]
 *               &lt;L[2] &lt;U4 RPTID&gt; &lt;L[m] &lt;V 值…&gt;&gt;
 *             &gt;
 *           &gt;
 * </pre>
 *
 * <p>{@code DATAID} 用于在同一 CEID 的多次上报之间建立关联（主机侧多腔/多批次区分）。
 * 本项目不维护事务型 DATAID，固定填 {@code 0}——这是 E30 允许的“不使用 DATAID”写法：
 * 字段必须在，值恒为 0。少这一层会让按 L[3] 解析的标准主机把 CEID 当 DATAID、
 * 报告列表当 CEID，整条报文错位。</p>
 *
 * <p>报告里的值<b>不带 SVID</b>，主机只能按 S2F33/S2F35 定义的顺序解释。
 * 因此组装时必须先问 {@link HostReportTable}：主机是否在运行时改过这条 CEID 的链接或这个
 * 报告的 VID 列表（见 ADR 0006）。</p>
 *
 * <p>另一个易错点：本地配置会在报告首位注入 {@code UNIT_ID} 方便主机区分腔体，
 * 但<b>主机自定义的报告绝不能注入</b>——多一个值会让整条报文错位。</p>
 */
public final class S6F11Assembler {

    /** 事件报文的 DATAID：不区分事务时固定为 0。 */
    public static final int DATAID = 0;

    private final ResolvedEquipment model;
    private final ValueSource values;
    private final HostReportTable hostReports;
    private final Map<String, Integer> unitIdSvidByOwner;

    /**
     * 创建组装器（不带主机动态定义）。
     *
     * @param model  已解析设备模型
     * @param values 值来源
     */
    public S6F11Assembler(ResolvedEquipment model, ValueSource values) {
        this(model, values, new HostReportTable());
    }

    /**
     * 创建组装器。
     *
     * @param model       已解析设备模型
     * @param values      值来源
     * @param hostReports 主机动态定义（覆盖层）
     */
    public S6F11Assembler(ResolvedEquipment model, ValueSource values, HostReportTable hostReports) {
        this.model = model;
        this.values = values;
        this.hostReports = hostReports;
        this.unitIdSvidByOwner = new HashMap<>();

        for (ResolvedSvid svid : model.svids().values()) {
            if (UnitSpec.UNIT_ID_SLOT.equals(svid.slot())) {
                this.unitIdSvidByOwner.putIfAbsent(svid.owner(), svid.id());
            }
        }
    }

    /**
     * 该 CEID 是否可用（本地定义或主机定义任一存在）。
     *
     * @param ceid CEID
     * @return 可用返回 {@code true}
     */
    public boolean knows(int ceid) {
        return this.hostReports.reportsFor(ceid).isPresent() || this.model.ceids().containsKey(ceid);
    }

    /**
     * 组装报文体。
     *
     * @param ceid CEID
     * @param tMs  取值的时刻
     * @return SECS-II 报文体
     */
    public Secs2 build(int ceid, long tMs) {
        List<Integer> reportIds = this.reportIdsFor(ceid);
        Secs2Builder builder = Secs2.getBuilder();
        List<Secs2> reports = new ArrayList<>(reportIds.size());

        for (int reportId : reportIds) {
            Asset asset = this.assetFor(reportId);
            reports.add(builder.list(
                    builder.uint4(reportId),
                    builder.list(this.valuesOf(asset, tMs))));
        }

        return builder.list(
                builder.uint4(DATAID),
                builder.uint4(ceid),
                builder.list(reports));
    }

    private List<Integer> reportIdsFor(int ceid) {
        Optional<List<Integer>> hostDefined = this.hostReports.reportsFor(ceid);
        if (hostDefined.isPresent()) {
            return hostDefined.get();
        }

        ResolvedCeid definition = this.model.ceids().get(ceid);
        if (definition == null) {
            throw new IllegalArgumentException("未知 CEID: " + ceid + "（设备 " + this.model.name() + "）");
        }
        return definition.reportIds();
    }

    private Asset assetFor(int reportId) {
        Optional<List<Integer>> hostDefined = this.hostReports.svidsOf(reportId);
        if (hostDefined.isPresent()) {
            /* 主机自定义：原样使用，不注入 UNIT_ID */
            return new Asset(hostDefined.get(), null);
        }

        ResolvedReport report = this.model.reports().get(reportId);
        if (report == null) {
            return new Asset(List.of(), null);
        }
        return new Asset(report.svidIds(), report.owner());
    }

    private List<Secs2> valuesOf(Asset asset, long tMs) {
        List<Integer> svidIds = new ArrayList<>(asset.svidIds());

        if (asset.injectableOwner() != null) {
            Integer unitIdSvid = this.unitIdSvidByOwner.get(asset.injectableOwner());
            if (unitIdSvid != null && !svidIds.contains(unitIdSvid)) {
                /* 多单元设备：把单元号放到报告首位，主机侧才不用查表就能区分腔体 */
                svidIds.addFirst(unitIdSvid);
            }
        }

        List<Secs2> values = new ArrayList<>(svidIds.size());
        for (int svidId : svidIds) {
            ResolvedSvid svid = this.model.svids().get(svidId);
            if (svid == null) {
                values.add(Secs2.getBuilder().uint4(0L));
                continue;
            }

            OptionalDouble value = this.values.valueAt(svidId, tMs);
            values.add(Secs2Mapper.of(svid.type(), value.orElse(0.0)));
        }
        return values;
    }

    /**
     * 一个报告的取值来源。
     *
     * @param svidIds          VID 列表
     * @param injectableOwner  可注入 UNIT_ID 的单元名；主机自定义时为 {@code null}
     */
    private record Asset(List<Integer> svidIds, String injectableOwner) {
    }
}
