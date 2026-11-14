package com.shimizukenta.secssim.core.compile;

import com.shimizukenta.secssim.core.domain.ValueType;

/**
 * 已解析出绝对 ID 的 SVID。
 *
 * @param id         绝对 SVID
 * @param owner      归属单元名，设备级为 {@code GLOBAL}
 * @param slot       符号名
 * @param type       类型
 * @param unitSymbol 工程单位（可空串），S1F12 会把它报给主机
 */
public record ResolvedSvid(int id, String owner, String slot, ValueType type, String unitSymbol) {

    /** 设备级共享项的所有者标记。 */
    public static final String GLOBAL_OWNER = "GLOBAL";

    /**
     * 校验并创建。
     *
     * @param id         绝对 SVID
     * @param owner      归属
     * @param slot       符号名
     * @param type       类型
     * @param unitSymbol 工程单位
     */
    public ResolvedSvid {
        unitSymbol = unitSymbol == null ? "" : unitSymbol;
    }

    /**
     * 人类可读的变量名：单元归属的变量带上单元前缀。
     *
     * <p>多单元设备里两个腔体都会有 {@code TEMP_PV}，若原样上报，
     * 主机侧看到两个同名变量却无法区分。加上单元前缀（{@code CH1_TEMP_PV}）后，
     * 名称本身就带上了归属信息。设备级共享变量保持原名。
     * </p>
     *
     * @return 变量名
     */
    public String svName() {
        return GLOBAL_OWNER.equals(this.owner) ? this.slot : this.owner + "_" + this.slot;
    }
}
