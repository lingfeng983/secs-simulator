package com.shimizukenta.secssim.core.cycle;

/**
 * wafer 周期时钟：把连续时间映射成「第几片 + 片内相位」。
 *
 * <p>一个周期就是一片 wafer 的处理过程。周期长度是 {@code periodMs} 加上
 * 每片独立的抖动 {@code ±jitterMs}——抖动由种子派生，因此<b>可复现</b>：
 * 同一份配置、同一个种子，第 7 片的起点永远是同一个时刻。</p>
 *
 * <p>两条实现：</p>
 * <ul>
 *   <li>{@link CompiledCycleClock}：纯函数——时间轴从 0 起算，片表由种子惰性构建；</li>
 *   <li>{@link LifecycleCycleClock}：装饰器——持有工艺周期的运行状态
 *       （停止 / 运行 / 已完成），相位锚 = 启动时刻（2026-11-16 计划生命周期重设计
 *       §5.2）。值求值（{@code SourceEvaluator}）只认这个接口，
 *       装配处注入装饰器即可让「值随周期启停」。</li>
 * </ul>
 *
 * <p>三条边界语义：</p>
 * <ul>
 *   <li>抖动用 {@code rng.derive("cycle:" + i)}，与求值顺序无关；</li>
 *   <li>起点是逐片累加（{@code startOf(i+1) = startOf(i) + durationOf(i)}），
 *       所以抖动会累积成节拍漂移，这正是真实产线的样子；</li>
 *   <li>{@code phase} 落在 {@code [0, duration)}，周期边界处归零——
 *       曲线按相位重放，不需要任何"复位"逻辑。</li>
 * </ul>
 *
 * <p>片数必须是有限值：事件在编译期按片展开（见 {@code CycleConfig}），
 * 无限片会让展开无界。要长时间跑就配一个大一点的 {@code count}。</p>
 */
public interface CycleClock {

    /** 周期长度的下限：太短的周期会让事件挤在同一个 tick 上。 */
    long MIN_PERIOD_MS = 1_000L;

    /**
     * 单周期直通时钟：相位恒等于绝对时刻。
     *
     * <p>配置里没有 {@code cycle} 段时用它——{@link DataSource.Clock#PHASE}
     * 因此退化成"只跑一次"，旧配置的语义不必额外分支。
     * （3.3 起装配处会再包一层 {@link LifecycleCycleClock}：
     * 自动 RUNNING、24h 后 FINISHED 冻结，见设计 D12。）</p>
     *
     * @return 直通时钟
     */
    static CycleClock passthrough() {
        return CompiledCycleClock.passthrough();
    }

    /**
     * 编译期时钟：片表由种子确定。
     *
     * @param periodMs 标准周期长度（毫秒）
     * @param jitterMs 每片长度误差（±，毫秒），0 表示不抖
     * @param count    片数（≥1）
     * @param seed     种子：抖动的确定性来源
     * @return 编译期时钟
     */
    static CycleClock of(long periodMs, long jitterMs, int count, long seed) {
        return new CompiledCycleClock(periodMs, jitterMs, count, seed);
    }

    /**
     * 标准周期长度。
     *
     * @return 毫秒
     */
    long periodMs();

    /**
     * 每片长度误差。
     *
     * @return 毫秒
     */
    long jitterMs();

    /**
     * 片数。
     *
     * @return 片数
     */
    int count();

    /**
     * 第 {@code i} 片的实际长度。
     *
     * @param i 片号（从 0 开始）
     * @return 毫秒
     */
    long durationOf(int i);

    /**
     * 第 {@code i} 片的起点。
     *
     * @param i 片号（从 0 开始；{@code i == count} 返回整段结束时刻）
     * @return 毫秒
     */
    long startOf(int i);

    /**
     * 当前时刻落在第几片。
     *
     * <p>超出最后一片时返回 {@code count - 1}——模拟结束后停在最后一片，
     * 与曲线「非循环停在末段」一致，不会因为时间继续走而报错。</p>
     *
     * @param tMs 时刻
     * @return 片号（从 0 开始）
     */
    int indexAt(long tMs);

    /**
     * 片内相位：{@code t - startOf(indexAt(t))}。
     *
     * @param tMs 时刻
     * @return 相位（毫秒，从 0 开始）
     */
    long phaseAt(long tMs);

    /**
     * ABSOLUTE 曲线的本地时刻。
     *
     * <p>编译期时钟下就是 {@code tMs} 本身（时钟零点起算）；生命周期装饰器下是
     * {@code t - startedAt}——ABSOLUTE 曲线随周期重锚，重启周期即重放
     * （2026-11-16 设计 D11）。</p>
     *
     * @param tMs 时刻
     * @return 本地时刻（毫秒）
     */
    long elapsedAt(long tMs);

    /**
     * 整段结束时刻。
     *
     * @return 毫秒
     */
    long endMs();
}
