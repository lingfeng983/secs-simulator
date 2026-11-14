package com.shimizukenta.secssim.runtime.session;

import java.util.OptionalDouble;
import java.util.Set;

import com.shimizukenta.secssim.core.event.EventEffect;
import com.shimizukenta.secssim.core.value.ValueSource;

/**
 * 带<b>事件效果层</b>的值来源（schema v6）。
 *
 * <p>求值 = 基线曲线 + 该 SVID 上仍然活动的事件效果。所有消费点
 * （S6F11 组装、S1F3 应答、Trace、预览）共用同一个
 * {@code ValueSourceHandle}，效果因此对它们全部一致可见。</p>
 *
 * <p>组合语义（按施加顺序逐个叠加）：</p>
 * <ul>
 *   <li>{@code shift/add}——当前值加 delta；</li>
 *   <li>{@code set}——当前值改写为 value；带 decayMs 时向基线线性回归，
 *       decayMs 后效果失效、自然回到基线；</li>
 *   <li>{@code pulse}——改写为 value，holdMs 后失效；</li>
 *   <li>施加顺序靠后的效果覆盖靠前的（set 之后的 shift 在 set 之上叠加）。</li>
 * </ul>
 */
public final class EffectValueSource implements ValueSource {

    private final ValueSource base;
    private final EventEffectStore store;
    /** 「设定」钉住的值（设备页·状态变量）：优先级最高,覆盖曲线与事件效果,直到取消或热重载。 */
    private final java.util.Map<Integer, Double> pinned = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 创建。
     *
     * @param base  基线值来源
     * @param store 效果记录
     */
    public EffectValueSource(ValueSource base, EventEffectStore store) {
        this.base = base;
        this.store = store;
    }

    /**
     * 基线来源（预览模拟需要在其上叠加<b>模拟的</b>效果而非运行时效果）。
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
        if (manual != null) {
            return OptionalDouble.of(manual);
        }
        OptionalDouble baseValue = this.base.valueAt(svid, tMs);
        if (baseValue.isEmpty()) {
            return baseValue;
        }
        if (!this.store.hasActive(svid, tMs)) {
            return baseValue;
        }

        double value = baseValue.getAsDouble();
        for (EventEffectStore.Applied entry : this.store.activeAt(svid, tMs)) {
            EventEffect effect = entry.effect();
            double before = value;
            value = switch (effect.op()) {
                case SET -> {
                    Double target = effect.value();
                    Long decay = effect.decayMs();
                    if (decay == null || decay <= 0L) {
                        yield target;
                    }
                    /* 从施加时刻的值向"当时基线"线性回归:进度 0 → target,1 → 基线。
                       基线取本效果施加前的组合值——先前 shift 抬升过的量在衰减结束后保留。 */
                    long elapsed = tMs - entry.tAppliedMs();
                    double progress = Math.min(1.0, elapsed / (double) decay);
                    yield target + (before - target) * progress;
                }
                case ADD, SHIFT -> value + effect.delta();
                case PULSE -> effect.value();
                case RESUME -> value;
            };
        }
        return OptionalDouble.of(value);
    }

    @Override
    public Set<Integer> svids() {
        return this.base.svids();
    }
}
