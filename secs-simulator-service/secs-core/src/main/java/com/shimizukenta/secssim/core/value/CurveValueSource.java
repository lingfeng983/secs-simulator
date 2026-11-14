package com.shimizukenta.secssim.core.value;

import java.util.OptionalDouble;
import java.util.Set;

/**
 * 用 {@link ValueStore} 里的一条条曲线作为值来源。
 *
 * @param store 值存储
 */
public record CurveValueSource(ValueStore store) implements ValueSource {

    @Override
    public OptionalDouble valueAt(int svid, long tMs) {
        return this.store.valueAt(svid, tMs);
    }

    @Override
    public Set<Integer> svids() {
        return this.store.boundSvids();
    }
}
