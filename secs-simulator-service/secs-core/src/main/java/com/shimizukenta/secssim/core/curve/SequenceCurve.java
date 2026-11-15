package com.shimizukenta.secssim.core.curve;

import java.util.List;

/**
 * 分段序列：把一条时间轴切成若干段，每段交给一条子曲线，段内时间从 0 重新开始。
 *
 * <p>为什么需要它：真实工艺参数很少只有一种形态——升温是斜坡、保温是恒定或小
 * 幅正弦、抽真空是陡降、收尾是关断。"一条曲线表达"过去只有两条路：pwl 折线
 * （丢掉随机与周期）或组合模板里的 {@code select(t<…, …)} 硬切（时间窗写死在
 * 表达式里，还要额外实例）。分段序列把这件事收回曲线本体：一段一个对象，
 * 时长与波形分开写。</p>
 *
 * <p>求值语义：</p>
 * <ul>
 *   <li>段内用<b>局部时刻</b> {@code t - 段起点} 委托子曲线——现有 ramp / sine /
 *       uniform 都以 0 为原点，天然可以当"段内时间"用；</li>
 *   <li>边界归后一段：{@code t == 段起点} 属于新段；</li>
 *   <li>{@code cyclic = false} 时走过末段就停在末段终点（与 {@link PwlCurve} 一致）；</li>
 *   <li>{@code cyclic = true} 时整条序列循环，周期是各段时长之和；</li>
 *   <li>每段用 {@code rng.derive("segment:" + 序号)} 派生独立噪声源，
 *       否则两段 uniform 会给出<b>完全相同</b>的随机序列。</li>
 * </ul>
 *
 * <p>纯函数约束不变：任意时刻可重算，不依赖"之前跑到哪一段"，
 * 因此 Trace 补点、加速回放、离线重算与其它曲线完全一致。</p>
 *
 * @param segments 按时间顺序排列的段
 * @param cyclic   走完末段后是否从头循环
 */
public record SequenceCurve(List<Segment> segments, boolean cyclic) implements Curve {

    /**
     * 一段：持续时长 + 段内曲线。
     *
     * @param durationMs 段时长（毫秒，&ge; 1）
     * @param curve      段内曲线（自身也以 0 为起点）
     */
    public record Segment(long durationMs, Curve curve) {

        /**
         * 校验并创建。
         *
         * @param durationMs 段时长
         * @param curve      段内曲线
         */
        public Segment {
            if (durationMs < 1L) {
                throw new IllegalArgumentException("segment durationMs must be >= 1 but was " + durationMs);
            }
            if (curve == null) {
                throw new IllegalArgumentException("segment curve must not be null");
            }
        }
    }

    /**
     * 校验并创建。
     *
     * @param segments 段列表
     * @param cyclic   是否整条循环
     */
    public SequenceCurve {
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("sequence curve needs at least one segment");
        }
        segments = List.copyOf(segments);

        long total = 0L;
        for (Segment segment : segments) {
            total += segment.durationMs();
            if (total < 0L) {
                throw new IllegalArgumentException("sequence total duration overflows");
            }
        }
    }

    /**
     * 全序列时长（毫秒）。
     *
     * @return 各段时长之和
     */
    public long totalDurationMs() {
        long total = 0L;
        for (Segment segment : this.segments) {
            total += segment.durationMs();
        }
        return total;
    }

    @Override
    public double at(long tMs, Rng rng) {
        long total = this.totalDurationMs();
        long t = Math.max(0L, tMs);

        if (this.cyclic) {
            t %= total;
        } else if (t >= total) {
            /* 非循环：停在末段终点。末段自己的"到顶停住"语义照旧（ramp/pwl 会保持末值） */
            int lastIndex = this.segments.size() - 1;
            Segment last = this.segments.get(lastIndex);
            return last.curve().at(last.durationMs(), rng.derive("segment:" + lastIndex));
        }

        long start = 0L;
        for (int i = 0; i < this.segments.size(); i++) {
            Segment segment = this.segments.get(i);
            long end = start + segment.durationMs();
            if (t < end) {
                return segment.curve().at(t - start, rng.derive("segment:" + i));
            }
            start = end;
        }

        /* 上面的循环覆盖 [0, total)，到这里说明总时长在计算期间被改写（不会发生）；兜底取末值 */
        Segment last = this.segments.getLast();
        return last.curve().at(last.durationMs(), rng.derive("segment:" + (this.segments.size() - 1)));
    }

    @Override
    public double lowerBound() {
        double lower = Double.POSITIVE_INFINITY;
        for (Segment segment : this.segments) {
            lower = Math.min(lower, segment.curve().lowerBound());
        }
        return lower;
    }

    @Override
    public double upperBound() {
        double upper = Double.NEGATIVE_INFINITY;
        for (Segment segment : this.segments) {
            upper = Math.max(upper, segment.curve().upperBound());
        }
        return upper;
    }

    @Override
    public String describe() {
        return "sequence(" + this.segments.size() + " segments, total " + this.totalDurationMs() + "ms"
                + (this.cyclic ? ", cyclic" : "") + ")";
    }
}
