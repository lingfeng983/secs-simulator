package com.shimizukenta.secssim.core.event;

import java.util.Objects;

/**
 * 事件效果（schema v6）：计划触发时对某个 SVID 施加的值变化。
 *
 * <p>op 与字段的对应关系（多余字段一律拒绝，避免"写错键名静默无效"）：</p>
 * <ul>
 *   <li>{@code set}——{@code value} 必填；可选 {@code decayMs}，写了则从 value
 *       线性衰减回基线曲线；</li>
 *   <li>{@code add} / {@code shift}——{@code delta} 必填，相对基线抬升/下降，
 *       持续生效直到 resume 或热重载；</li>
 *   <li>{@code pulse}——{@code value} 与 {@code holdMs} 必填，瞬时跳变保持
 *       holdMs 后自动回落；</li>
 *   <li>{@code resume}——不带任何字段，清除该 SVID 上的全部活动效果。</li>
 * </ul>
 *
 * @param svid    目标 SVID（绝对 ID）
 * @param op      效果类型
 * @param value   set/pulse 的目标值
 * @param delta   add/shift 的相对量
 * @param holdMs  pulse 的保持时长
 * @param decayMs set 的衰减时长
 */
public record EventEffect(int svid, Op op, Double value, Double delta, Long holdMs, Long decayMs) {

    /** 效果类型。 */
    public enum Op {
        /** 写入固定值（可选衰减回基线）。 */
        SET,
        /** 相对基线抬升（shift 的别名，语义一致）。 */
        ADD,
        /** 瞬时跳变，保持 holdMs 后回落。 */
        PULSE,
        /** 相对基线抬升/下降，持续生效。 */
        SHIFT,
        /** 清除该 SVID 的全部活动效果。 */
        RESUME
    }

    /**
     * 校验并创建。
     *
     * @param svid    目标 SVID
     * @param op      效果类型
     * @param value   目标值
     * @param delta   相对量
     * @param holdMs  保持时长
     * @param decayMs 衰减时长
     */
    public EventEffect {
        if (svid < 1) {
            throw new IllegalArgumentException("effect svid must be >= 1 but was " + svid);
        }
        Objects.requireNonNull(op, "op");
        switch (op) {
            case SET -> {
                requireField(value, "value", op);
                rejectFields(delta, "delta", op);
                rejectFields(holdMs, "holdMs", op);
                if (decayMs != null && decayMs < 0) {
                    throw new IllegalArgumentException("decayMs must be >= 0");
                }
            }
            case ADD, SHIFT -> {
                requireField(delta, "delta", op);
                rejectFields(value, "value", op);
                rejectFields(holdMs, "holdMs", op);
                rejectFields(decayMs, "decayMs", op);
            }
            case PULSE -> {
                requireField(value, "value", op);
                if (holdMs == null || holdMs <= 0) {
                    throw new IllegalArgumentException("pulse 需要 holdMs > 0");
                }
                rejectFields(delta, "delta", op);
                rejectFields(decayMs, "decayMs", op);
            }
            case RESUME -> {
                rejectFields(value, "value", op);
                rejectFields(delta, "delta", op);
                rejectFields(holdMs, "holdMs", op);
                rejectFields(decayMs, "decayMs", op);
            }
        }
    }

    private static void requireField(Number field, String name, Op op) {
        /* NaN 只能这样判：== Double.NaN 恒为 false，原来的写法等于没校验 */
        if (field == null) {
            throw new IllegalArgumentException(op.name().toLowerCase() + " 需要 " + name);
        }
        final double v = field.doubleValue();
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException(op.name().toLowerCase() + " 的 " + name + " 必须是有限数值");
        }
    }

    private static void rejectFields(Number field, String name, Op op) {
        if (field != null) {
            throw new IllegalArgumentException(op.name().toLowerCase() + " 不接受 " + name);
        }
    }
}
