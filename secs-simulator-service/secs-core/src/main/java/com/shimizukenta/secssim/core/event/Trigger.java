package com.shimizukenta.secssim.core.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.shimizukenta.secssim.core.curve.Rng;
import com.shimizukenta.secssim.core.template.Expression;

/**
 * 触发规则。
 *
 * <p>只回答"在 (from, to] 区间内，它应该在哪些时刻发生"。
 * 注意区间是<b>左开右闭</b>：连续调用 {@code between(t0,t1)}、{@code between(t1,t2)}
 * 不会重复触发边界上的事件。
 * </p>
 */
public sealed interface Trigger permits Trigger.At, Trigger.Periodic, Trigger.OnCondition, Trigger.AfterEvent {

    /**
     * 计算区间内应触发的时刻。
     *
     * @param fromMs 起始（不含）
     * @param toMs   结束（含）
     * @param rng    噪声源（周期性触发的抖动用它派生）
     * @param planId 计划标识（用于抖动派生，保证同一计划同一编号抖动一致）
     * @return 时刻列表（升序）
     */
    List<Long> fireTimes(long fromMs, long toMs, Rng rng, String planId);

    /**
     * 一次性触发。
     *
     * @param atMs 绝对时刻（相对仿真起点）
     */
    record At(long atMs) implements Trigger {

        @Override
        public List<Long> fireTimes(long fromMs, long toMs, Rng rng, String planId) {
            return (fromMs < this.atMs && this.atMs <= toMs) ? List.of(this.atMs) : List.of();
        }
    }

    /**
     * 条件触发。
     *
     * <p>条件表达式由运行时每 tick 求值。{@link #fireTimes} 返回空列表：
     * 条件触发不可能只凭时间区间算出来，去抖状态属于运行时
     * （见 {@link ConditionDebouncer}）。</p>
     *
     * @param expression 条件表达式，结果非 0 表示成立；变量支持 {@code t} 与 {@code svid.<id>}
     * @param debounceMs 条件必须连续成立的毫秒数
     */
    record OnCondition(Expression expression, long debounceMs) implements Trigger {

        /**
         * 校验并创建。
         *
         * @param expression 条件表达式
         * @param debounceMs 去抖毫秒数
         */
        public OnCondition {
            Objects.requireNonNull(expression, "expression");
            if (debounceMs < 0L) {
                throw new IllegalArgumentException("debounceMs must be >= 0 but was " + debounceMs);
            }
        }

        @Override
        public List<Long> fireTimes(long fromMs, long toMs, Rng rng, String planId) {
            return List.of();
        }
    }

    /**
     * 事件链：源计划触发后延迟 {@code delayMs} 再触发本计划。
     *
     * <p>与 OnCondition 一样，{@link #fireTimes} 返回空列表：
     * 它依赖“源事件已经发生”这个运行时事实，无法只凭时间区间算出来。</p>
     *
     * @param sourcePlan 源计划标识
     * @param delayMs    延迟毫秒数（≥ 0）
     */
    record AfterEvent(String sourcePlan, long delayMs) implements Trigger {

        /**
         * 校验并创建。
         *
         * @param sourcePlan 源计划标识
         * @param delayMs    延迟毫秒数
         */
        public AfterEvent {
            if (sourcePlan == null || sourcePlan.isBlank()) {
                throw new IllegalArgumentException("sourcePlan must not be blank");
            }
            if (delayMs < 0L) {
                throw new IllegalArgumentException("delayMs must be >= 0 but was " + delayMs);
            }
        }

        @Override
        public List<Long> fireTimes(long fromMs, long toMs, Rng rng, String planId) {
            return List.of();
        }
    }

    /**
     * 周期触发。
     *
     * <p>第 {@code k} 次发生在 {@code k × period}（k 从 1 开始），
     * 若 {@code jitterMs > 0}，再叠加一个由 {@code (planId, k)} 派生的确定性抖动。
     * 抖动是确定性的，因此回放与预览能重现同样的时序。
     * </p>
     *
     * <p>无状态：每次调用只回答"这个窗口内有哪些时刻"，不记忆上次触发、也不补偿
     * 迟到的窗口（迟到的按当前窗口如实计算，不补发历史）。相邻窗口
     * {@code (t0,t1]}、{@code (t1,t2]} 拼接后恰好覆盖每个事件一次，不重不漏——
     * 这也是回放与并发消费安全的前提。
     * </p>
     *
     * @param periodMs 周期
     * @param jitterMs 抖动幅度（±）
     */
    record Periodic(long periodMs, long jitterMs) implements Trigger {

        /**
         * 校验并创建。
         *
         * @param periodMs 周期
         * @param jitterMs 抖动
         */
        public Periodic {
            if (periodMs < 1L) {
                throw new IllegalArgumentException("periodMs must be >= 1 but was " + periodMs);
            }
            if (jitterMs < 0L) {
                throw new IllegalArgumentException("jitterMs must be >= 0 but was " + jitterMs);
            }
        }

        @Override
        public List<Long> fireTimes(long fromMs, long toMs, Rng rng, String planId) {
            // 抖动会把真实触发时刻推到 base ± jitter。k 的扫描区间必须按 ±jitter 放宽：
            // 否则当一次抖动把 at 推过窗口边界、而 base 仍落在窗外时，该事件会被相邻两个
            // 窗口同时漏掉（历史上按 base 定区间就踩过这个坑——跨 tick 累加会永久丢事件）。
            // 放宽后由下面的 fromMs < at <= toMs 过滤保证不重不漏，窗口边界天然对齐 at 而非 base。
            long firstK = Math.max(1L, Math.floorDiv(fromMs - this.jitterMs, this.periodMs) + 1L);
            long lastK = Math.floorDiv(toMs + this.jitterMs, this.periodMs);
            /* TreeSet：既排序又去重。抖动 >= 半周期时相邻两个 k 可能落到同一毫秒，
               以前会让同一计划在同一时刻触发两次（主机收到重复 S6F11）。 */
            java.util.TreeSet<Long> times = new java.util.TreeSet<>();

            Rng stream = rng.derive("plan:" + planId);

            for (long k = firstK; k <= lastK; k++) {
                long base = k * this.periodMs;
                long offset = 0L;

                if (this.jitterMs > 0L) {
                    double u = stream.uniform01(k) * 2.0 - 1.0;
                    offset = Math.round(u * this.jitterMs);
                }

                long at = base + offset;
                if (fromMs < at && at <= toMs) {
                    times.add(at);
                }
            }

            return List.copyOf(times);
        }
    }
}
