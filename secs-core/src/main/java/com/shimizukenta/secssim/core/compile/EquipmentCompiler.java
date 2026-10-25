package com.shimizukenta.secssim.core.compile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.shimizukenta.secssim.core.domain.CeidDef;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.core.domain.GlobalCeidDef;
import com.shimizukenta.secssim.core.domain.GlobalSvidDef;
import com.shimizukenta.secssim.core.domain.IdSpacePolicy;
import com.shimizukenta.secssim.core.domain.ReportDef;
import com.shimizukenta.secssim.core.domain.SvidDef;
import com.shimizukenta.secssim.core.domain.UnitSpec;

/**
 * 把槽位相对的设备配置编译成绝对 ID 模型，并一次性报出全部违规。
 *
 * <p>多单元设备的失败模式几乎都集中在号段上：相邻单元的段重叠、事件挂错单元、
 * 报告引用了别家的变量。这些错在运行期表现为"主机收到的数据莫名其妙"，
 * 极难定位。因此全部在编译期拦下。
 * </p>
 */
public final class EquipmentCompiler {

    private final EquipmentSpec spec;
    private final List<Issue> issues = new ArrayList<>();

    private final Map<Integer, ResolvedSvid> svids = new LinkedHashMap<>();
    private final Map<Integer, ResolvedReport> reports = new LinkedHashMap<>();
    private final Map<Integer, ResolvedCeid> ceids = new LinkedHashMap<>();
    private final Map<Integer, String> unitByCeid = new LinkedHashMap<>();

    private final Map<String, ResolvedSvid> globalSvidByName = new HashMap<>();
    private final Map<String, ResolvedReport> reportByQualified = new HashMap<>();
    private final Map<String, List<ResolvedReport>> reportByShort = new HashMap<>();

    private final Set<Integer> unitIdsSeen = new HashSet<>();
    private final Set<String> unitNamesSeen = new HashSet<>();

    private EquipmentCompiler(EquipmentSpec spec) {
        this.spec = spec;
    }

    /**
     * 编译并校验。
     *
     * @param spec 设备配置
     * @return 编译结果（含全部问题）
     */
    public static CompilationResult compile(EquipmentSpec spec) {
        return new EquipmentCompiler(spec).run();
    }

    private CompilationResult run() {
        if (this.spec.units().isEmpty()) {
            this.issues.add(Issue.error("NO_UNITS", "设备没有任何单元: " + this.spec.name()));
        }

        this.collectGlobalSvids();

        int position = 0;
        for (UnitSpec unit : this.spec.units()) {
            position++;
            this.compileUnit(unit, position);
        }

        this.compileGlobalCeids();

        ResolvedEquipment equipment = new ResolvedEquipment(
                this.spec.name(), this.spec.deviceId(),
                this.svids, this.reports, this.ceids, this.unitByCeid);

        return new CompilationResult(equipment, this.issues);
    }

    /**
     * 收集设备级（global）SVID 定义。
     *
     * <p><b>first-wins 语义</b>：{@code globalSvidByName} 以 {@code putIfAbsent} 注册，
     * 因此当多个 global 定义<b>重名</b>时，以先注册的模板为准，后到的同名定义被静默忽略
     * （既不报错也不覆盖）。这是刻意取舍：名字只是供人引用的别名，真正保证"一个 SVID 只有
     * 一个生产者"的是 <b>ID 唯一性</b>——重复 ID 会在上方以 {@code DUPLICATE_SVID} 显式报出；
     * 此外运行期模板图构建也会以单写者规则（{@code DUPLICATE_SVID}）拒绝重复绑定，作为兜底。
     * </p>
     */
    private void collectGlobalSvids() {
        IdSpacePolicy policy = this.spec.idSpace();

        for (GlobalSvidDef def : this.spec.globalSvids()) {
            if (policy.svidReserved(def.id())) {
                this.reserved("SVID", def.id(), def.name());
            }

            ResolvedSvid resolved = new ResolvedSvid(def.id(), ResolvedSvid.GLOBAL_OWNER, def.name(),
                    def.type(), "");
            ResolvedSvid previous = this.svids.putIfAbsent(def.id(), resolved);

            if (previous != null) {
                this.issues.add(Issue.error("DUPLICATE_SVID",
                        "SVID " + def.id() + " 重复: " + previous.owner() + "." + previous.slot()
                                + " 与 " + ResolvedSvid.GLOBAL_OWNER + "." + def.name()));
            }

            // first-wins：按名索引，重名时保留先注册者（ID 重复才在上面报 DUPLICATE_SVID）。
            // 重名本身不阻断启动，但必须留一条提示：报告/CEID 按名引用时，
            // 后定义的同名项会被悄悄忽略，现场只会看到“引用的好像是另一个值”。
            if (this.globalSvidByName.putIfAbsent(def.name(), resolved) != null) {
                this.issues.add(Issue.warning("DUPLICATE_GLOBAL_SVID_NAME",
                        "设备级 SVID 名称重复: " + def.name() + "（按先注册者生效，后定义的同名项被忽略）"));
            }
        }
    }

