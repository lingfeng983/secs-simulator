package com.shimizukenta.secssim.core.value;

import com.shimizukenta.secssim.core.cycle.CycleClock;
import com.shimizukenta.secssim.core.curve.Curve;
import com.shimizukenta.secssim.core.curve.Rng;
import com.shimizukenta.secssim.core.template.ExpressionException;

/**
 * 把 {@link DataSource} 在某一时刻算成一个值。
 *
 * <p>整个 v7 值模型只有这一个求值函数：没有叠加层、没有效果队列、没有绑定图。
 * 同一个 {@code (source, 时刻, 时钟状态)} 永远得到同一个结果，因此报文读取、曲线预览、
 * 历史回放三条路径不可能出现分歧（生命周期时钟的启停/冻结是显式输入，不是隐藏状态）。</p>
 */
public final class SourceEvaluator {

    private SourceEvaluator() {
    }

    /**
     * 求数值。
     *
     * <p>文本型来源（{@link DataSource.Text} / {@link DataSource.CycleText}）没有数值语义，
     * 返回 0 —— 它们只用于 Context 的报文编码，调用方应走
     * {@link com.shimizukenta.secssim.core.context.ContextTable}。</p>
     *
     * @param source 值定义
     * @param tMs    时刻
     * @param clock  周期时钟
     * @param rng    该数据项的确定性噪声源
     * @return 数值
     */
    public static double numeric(DataSource source, long tMs, CycleClock clock, Rng rng) {
        return switch (source) {
            case DataSource.Const constant -> constant.value();
            case DataSource.Text ignored -> 0.0;
            case DataSource.CycleNumber step -> step.valueAt(clock.indexAt(tMs));
            case DataSource.CycleText ignored -> 0.0;
            case DataSource.CurveSource curve -> {
                long local = localTime(curve.clock(), tMs, clock);
                double value = curve.curve().at(local, rng);
                yield curve.transform() == null
                        ? value
                        : curve.transform().evaluate(name -> switch (name) {
                            case "V", "v" -> value;
                            case "t" -> local / 1000.0;
                            default -> throw new ExpressionException("值变换只允许引用 V 与 t：" + name);
                        });
            }
            case DataSource.ExprSource expr -> {
                long local = localTime(expr.clock(), tMs, clock);
                yield expr.expr().evaluate(name -> {
                    if ("t".equals(name)) {
                        return local / 1000.0;
                    }
                    Curve variable = expr.vars().get(name);
                    if (variable == null) {
                        throw new ExpressionException("表达式引用了未定义的局部曲线：" + name);
                    }
                    return variable.at(local, rng.derive("var:" + name));
                });
            }
        };
    }

    /**
     * 求文本（Context 专用）。
     *
     * @param source 值定义
     * @param tMs    时刻
     * @param clock  周期时钟
     * @return 文本
     */
    public static String text(DataSource source, long tMs, CycleClock clock) {
        return switch (source) {
            case DataSource.Text text -> text.value();
            case DataSource.CycleText text -> text.textAt(clock.indexAt(tMs));
            case DataSource.Const constant -> format(constant.value());
            /* core 兜底（不改行为）：CurveSource / ExprSource / CycleNumber 等数值型来源
               落到这里返回空串。正常配置不会走到这里——SessionConfig.compile() 已在
               config 层用 CONTEXT_SOURCE_TYPE_MISMATCH 拒绝不相容的组合；这里只防
               直接构造 ContextTable.Def 的调用方。 */
            default -> "";
        };
    }

    /**
     * 数值转文本：整数不带小数点（与报文 ASCII 编码同规则）。
     *
     * @param value 数值
     * @return 文本
     */
    public static String format(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    /**
     * 把绝对时刻换算成该来源的时间自变量。
     *
     * @param clockKind 来源的时钟
     * @param tMs       绝对时刻
     * @param clock     周期时钟
     * @return 局部时间
     */
    public static long localTime(DataSource.Clock clockKind, long tMs, CycleClock clock) {
        return switch (clockKind) {
            case PHASE -> clock.phaseAt(tMs);
            /* ABSOLUTE 走生命周期锚：无装饰器时 = tMs（时钟零点起算，3.1 语义不变）；
               有 LifecycleCycleClock 时 = t - startedAt——ABSOLUTE 曲线随周期重锚，
               重启周期即重放（2026-11-16 设计 D11）。 */
            case ABSOLUTE -> clock.elapsedAt(tMs);
            case CYCLE -> clock.indexAt(tMs);
        };
    }
}
