package com.shimizukenta.secssim.core.value;

import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * 让 {@code UNIT_ID} 这个 SVID 永远等于所属单元的编号。
 *
 * <p>为什么需要单独一层：{@code UNIT_ID} 是设备语义，不是用户配的曲线。
 * 一旦某个变量配了值定义，值引擎改走 {@link DataSourceValueSource}，
 * 那里只认识显式配置的编号——主机 S1F3 查 1090 会拿到 0，而 S6F11 里注入的
 * UNIT_ID 却是正确值，同一台设备两个来源给出不同答案。这一层把两类值来源
 * 统一钉在真实单元号上。</p>
 */
public final class UnitIdValueSource implements ValueSource {

    /** 下游值来源。 */
    private final ValueSource inner;

    /** SVID → 单元编号。 */
    private final Map<Integer, Double> unitIds;

    /**
     * 包装一个值来源。
     *
     * @param inner   下游值来源
     * @param unitIds SVID → 单元编号
     */
    public UnitIdValueSource(ValueSource inner, Map<Integer, Double> unitIds) {
        this.inner = Objects.requireNonNull(inner, "inner");
        this.unitIds = Map.copyOf(unitIds);
    }

    @Override
    public OptionalDouble valueAt(int svid, long tMs) {
        Double unitId = this.unitIds.get(svid);
        if (unitId != null) {
            return OptionalDouble.of(unitId.doubleValue());
        }
        return this.inner.valueAt(svid, tMs);
    }

    @Override
    public Set<Integer> svids() {
        return this.inner.svids();
    }

    /**
     * 被包装的下游值来源（诊断与测试用）。
     *
     * @return 下游值来源
     */
    public ValueSource inner() {
        return this.inner;
    }
}
