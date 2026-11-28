package com.shimizukenta.secssim.core.value;

import java.util.List;
import java.util.Map;

import com.shimizukenta.secssim.core.curve.Curve;
import com.shimizukenta.secssim.core.template.Expression;

/**
 * 一个数据项的值定义（schema v7）。
 *
 * <p>v6 里"值从哪来"散在模板图的曲线/组合/实例绑定三处；v7 把它收成一个
 * 挂在数据项上的 {@code source}——<b>一个数据项一份 source，没有绑定表</b>。
 * 晶圆属性（Context）、设备常量（ECID）、参数变量（SVID）共用同一套形状，
 * 差别只在允许的 {@link Clock} 与取值类型。</p>
 *
 * <p>时钟有三种，决定曲线拿到的是什么时间：</p>
 * <ul>
 *   <li>{@link Clock#PHASE} —— 片内相位 {@code [0, duration)}，每片重放（SVID 默认）；</li>
 *   <li>{@link Clock#ABSOLUTE} —— 自仿真起点的时间，只跑一次；</li>
 *   <li>{@link Clock#CYCLE} —— 片号，用于"每片变一次"的阶跃值。</li>
 * </ul>
 */
public sealed interface DataSource
        permits DataSource.Const, DataSource.Text, DataSource.CurveSource, DataSource.ExprSource,
                DataSource.CycleNumber, DataSource.CycleText {

    /**
     * {@code pattern} 里补零宽度的上限。
     *
     * <p>没有上限时 {@code {999999999}} 会让格式化先分配近 GB 的填充串，
     * 一次采样就能打满堆。片号补到 32 位已经远超实际需要。</p>
     */
    int MAX_PATTERN_WIDTH = 32;

    /**
     * 时间自变量。
     */
    enum Clock {

        /** 片内相位，每片重放。 */
        PHASE,

        /** 绝对时间，只跑一次。 */
        ABSOLUTE,

        /** 片号（0,1,2…），阶跃。 */
        CYCLE
    }

    /**
     * 该来源用的时钟。
     *
     * @return 时钟
     */
    Clock clock();

    /**
     * 常量数值。
     *
     * @param value 值
     */
    record Const(double value) implements DataSource {

        @Override
        public Clock clock() {
            return Clock.ABSOLUTE;
        }
    }

    /**
     * 常量文本（只用于 Context：LOTID / RCPID / PPID 这类）。
     *
     * @param value 文本
     */
    record Text(String value) implements DataSource {

        @Override
        public Clock clock() {
            return Clock.ABSOLUTE;
        }
    }

    /**
     * 一条曲线，可选一层标量变换。
     *
     * <p>{@code transform} 用变量 {@code V} 引用曲线输出，只做"对同一条曲线换算式"
     * 这类事情（如保留一位小数）——它不引入任何中间数据项，因此不会长回模板图。</p>
     *
     * @param curve     曲线
     * @param clock     时钟
     * @param transform 变换表达式（可空）
     */
    record CurveSource(Curve curve, Clock clock, Expression transform) implements DataSource {
    }

    /**
     * 局部变量的组合表达式。
     *
     * <p>{@code vars} 是<b>本数据项内部的私有曲线</b>，不是实例：名字只在这个
     * source 里可见，不参与任何全局绑定，也不产生 SVID。这正是"复制模板即展开"的落点——
     * 库里的多输出组合被折叠成一条带私有变量的表达式。</p>
     *
     * @param expr  表达式（变量：{@code t}、{@code 变量名}）
     * @param vars  私有曲线：名字 → 曲线
     * @param clock 时钟
     */
    record ExprSource(Map<String, Curve> vars, Expression expr, Clock clock) implements DataSource {

        /** 防御性拷贝：vars 可空（视为无局部变量），拷贝后不可变，避免调用方后续改动泄漏进来。 */
        public ExprSource {
            vars = vars == null ? Map.of() : Map.copyOf(vars);
        }
    }

    /**
     * 按片号线性变化：{@code start + step × (片号 / every)}。
     *
     * @param start 起始值
     * @param step  每档步长
     * @param every 每几片进一档
     */
    record CycleNumber(long start, long step, long every) implements DataSource {

        /** 校验并创建。 */
        public CycleNumber {
            if (every < 1L) {
                throw new IllegalArgumentException("every must be >= 1 but was " + every);
            }
        }

        @Override
        public Clock clock() {
            return Clock.CYCLE;
        }

        /**
         * 第 {@code index} 片的值。
         *
         * @param index 片号
         * @return 值
         */
        public double valueAt(int index) {
            return this.start + this.step * (index / this.every);
        }
    }

    /**
     * 按片号变化的文本（Context 专用）。
     *
     * <p>三种写法互斥：{@code value} 常量、{@code pattern} 模板（如 {@code "LOT{02}"}）、
     * {@code list} 轮换。</p>
     *
     * @param value   常量文本（可空）
     * @param pattern 模板（可空；{@code {n}} 为片序，{@code {03}} 表示补到 3 位）
     * @param list    轮换列表（可空）
     * @param start   片序起点
     * @param every   每几片进一档
     */
    record CycleText(String value, String pattern, List<String> list, long start, long every) implements DataSource {

        /** 校验并创建。 */
        public CycleText {
            list = list == null ? List.of() : List.copyOf(list);
            if (every < 1L) {
                throw new IllegalArgumentException("every must be >= 1 but was " + every);
            }
            int defined = (value != null ? 1 : 0) + (pattern != null ? 1 : 0) + (list.isEmpty() ? 0 : 1);
            if (defined != 1) {
                throw new IllegalArgumentException("CycleText 需要且只能有 value / pattern / list 之一");
            }
        }

        @Override
        public Clock clock() {
            return Clock.CYCLE;
        }

        /**
         * 第 {@code index} 片的文本。
         *
         * @param index 片号
         * @return 文本
         */
        public String textAt(int index) {
            long slot = this.start + (index / this.every);
            if (this.value != null) {
                return this.value;
            }
            if (!this.list.isEmpty()) {
                int at = (int) Math.floorMod(slot, (long) this.list.size());
                return this.list.get(at);
            }
            return fill(this.pattern, slot);
        }

        /**
         * 把 {@code {n}} / {@code {03}} 替换成补零后的片序。
         *
         * <p>宽度必须限幅：{@code {999999999}} 会让 String.format 先分配近 GB 的填充串，
         * 一次采样就能把堆打满；{@code {0}} 之类的非法组合会抛未捕获的
         * IllegalFormatException。超出范围或非数字一律按"不替换"处理，
         * 保留原文让用户在界面上看出配置写错了。</p>
         */
        private static String fill(String pattern, long slot) {
            int open = pattern.indexOf('{');
            int close = open < 0 ? -1 : pattern.indexOf('}', open);
            if (open < 0 || close < 0) {
                return pattern;
            }
            String body = pattern.substring(open + 1, close);
            String text;
            if (body.isEmpty()) {
                text = Long.toString(slot);
            } else {
                try {
                    int width = Integer.parseInt(body);
                    if (width < 1 || width > MAX_PATTERN_WIDTH) {
                        return pattern;
                    }
                    text = String.format("%0" + width + "d", slot);
                } catch (NumberFormatException | java.util.IllegalFormatException e) {
                    return pattern;
                }
            }
            return pattern.substring(0, open) + text + pattern.substring(close + 1);
        }
    }
}
