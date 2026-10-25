package com.shimizukenta.secssim.runtime.session;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.shimizukenta.secssim.core.event.EventEffect;

/**
 * 已施加事件效果的运行时记录（schema v6）。
 *
 * <p>计划/手动触发一个 CEID 时，它的 {@code effects} 在这里登记
 * {@code (施加时刻, 效果)}；{@link EffectValueSource} 求值时取
 * "在时刻 t 仍然活动"的效果修正基线曲线。手动触发产生的效果
 * 无法从时间纯函数重放，这是它与确定性预览的唯一分叉（见重设计文档 §5.1）。</p>
 *
 * <p>每个 SVID 一个有界环形（64 条）：同变量反复触发时旧效果被挤出，
 * 内存不随压测增长。热重载 {@code clear()}——旧模型的效果随旧配置失效。</p>
 */
public final class EventEffectStore {

    /** 每个 SVID 最多保留的已施加效果数。 */
    static final int MAX_PER_SVID = 64;

    private final Map<Integer, Deque<Applied>> applied = new ConcurrentHashMap<>();

    /** 一条已施加效果。 */
    public record Applied(long tAppliedMs, EventEffect effect) {
    }

    /**
     * 施加一个效果。
     *
     * @param svid      目标 SVID
     * @param effect    效果
     * @param tNowMs    施加时刻（引擎时钟毫秒）
     */
    public void apply(int svid, EventEffect effect, long tNowMs) {
        Deque<Applied> queue = this.applied.computeIfAbsent(svid, key -> new ArrayDeque<>());
        synchronized (queue) {
            if (effect.op() == EventEffect.Op.RESUME) {
                queue.clear();
                return;
            }
            queue.addLast(new Applied(tNowMs, effect));
            while (queue.size() > MAX_PER_SVID) {
                queue.pollFirst();
            }
        }
    }

    /**
     * 按施加顺序取"在时刻 t 活动"的效果。
     *
     * <p>活动的含义：t ≥ 施加时刻；pulse 未过 holdMs；set 带衰减时未过 decayMs
     * （过期即视为已回到基线，剔除）；shift 恒活动直到 resume/挤出。</p>
     *
     * @param svid SVID
     * @param tMs  求值时刻
     * @return 施加顺序的效果快照
     */
    public List<Applied> activeAt(int svid, long tMs) {
        Deque<Applied> queue = this.applied.get(svid);
        if (queue == null || queue.isEmpty()) {
            return List.of();
        }

        List<Applied> active = new ArrayList<>(queue.size());
        synchronized (queue) {
            for (Applied entry : queue) {
                if (!isActive(entry, tMs)) {
                    continue;
                }
                /* shift/add 在更晚的 set/pulse 之前施加才有意义:施加顺序本身
                   就是组合顺序,这里保持队列顺序 */
                active.add(entry);
            }
        }
        return active;
    }

    private static boolean isActive(Applied entry, long tMs) {
        if (tMs < entry.tAppliedMs()) {
            return false;
        }
        EventEffect effect = entry.effect();
        return switch (effect.op()) {
            case SET -> effect.decayMs() == null || tMs < entry.tAppliedMs() + effect.decayMs();
            case PULSE -> tMs < entry.tAppliedMs() + effect.holdMs();
            case ADD, SHIFT -> true;
            case RESUME -> false;
        };
    }

    /**
     * 是否有任何活动效果（诊断用）。
     *
     * @param svid SVID
     * @param tMs  时刻
     * @return 有活动效果返回 {@code true}
     */
    public boolean hasActive(int svid, long tMs) {
        return !this.activeAt(svid, tMs).isEmpty();
    }

    /** 清空全部（热重载时调用）。 */
    public void clear() {
        this.applied.clear();
    }
}
