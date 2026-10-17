package com.shimizukenta.secssim.core.domain;

/**
 * 单元编号。
 *
 * <p>一台设备的多个单元（腔体 / 工位 / 装载口）用这个编号区分。
 * 它与"单元在本设备中的序号"不是一回事：序号决定 ID 段，
 * 编号是写给主机看的语义值（通常通过 UNIT_ID 这个 SVID 上报）。
 * </p>
 *
 * @param value 编号，必须 ≥ 1
 */
public record UnitId(int value) {

    /**
     * 校验并创建。
     *
     * @param value 编号
     */
    public UnitId {
        if (value < 1) {
            throw new IllegalArgumentException("unitId must be >= 1 but was " + value);
        }
    }

    /**
     * 快捷构造。
     *
     * @param value 编号
     * @return 单元编号
     */
    public static UnitId of(int value) {
        return new UnitId(value);
    }

    @Override
    public String toString() {
        return "unit-" + this.value;
    }
}
