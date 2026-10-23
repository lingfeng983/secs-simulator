package com.shimizukenta.secssim.core.domain;

/**
 * 设备常量（ECV，Equipment Constant Value）。
 *
 * <p>对应 SEMI E5 的 S2F13/S2F15 / S2F14/S2F16：主机用它读写"调机参数"
 * （温度设定、超时门限、批量大小之类），是主机上线流程里常见的一步。</p>
 *
 * <p>{@code id} 是绝对 ECID；{@code name} 给 S2F14 用（主机靠它判断
 * "这个编号是什么"）；{@code unit} 可空。</p>
 *
 * <p>值用 {@code double} 承载而不是按 {@link ValueType} 分支：ECV 在标准里
 * 只允许数值型（I8/I1/I2/I4/F8/F4/U8/U1/U2/U4），统一成 double 足以无损表示
 * 到 2^53，而上层写报文时再按 {@link #type()} 转回具体数据项。</p>
 *
 * @param id    绝对 ECID
 * @param name  名称
 * @param type  数据类型
 * @param value 当前值
 * @param unit  单位（可空串）
 */
public record EquipmentConstantDef(int id, String name, ValueType type, double value, String unit) {

    /**
     * 简化构造：不带单位。
     *
     * @param id    绝对 ECID
     * @param name  名称
     * @param type  数据类型
     * @param value 当前值
     */
    public EquipmentConstantDef(int id, String name, ValueType type, double value) {
        this(id, name, type, value, "");
    }

    /**
     * 校验并创建。
     *
     * @param id    绝对 ECID
     * @param name  名称
     * @param type  数据类型
     * @param value 当前值
     * @param unit  单位
     */
    public EquipmentConstantDef {
        if (id < 1) {
            throw new IllegalArgumentException("equipment constant id must be >= 1 but was " + id);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("equipment constant name must not be blank: " + id);
        }
        if (type == null) {
            throw new IllegalArgumentException("equipment constant type must not be null: " + name);
        }
        if (! Double.isFinite(value)) {
            throw new IllegalArgumentException("equipment constant value must be finite: " + name);
        }
        unit = unit == null ? "" : unit;
    }

    /**
     * 换一个值。
     *
     * @param newValue 新值
     * @return 新实例
     */
    public EquipmentConstantDef withValue(double newValue) {
        return new EquipmentConstantDef(this.id, this.name, this.type, newValue, this.unit);
    }
}