    private void compileUnit(UnitSpec unit, int position) {
        IdSpacePolicy policy = this.spec.idSpace();

        if (!this.unitIdsSeen.add(unit.unitId().value())) {
            this.issues.add(Issue.error("DUPLICATE_UNIT_ID", "unitId 重复: " + unit.unitId().value()));
        }
        if (!this.unitNamesSeen.add(unit.name())) {
            this.issues.add(Issue.warning("DUPLICATE_UNIT_NAME", "单元名重复: " + unit.name()));
        }

        unit.duplicatedSvidSlots().forEach(slot -> this.issues.add(Issue.error("DUPLICATE_SLOT",
                "单元 " + unit.name() + " 的 SVID 槽位重复: " + slot)));
        unit.duplicatedReportSlots().forEach(slot -> this.issues.add(Issue.error("DUPLICATE_SLOT",
                "单元 " + unit.name() + " 的报告槽位重复: " + slot)));
        unit.duplicatedCeidSlots().forEach(slot -> this.issues.add(Issue.error("DUPLICATE_SLOT",
                "单元 " + unit.name() + " 的 CEID 槽位重复: " + slot)));

        if (unit.maxSvidOffset() >= policy.svidStride()) {
            this.issues.add(Issue.error("STRIDE_TOO_SMALL_SVID",
                    "单元 " + unit.name() + " 的 SVID 最大偏移 " + unit.maxSvidOffset()
                            + " 已达到或超过段步长 " + policy.svidStride() + "，相邻单元会重叠"));
        }
        if (unit.maxReportOffset() >= policy.reportStride()) {
            this.issues.add(Issue.error("STRIDE_TOO_SMALL_REPORT",
                    "单元 " + unit.name() + " 的报告最大偏移 " + unit.maxReportOffset()
                            + " 已达到或超过段步长 " + policy.reportStride()));
        }
        if (unit.maxCeidOffset() >= policy.ceidStride()) {
            this.issues.add(Issue.error("STRIDE_TOO_SMALL_CEID",
                    "单元 " + unit.name() + " 的 CEID 最大偏移 " + unit.maxCeidOffset()
                            + " 已达到或超过段步长 " + policy.ceidStride()));
        }

        if (unit.injectUnitId() && !unit.hasUnitIdSvid()) {
            this.issues.add(Issue.error("MISSING_UNIT_ID_SVID",
                    "单元 " + unit.name() + " 开启了 injectUnitId，但没有定义 "
                            + UnitSpec.UNIT_ID_SLOT + " 槽位"));
        }

        int svidBase = policy.svidBaseFor(position);
        int reportBase = policy.reportBaseFor(position);
        int ceidBase = policy.ceidBaseFor(position);

        this.reserveDuplicateOffsets(unit.name(), "SVID", unit.svids().stream().map(SvidDef::offset).toList());

        for (SvidDef def : unit.svids()) {
            int id = def.resolve(svidBase);
            if (policy.svidReserved(id)) {
                this.reserved("SVID", id, unit.name() + "." + def.slot());
            }

            ResolvedSvid resolved = new ResolvedSvid(id, unit.name(), def.slot(), def.type(), def.unitSymbol());
            ResolvedSvid previous = this.svids.putIfAbsent(id, resolved);

            if (previous != null) {
                this.issues.add(Issue.error("DUPLICATE_SVID",
                        "SVID " + id + " 重复: " + previous.owner() + "." + previous.slot()
                                + " 与 " + unit.name() + "." + def.slot()));
            }
        }

        for (ReportDef def : unit.reports()) {
            int id = def.resolve(reportBase);
            if (policy.reportReserved(id)) {
                this.reserved("RPTID", id, unit.name() + "." + def.slot());
            }

            List<Integer> svidIds = new ArrayList<>();
            for (String name : def.svidNames()) {
                Optional<SvidDef> local = unit.findSvid(name);
                if (local.isPresent()) {
                    svidIds.add(local.get().resolve(svidBase));
                } else if (this.globalSvidByName.containsKey(name)) {
                    svidIds.add(this.globalSvidByName.get(name).id());
                } else {
                    this.issues.add(Issue.error("UNKNOWN_SVID_REF",
                            "单元 " + unit.name() + " 的报告 " + def.slot() + " 引用了不存在的 SVID: " + name));
                }
            }

            ResolvedReport resolved = new ResolvedReport(id, unit.name(), def.slot(), svidIds);
            ResolvedReport previous = this.reports.putIfAbsent(id, resolved);

            if (previous != null) {
                this.issues.add(Issue.error("DUPLICATE_REPORT",
                        "RPTID " + id + " 重复: " + previous.owner() + "." + previous.slot()
                                + " 与 " + unit.name() + "." + def.slot()));
            }

            this.reportByQualified.put(unit.name() + "." + def.slot(), resolved);
            this.reportByShort.computeIfAbsent(def.slot(), k -> new ArrayList<>()).add(resolved);
        }

        for (CeidDef def : unit.ceids()) {
            int id = def.resolve(ceidBase);
            if (policy.ceidReserved(id)) {
                this.reserved("CEID", id, unit.name() + "." + def.slot());
            }

            List<Integer> reportIds = new ArrayList<>();
            for (String name : def.reportNames()) {
                Optional<ReportDef> local = unit.findReport(name);
                if (local.isPresent()) {
                    reportIds.add(local.get().resolve(reportBase));
                } else {
                    this.issues.add(Issue.error("UNKNOWN_REPORT_REF",
                            "单元 " + unit.name() + " 的 CEID " + def.slot() + " 引用了不存在的报告: " + name));
                }
            }

            ResolvedCeid resolved = new ResolvedCeid(id, unit.name(), def.slot(), reportIds);
            ResolvedCeid previous = this.ceids.putIfAbsent(id, resolved);

            if (previous != null) {
                this.issues.add(Issue.error("DUPLICATE_CEID",
                        "CEID " + id + " 重复: " + previous.owner() + "." + previous.slot()
                                + " 与 " + unit.name() + "." + def.slot()));
            }

            this.unitByCeid.put(id, unit.name());
        }
    }

