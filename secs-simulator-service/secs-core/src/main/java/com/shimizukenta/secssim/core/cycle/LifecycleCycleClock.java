package com.shimizukenta.secssim.core.cycle;

/**
 * 生命周期周期时钟：给编译期时钟挂「停止 / 运行 / 已完成」状态与启动锚点。
 *
 * <p>计划生命周期重设计（2026-11-16 §5.2）的核心装饰器：值求值与工艺周期
 * 状态机共用这一个实例——{@code CycleRunner} 的启动 / 停止 / 跑满三处更新状态，
 * {@code SourceEvaluator} 照常调 {@link #indexAt(long)} / {@link #phaseAt(long)}，
 * 两边看到的就是同一个节拍。</p>
 *
 * <p>状态语义（D9 / D10 / D11 / D12）：</p>
 * <ul>
 *   <li><b>RUNNING</b>：相位锚 = 启动时刻。片号与片内相位按
 *       {@code t - startedAt} 查编译期片表；ABSOLUTE 曲线的本地时刻 = 同一锚点的
 *       相对时刻，重启周期即重放。</li>
 *   <li><b>STOPPED</b>：待机保持——PHASE 冻结在进入停止时的值（D10），
 *       CYCLE 槽归 0（没有正在处理的片），ABSOLUTE 冻结在停止时刻的相对值。
 *       有 cycle 段的会话初态即 STOPPED（全停清单：会话新建 / 全停事件后待手动启动）。</li>
 *   <li><b>FINISHED</b>：跑满 {@code count} 片后冻结在末相位——修掉
 *       {@code phaseAt} 相位无界（审计 P1-1）。CYCLE 槽停在末槽。</li>
 * </ul>
 *
 * <p>时间源不变：时钟读数仍来自会话 {@code EngineClock}，装饰器只持有状态与锚点，
 * 不引入第二条时间线。</p>
 */
public final class LifecycleCycleClock implements CycleClock {

    /** 工艺周期运行状态。 */
    public enum State {

        /** 待启动（或被全停）：值冻结在进入停止时的表现。 */
        STOPPED,

        /** 运行中：相位锚 = startedAt。 */
        RUNNING,

        /** 已完成：跑满 count 片，冻结在末相位。 */
        FINISHED,
    }

    private final CycleClock compiled;

    private volatile State state;
    /** RUNNING 的锚点（时钟系毫秒）。 */
    private volatile long startedAt;
    /** STOPPED / FINISHED 的冻结读数。 */
    private volatile int frozenIndex;
    private volatile long frozenPhase;
    private volatile long frozenElapsed;

    /**
     * 创建。
     *
     * @param compiled 编译期时钟（片表与抖动）
     * @param initial  初态：有 cycle 段传 {@link State#STOPPED}（待手动启动）；
     *                 无 cycle 段传 {@link State#RUNNING}（D12：锚 = 时钟零点，
     *                 与 3.1 行为一致，24h 后 FINISHED 冻结）
     */
    public LifecycleCycleClock(CycleClock compiled, State initial) {
        if (compiled == null) {
            throw new IllegalArgumentException("compiled clock must not be null");
        }
        this.compiled = compiled;
        this.state = initial;
        this.startedAt = 0L;
        this.frozenIndex = 0;
        this.frozenPhase = 0L;
        this.frozenElapsed = 0L;
    }

    /** 编译期时钟（片表 / 片数 / 结束时刻的来源）。 */
    public CycleClock compiled() {
        return this.compiled;
    }

    /** 当前运行状态。 */
    public State state() {
        return this.state;
    }

    /** 启动锚点（时钟系毫秒；未启动过返回 0）。 */
    public long startedAt() {
        return this.startedAt;
    }

    /**
     * 启动：锚点 = 点击时刻；值从第 0 片初值起跑（D10）。
     *
     * @param nowMs 当前时钟读数
     */
    public void start(long nowMs) {
        this.startedAt = nowMs;
        this.state = State.RUNNING;
    }

    /**
     * 停止：值冻结在进入停止时的表现（D10）。
     *
     * <p>已在 STOPPED / FINISHED 时保持原冻结读数（FINISHED 停机仍停在末相位）。</p>
     *
     * @param nowMs 当前时钟读数
     */
    public void stop(long nowMs) {
        if (this.state == State.RUNNING) {
            long elapsed = nowMs - this.startedAt;
            this.frozenIndex = this.compiled.indexAt(elapsed);
            this.frozenPhase = this.compiled.phaseAt(elapsed);
            this.frozenElapsed = elapsed;
        }
        this.state = State.STOPPED;
    }

    /**
     * 跑满 count 片：冻结在末相位（D12 / 审计 P1-1）。
     *
     * @param nowMs 当前时钟读数（仅用于 STARTED 判定，冻结值取片表末尾）
     */
    public void finish(long nowMs) {
        this.frozenIndex = this.compiled.count() - 1;
        this.frozenPhase = this.compiled.durationOf(this.compiled.count() - 1);
        this.frozenElapsed = this.compiled.endMs();
        this.state = State.FINISHED;
    }

    @Override
    public long periodMs() {
        return this.compiled.periodMs();
    }

    @Override
    public long jitterMs() {
        return this.compiled.jitterMs();
    }

    @Override
    public int count() {
        return this.compiled.count();
    }

    @Override
    public long durationOf(int i) {
        return this.compiled.durationOf(i);
    }

    @Override
    public long startOf(int i) {
        return this.compiled.startOf(i);
    }

    /**
     * 当前片号。
     *
     * <p>STOPPED = 槽 0（没有正在处理的片，CYCLE 槽回到 0）；
     * FINISHED = 末槽；RUNNING 按 {@code t - startedAt} 查片表。</p>
     */
    @Override
    public int indexAt(long tMs) {
        State current = this.state;
        if (current == State.STOPPED) {
            return 0;
        }
        if (current == State.FINISHED) {
            return this.compiled.count() - 1;
        }
        return this.compiled.indexAt(tMs - this.startedAt);
    }

    /**
     * 片内相位。
     *
     * <p>STOPPED / FINISHED 冻结在进入该状态时的读数（D10 / 审计 P1-1）。</p>
     */
    @Override
    public long phaseAt(long tMs) {
        State current = this.state;
        if (current == State.STOPPED) {
            return this.frozenPhase;
        }
        if (current == State.FINISHED) {
            return this.frozenPhase;
        }
        return this.compiled.phaseAt(tMs - this.startedAt);
    }

    /**
     * ABSOLUTE 曲线的本地时刻：RUNNING = {@code t - startedAt}（重启周期即重放，D11）；
     * STOPPED / FINISHED 冻结。
     */
    @Override
    public long elapsedAt(long tMs) {
        State current = this.state;
        if (current == State.STOPPED) {
            return this.frozenElapsed;
        }
        if (current == State.FINISHED) {
            return this.frozenElapsed;
        }
        return tMs - this.startedAt;
    }

    @Override
    public long endMs() {
        return this.compiled.endMs();
    }
}
