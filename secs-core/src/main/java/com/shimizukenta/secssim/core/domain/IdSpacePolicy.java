package com.shimizukenta.secssim.core.domain;

import java.util.List;

/**
 * ID 段分配策略。
 *
 * <p>多单元设备最容易死在号段撞车上：第 i 个单元的绝对 ID = 段起点 + (i-1) × 步长 + 槽位偏移。
 * 因此步长必须大于该单元模板里最大的槽位偏移，否则相邻单元必然重叠。
 * 这条约束由 {@code EquipmentCompiler} 在编译期强制。
 * </p>
 *
 * <p>注意：这里的 {@code unitPosition} 是单元在列表中的<b>位置</b>（1 起），
 * 不是 {@link UnitId}。编号可以跳号，段只按位置推进。
 * </p>
 *
 * @param svidBase       SVID 段起点
 * @param ceidBase       CEID 段起点
 * @param reportBase     RPTID 段起点
 * @param svidStride     SVID 段步长
 * @param ceidStride     CEID 段步长
 * @param reportStride   RPTID 段步长
 * @param reservedSvid   保留的 SVID 区间（不得占用）
 * @param reservedCeid   保留的 CEID 区间
 * @param reservedReport 保留的 RPTID 区间
 */
public record IdSpacePolicy(int svidBase, int ceidBase, int reportBase,
                            int svidStride, int ceidStride, int reportStride,
                            List<IdRange> reservedSvid, List<IdRange> reservedCeid,
                            List<IdRange> reservedReport) {

    /**
     * 校验并创建。
     *
     * @param svidBase       SVID 段起点
     * @param ceidBase       CEID 段起点
     * @param reportBase     RPTID 段起点
     * @param svidStride     SVID 段步长
     * @param ceidStride     CEID 段步长
     * @param reportStride   RPTID 段步长
     * @param reservedSvid   保留 SVID 区间
     * @param reservedCeid   保留 CEID 区间
     * @param reservedReport 保留 RPTID 区间
     */
    public IdSpacePolicy {
        if (svidBase < 1 || ceidBase < 1 || reportBase < 1) {
            throw new IllegalArgumentException("id base must be >= 1");
        }
        if (svidStride < 1 || ceidStride < 1 || reportStride < 1) {
            throw new IllegalArgumentException("id stride must be >= 1");
        }
        reservedSvid = List.copyOf(reservedSvid == null ? List.of() : reservedSvid);
        reservedCeid = List.copyOf(reservedCeid == null ? List.of() : reservedCeid);
        reservedReport = List.copyOf(reservedReport == null ? List.of() : reservedReport);
    }

    /**
     * 默认策略：SVID 1000+ / CEID 2000+ / RPTID 3000+，步长 100，1..999 保留给标准消息。
     *
     * @return 默认策略
     */
    public static IdSpacePolicy defaults() {
        List<IdRange> reserved = List.of(new IdRange(1, 999));
        return new IdSpacePolicy(1000, 2000, 3000, 100, 100, 100, reserved, reserved, reserved);
    }

    /**
     * 第 {@code position} 个单元的 SVID 段起点。
     *
     * @param position 单元位置（1 起）
     * @return 段起点
     */
    public int svidBaseFor(int position) {
        return this.svidBase + (requirePositive(position) - 1) * this.svidStride;
    }

    /**
     * 第 {@code position} 个单元的 CEID 段起点。
     *
     * @param position 单元位置（1 起）
     * @return 段起点
     */
    public int ceidBaseFor(int position) {
        return this.ceidBase + (requirePositive(position) - 1) * this.ceidStride;
    }

    /**
     * 第 {@code position} 个单元的 RPTID 段起点。
     *
     * @param position 单元位置（1 起）
     * @return 段起点
     */
    public int reportBaseFor(int position) {
        return this.reportBase + (requirePositive(position) - 1) * this.reportStride;
    }

    /**
     * SVID 是否落在保留区间。
     *
     * @param id 绝对 ID
     * @return 命中返回 {@code true}
     */
    public boolean svidReserved(int id) {
        return reservedSvid.stream().anyMatch(r -> r.contains(id));
    }

    /**
     * CEID 是否落在保留区间。
     *
     * @param id 绝对 ID
     * @return 命中返回 {@code true}
     */
    public boolean ceidReserved(int id) {
        return reservedCeid.stream().anyMatch(r -> r.contains(id));
    }

    /**
     * RPTID 是否落在保留区间。
     *
     * @param id 绝对 ID
     * @return 命中返回 {@code true}
     */
    public boolean reportReserved(int id) {
        return reservedReport.stream().anyMatch(r -> r.contains(id));
    }

    private static int requirePositive(int position) {
        if (position < 1) {
            throw new IllegalArgumentException("unit position must be >= 1 but was " + position);
        }
        return position;
    }
}