    private void compileGlobalCeids() {
        IdSpacePolicy policy = this.spec.idSpace();

        for (GlobalCeidDef def : this.spec.globalCeids()) {
            if (policy.ceidReserved(def.id())) {
                this.reserved("CEID", def.id(), ResolvedSvid.GLOBAL_OWNER + "." + def.name());
            }

            List<Integer> reportIds = new ArrayList<>();
            for (String name : def.reportNames()) {
                ResolvedReport report = this.findReport(name);
                if (report == null) {
                    this.issues.add(Issue.error("UNKNOWN_REPORT_REF",
                            "设备级 CEID " + def.name() + " 引用了不存在的报告: " + name
                                    + "（跨单元引用请写成 单元名.报告名）"));
                } else {
                    reportIds.add(report.id());
                }
            }

            ResolvedCeid resolved = new ResolvedCeid(def.id(), ResolvedSvid.GLOBAL_OWNER, def.name(), reportIds);
            ResolvedCeid previous = this.ceids.putIfAbsent(def.id(), resolved);

            if (previous != null) {
                this.issues.add(Issue.error("DUPLICATE_CEID",
                        "CEID " + def.id() + " 重复: " + previous.owner() + "." + previous.slot()
                                + " 与 " + ResolvedSvid.GLOBAL_OWNER + "." + def.name()));
            }

            this.unitByCeid.put(def.id(), ResolvedSvid.GLOBAL_OWNER);
        }
    }

    private ResolvedReport findReport(String name) {
        ResolvedReport qualified = this.reportByQualified.get(name);
        if (qualified != null) {
            return qualified;
        }
        List<ResolvedReport> candidates = this.reportByShort.getOrDefault(name, List.of());
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        if (candidates.size() > 1) {
            this.issues.add(Issue.error("AMBIGUOUS_REPORT_REF",
                    "报告名 " + name + " 在多个单元中存在，请写成 单元名.报告名"));
        }
        return null;
    }

    private void reserveDuplicateOffsets(String unitName, String kind, List<Integer> offsets) {
        Set<Integer> seen = new HashSet<>();
        for (int offset : offsets) {
            if (!seen.add(offset)) {
                this.issues.add(Issue.error("DUPLICATE_OFFSET",
                        "单元 " + unitName + " 的 " + kind + " 偏移重复: " + offset));
            }
        }
    }

    private void reserved(String kind, int id, String owner) {
        this.issues.add(Issue.error("RESERVED_ID",
                kind + " " + id + "（" + owner + "）落在保留区间，禁止占用"));
    }
}
