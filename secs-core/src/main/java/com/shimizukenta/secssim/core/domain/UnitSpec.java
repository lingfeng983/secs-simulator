package com.shimizukenta.secssim.core.domain;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 一个单元的完整定义（全部槽位相对）。
 *
 * @param unitId       单元编号
 * @param name         单元名（如 {@code CH1}）
 * @param svids        状态变量
 * @param reports      报告
 * @param ceids        采集事件
 * @param injectUnitId 是否强制在事件报告里注入 {@code UNIT_ID}
 */
public record UnitSpec(UnitId unitId, String name, List<SvidDef> svids, List<ReportDef> reports,
                       List<CeidDef> ceids, boolean injectUnitId) {

    /**
     * 强制注入单元号时使用的槽位名。
     */
    public static final String UNIT_ID_SLOT = "UNIT_ID";

    /**
     * 校验并创建。
     *
     * @param unitId       单元编号
     * @param name         单元名
     * @param svids        状态变量
     * @param reports      报告
     * @param ceids        采集事件
     * @param injectUnitId 是否注入单元号
     */
    public UnitSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("unit name must not be blank");
        }
        svids = List.copyOf(svids == null ? List.of() : svids);
        reports = List.copyOf(reports == null ? List.of() : reports);
        ceids = List.copyOf(ceids == null ? List.of() : ceids);
    }

    /**
     * 按符号名查找 SVID。
     *
     * @param slot 符号名
     * @return 定义
     */
    public Optional<SvidDef> findSvid(String slot) {
        return this.svids.stream().filter(d -> d.slot().equals(slot)).findFirst();
    }

    /**
     * 按符号名查找报告。
     *
     * @param slot 符号名
     * @return 定义
     */
    public Optional<ReportDef> findReport(String slot) {
        return this.reports.stream().filter(d -> d.slot().equals(slot)).findFirst();
    }

    /**
     * 按符号名查找 CEID。
     *
     * @param slot 符号名
     * @return 定义
     */
    public Optional<CeidDef> findCeid(String slot) {
        return this.ceids.stream().filter(d -> d.slot().equals(slot)).findFirst();
    }

    /**
     * 是否定义了 {@code UNIT_ID} 槽位。
     *
     * @return 已定义返回 {@code true}
     */
    public boolean hasUnitIdSvid() {
        return this.findSvid(UNIT_ID_SLOT).isPresent();
    }

    /**
     * 返回重复的槽位名（SVID / 报告 / CEID 各自命名空间内）。
     *
     * @return 重复的槽位名
     */
    public Set<String> duplicatedSvidSlots() {
        return duplicates(this.svids.stream().map(SvidDef::slot).toList());
    }

    /**
     * 返回重复的报告槽位名。
     *
     * @return 重复的槽位名
     */
    public Set<String> duplicatedReportSlots() {
        return duplicates(this.reports.stream().map(ReportDef::slot).toList());
    }

    /**
     * 返回重复的 CEID 槽位名。
     *
     * @return 重复的槽位名
     */
    public Set<String> duplicatedCeidSlots() {
        return duplicates(this.ceids.stream().map(CeidDef::slot).toList());
    }

    /**
     * 最大 SVID 段内偏移。
     *
     * @return 最大偏移，无定义时返回 0
     */
    public int maxSvidOffset() {
        return this.svids.stream().mapToInt(SvidDef::offset).max().orElse(0);
    }

    /**
     * 最大报告段内偏移。
     *
     * @return 最大偏移，无定义时返回 0
     */
    public int maxReportOffset() {
        return this.reports.stream().mapToInt(ReportDef::offset).max().orElse(0);
    }

    /**
     * 最大 CEID 段内偏移。
     *
     * @return 最大偏移，无定义时返回 0
     */
    public int maxCeidOffset() {
        return this.ceids.stream().mapToInt(CeidDef::offset).max().orElse(0);
    }

    private static Set<String> duplicates(List<String> values) {
        return values.stream()
                .collect(Collectors.groupingBy(v -> v, Collectors.counting()))
                .entrySet().stream()
                .filter(e -> e.getValue() > 1L)
                .map(java.util.Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }
}
