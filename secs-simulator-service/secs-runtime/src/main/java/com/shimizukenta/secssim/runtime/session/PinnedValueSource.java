package com.shimizukenta.secssim.runtime.session;

import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.shimizukenta.secssim.core.value.ValueSource;

/**
 * 带「设定」覆盖层的值来源（schema v7）。
 *
 * <p>v6 的 {@code EffectValueSource} 有两层：事件效果 + 手动设定。v7 取消了
 * 事件效果（事件只上报、不改值），只剩「设定」——它是<b>调试用</b>的运行态
 * 覆盖：把一个 SVID 钉在某个值上，看主机侧怎么反应。设定不写回配置，
 * 热重载或重启即失效。</p>
 *
 * <p>所有消费点（S6F11 组装、S1F3 应答、Trace、预览）共用同一个
 * {@code ValueSourceHandle}，所以设定对它们一致可见。</p>
 */
public final class PinnedValueSource implements ValueSource {

    private final ValueSource base;

    /** 「设定」钉住的值：绝对 SVID → 值。 */
    private final java.util.Map<Integer, Double> pinned = new ConcurrentHashMap<>();

    /**
     * 创建。
     *
     * @param base 基线值来源
     */
    public PinnedValueSource(ValueSource base) {
        this.base = base;
    }

    /**
     * 基线来源。
     *
     * @return 基线
     */
    public ValueSource base() {
        return this.base;
    }

    /**
     * 钉住一个 SVID 的值（「设定」）。
     *
     * @param svid  绝对 SVID
     * @param value 设定值
     */
    public void pin(int svid, double value) {
        this.pinned.put(svid, value);
    }

    /**
     * 取消设定。
     *
     * @param svid 绝对 SVID
     * @return 原来是否有设定
     */
    public boolean unpin(int svid) {
        return this.pinned.remove(svid) != null;
    }

    /**
     * 是否被钉住。
     *
     * @param svid 绝对 SVID
     * @return 已设定返回 {@code true}
     */
    public boolean isPinned(int svid) {
        return this.pinned.containsKey(svid);
    }

    @Override
    public OptionalDouble valueAt(int svid, long tMs) {
        Double manual = this.pinned.get(svid);
        return manual != null ? OptionalDouble.of(manual) : this.base.valueAt(svid, tMs);
    }

    @Override
    public Set<Integer> svids() {
        return this.base.svids();
    }
}
