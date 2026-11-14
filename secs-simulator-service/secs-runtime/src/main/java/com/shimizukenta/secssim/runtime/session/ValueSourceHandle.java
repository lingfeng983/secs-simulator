package com.shimizukenta.secssim.runtime.session;

import com.shimizukenta.secssim.core.value.ValueSource;

/**
 * 可热替换的值来源委托。
 *
 * <p>S6F11 组装器、Trace 采样、曲线预览都在构造时拿到同一个 {@link ValueSource}
 * 引用；热生效要换模板图就得让所有持有者"看到"新来源——
 * 与其给三处各加更新入口，不如让它们持有这个委托，
 * 换图时只换委托的内部实现。</p>
 */
final class ValueSourceHandle implements ValueSource {

    private volatile ValueSource delegate;

    ValueSourceHandle(ValueSource initial) {
        this.delegate = initial;
    }

    /**
     * 替换内部实现。
     *
     * @param next 新的值来源
     */
    void set(ValueSource next) {
        this.delegate = next;
    }

    @Override
    public java.util.Set<Integer> svids() {
        return this.delegate.svids();
    }

    @Override
    public java.util.OptionalDouble valueAt(int svid, long tMs) {
        return this.delegate.valueAt(svid, tMs);
    }
}
