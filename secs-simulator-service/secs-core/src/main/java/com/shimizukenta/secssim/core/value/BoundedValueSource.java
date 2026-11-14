package com.shimizukenta.secssim.core.value;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.shimizukenta.secssim.core.compile.ResolvedSvid;
import com.shimizukenta.secssim.core.domain.ValueType;

/**
 * 把值钳制在 SVID 声明的类型值域内。
 *
 * <p>为什么需要这一层：SVID 的 {@code type} 与 {@code min/max} 过去只有"默认曲线"
 * 在用，用户写的模板图 / Step / Pwl 曲线完全绕过它们。于是一个声明为 U1、
 * 实际输出 300 的变量，配置校验通过、设备正常启动，直到 <b>S1F3/S6F11 组装报文时</b>
 * 才在协议层抛值域异常——事件丢失，日志里只剩一行不起眼的错误。</p>
 *
 * <p>越界必须发生在<b>取值处</b>：这里按 {@link ValueType} 的宽度收紧，NaN/Inf 也在此归一，
 * 下游的编码器就永远不会看到非法值。被钳制的次数可通过 {@link #clampedCount()} 观察。</p>
 *
 * <p><b>精度上限</b>：值管线全程是 {@code double}，超过 2^53 的整数已无法精确表示；
 * 编码端是 {@code long}。所以 U8/I8 的上界取 {@code Long} 范围而不是完整 U8 值域，
 * 并用 {@link Math#nextDown(double)} 避开 {@code (double) Long.MAX_VALUE} 舍入到 2^63
 * 后仍超出 {@code long} 的问题。</p>
 */
public final class BoundedValueSource implements ValueSource {

    /** 下游值来源。 */
    private final ValueSource inner;

    /** SVID → 值域；没有声明类型的 SVID 不放进来（原样透传）。 */
    private final Map<Integer, Bounds> bounds;

    /** 被钳制的取值次数（诊断用）。 */
    private final AtomicLong clamped = new AtomicLong();

    /**
     * 包装一个值来源。
     *
     * @param inner 下游值来源
     * @param svids 已解析的 SVID 表（SVID → 定义）；{@code null} 表示不做钳制
     */
    public BoundedValueSource(ValueSource inner, Map<Integer, ResolvedSvid> svids) {
        this.inner = Objects.requireNonNull(inner, "inner");

        Map<Integer, Bounds> map = new HashMap<>();
        if (svids != null) {
            for (ResolvedSvid svid : svids.values()) {
                Bounds b = Bounds.of(svid.type());
                if (b != null) {
                    map.put(svid.id(), b);
                }
            }
        }
        this.bounds = Map.copyOf(map);
    }

    @Override
    public OptionalDouble valueAt(int svid, long tMs) {
        OptionalDouble raw = this.inner.valueAt(svid, tMs);
        if (raw.isEmpty()) {
            return raw;
        }

        Bounds b = this.bounds.get(svid);
        if (b == null) {
            return raw;
        }

        double value = raw.getAsDouble();
        double bounded = b.apply(value);
        if (Double.compare(value, bounded) != 0) {
            this.clamped.incrementAndGet();
        }
        return OptionalDouble.of(bounded);
    }

    @Override
    public Set<Integer> svids() {
        return this.inner.svids();
    }

    /**
     * 被钳制的取值次数。
     *
     * @return 次数
     */
    public long clampedCount() {
        return this.clamped.get();
    }

    /**
     * 被包装的下游值来源（诊断与测试用）。
     *
     * @return 下游值来源
     */
    public ValueSource inner() {
        return this.inner;
    }

    /** 一个类型的闭区间。 */
    private record Bounds(double min, double max) {

        /**
         * 按类型给值域；非数值类型返回 {@code null}（不钳制）。
         *
         * @param type 类型
         * @return 值域
         */
        static Bounds of(ValueType type) {
            return switch (type) {
                case U1 -> new Bounds(0.0, 255.0);
                case U2 -> new Bounds(0.0, 65_535.0);
                case U4 -> new Bounds(0.0, 4_294_967_295.0);
                case U8 -> new Bounds(0.0, Math.nextDown((double) Long.MAX_VALUE));
                case I1 -> new Bounds(-128.0, 127.0);
                case I2 -> new Bounds(-32_768.0, 32_767.0);
                case I4 -> new Bounds(-2_147_483_648.0, 2_147_483_647.0);
                case I8 -> new Bounds((double) Long.MIN_VALUE, Math.nextDown((double) Long.MAX_VALUE));
                case F4, F8 -> new Bounds(-Double.MAX_VALUE, Double.MAX_VALUE);
                case ASCII, BOOLEAN, BINARY -> null;
            };
        }

        /**
         * 把值收进区间；NaN 归一为 0（NaN 无法进 JSON，也不该上报给主机）。
         *
         * @param value 原值
         * @return 钳制后的值
         */
        double apply(double value) {
            if (Double.isNaN(value)) {
                return Math.max(0.0, this.min);
            }
            if (value < this.min) {
                return this.min;
            }
            if (value > this.max) {
                return this.max;
            }
            return value;
        }
    }
}
