package com.shimizukenta.secssim.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secssim.core.curve.Rng;
import com.shimizukenta.secssim.core.event.EventEffect;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.event.Trigger;

/**
 * 事件上报的效果施加范围：同一 CEID 允许多条计划（周期 + 条件、多台单元）。
 *
 * <p>过去 {@code applyPlanEffects} 只按 CEID 过滤，触发计划 a 时会把计划 b
 * 的效果一起施加——界面高亮 a，值却按 a∪b 跳变。计划身份已知时必须只施加
 * 触发它的那一条；手动触发（{@code planId == null}）没有计划上下文，保持并集。</p>
 *
 * <p>另一半是"效果源必须是实时编排表"：运行时增删改只改 {@link EventScheduler}，
 * 拿装载时的快照会出现"刚加的计划不生效、已删的计划还在改值"。</p>
 */
class EventReporterTest {

    /** 固定时钟：效果落点可断言。 */
    private static final long NOW = 5_000L;

    /** 组装器返回 null：emit 在施加效果/发布通知之后才失败，用异常当"到此为止"的哨兵。 */
    private static final java.util.function.Supplier<S6F11Assembler> NO_ASSEMBLER = () -> null;

    /** 非空通信器；emit 用不到 send（组装先失败），只要求不为 null。 */
    private static final SecsCommunicator STUB_COMMUNICATOR = (SecsCommunicator) Proxy.newProxyInstance(
            SecsCommunicator.class.getClassLoader(),
            new Class<?>[] {SecsCommunicator.class},
            (proxy, method, args) -> {
                Class<?> type = method.getReturnType();
                if (type == boolean.class) {
                    return Boolean.FALSE;
                }
                if (type == int.class) {
                    return Integer.valueOf(0);
                }
                if (type == long.class) {
                    return Long.valueOf(0L);
                }
                return null;
            });

    /**
     * 装载期编排表：a 是<b>首条</b>，触发 b。
     *
     * <p>特意让触发项不是首条：回退成 {@code findFirst()} 猜 planId 时，断言必须失败。</p>
     */
    private static EventSchedule schedule() {
        return new EventSchedule(List.of(
                new EventPlan("a", 2001, new Trigger.At(0L),
                        List.of(new EventEffect(1, EventEffect.Op.SHIFT, null, 10.0, null, null))),
                new EventPlan("b", 2001, new Trigger.At(0L),
                        List.of(new EventEffect(2, EventEffect.Op.SHIFT, null, 100.0, null, null))),
                new EventPlan("other-ceid", 2002, new Trigger.At(0L),
                        List.of(new EventEffect(3, EventEffect.Op.SHIFT, null, 1_000.0, null, null)))));
    }

    private static EventPlan plan(String id, int ceid, int svid, double delta) {
        return new EventPlan(id, ceid, new Trigger.At(0L),
                List.of(new EventEffect(svid, EventEffect.Op.SHIFT, null, delta, null, null)));
    }

    /** 真实调度器；值来源与发送口都不参与（测试只走 add/update/remove 与预览表）。 */
    private static EventScheduler scheduler() {
        return new EventScheduler(new Rng(7L).derive("events"),
                () -> NOW, () -> null, () -> true, (ceid, planId) -> { });
    }

    private static EventReporter reporter(EventEffectStore store, List<String> notices) {
        return reporter(store, notices, EventReporterTest::schedule);
    }

    private static EventReporter reporter(EventEffectStore store, List<String> notices,
                                          java.util.function.Supplier<EventSchedule> schedule) {
        return new EventReporter(NO_ASSEMBLER, () -> STUB_COMMUNICATOR, () -> null, store,
                schedule,
                ceid -> true, () -> { }, () -> { }, (type, payload) -> notices.add(payload),
                () -> NOW, () -> false, "s1", e -> { });
    }

    @Test
    void appliesOnlyEffectsOfTheFiringPlan() {
        EventEffectStore store = new EventEffectStore();
        List<String> notices = new CopyOnWriteArrayList<>();
        EventReporter reporter = reporter(store, notices);

        assertThrows(IOException.class, () -> reporter.emit(2001, "b"));

        assertEquals(1, store.activeAt(2, NOW).size(), "触发计划 b 的效果必须生效");
        assertTrue(store.activeAt(1, NOW).isEmpty(), "同一 CEID 的其他计划不能被连带施加");
        assertTrue(store.activeAt(3, NOW).isEmpty(), "其他 CEID 的计划不能被施加");
        assertEquals(List.of("{\"ceid\":2001,\"planId\":\"b\"}"), notices,
                "通知必须携带真正触发的那条计划 id，而不是同 CEID 的第一条");
    }

    @Test
    void appliesUnionOfCeidPlansForManualTrigger() {
        EventEffectStore store = new EventEffectStore();
        EventReporter reporter = reporter(store, new CopyOnWriteArrayList<>());

        assertThrows(IOException.class, () -> reporter.emit(2001, null));

        assertEquals(1, store.activeAt(1, NOW).size(), "手动触发施加该 CEID 全部计划");
        assertEquals(1, store.activeAt(2, NOW).size());
        assertTrue(store.activeAt(3, NOW).isEmpty(), "手动触发不跨 CEID");
    }

    /**
     * 运行时新增的计划必须立刻参与效果施加。
     *
     * <p>回归点：效果源若是 {@code enableSchedule} 时的快照，新计划的 planId 在表里
     * 找不到，效果被静默丢弃——S6F11 与通知照发，界面上的值却完全不动。</p>
     */
    @Test
    void runtimeAddedPlanTakesEffect() {
        EventEffectStore store = new EventEffectStore();
        List<String> notices = new CopyOnWriteArrayList<>();
        EventScheduler scheduler = scheduler();
        scheduler.enableSchedule(EventSchedule.empty(), 10);
        EventReporter reporter = reporter(store, notices, scheduler::previewSchedule);

        assertTrue(scheduler.addEventPlan(plan("c", 2001, 4, 5.0)));

        assertThrows(IOException.class, () -> reporter.emit(2001, "c"));

        assertEquals(1, store.activeAt(4, NOW).size(), "运行时新增的计划必须能施加效果");
        assertEquals(List.of("{\"ceid\":2001,\"planId\":\"c\"}"), notices);
    }

    /** 运行时更新：施加新版本，旧版本的效果不得再出现。 */
    @Test
    void runtimeUpdatedPlanAppliesNewEffectsOnly() {
        EventEffectStore store = new EventEffectStore();
        EventScheduler scheduler = scheduler();
        scheduler.enableSchedule(new EventSchedule(List.of(plan("u", 2001, 5, 1.0))), 10);
        EventReporter reporter = reporter(store, new CopyOnWriteArrayList<>(), scheduler::previewSchedule);

        assertTrue(scheduler.updateEventPlan("u", plan("u", 2001, 6, 1.0)));

        assertThrows(IOException.class, () -> reporter.emit(2001, "u"));

        assertEquals(1, store.activeAt(6, NOW).size(), "更新后的效果必须生效");
        assertTrue(store.activeAt(5, NOW).isEmpty(), "旧版本的效果不得再施加");
    }

    /** 运行时删除：已删计划不得继续改值。 */
    @Test
    void runtimeRemovedPlanStopsApplyingEffects() {
        EventEffectStore store = new EventEffectStore();
        EventScheduler scheduler = scheduler();
        scheduler.enableSchedule(new EventSchedule(List.of(plan("r", 2001, 7, 1.0))), 10);
        EventReporter reporter = reporter(store, new CopyOnWriteArrayList<>(), scheduler::previewSchedule);

        assertTrue(scheduler.removeEventPlan("r"));

        assertThrows(IOException.class, () -> reporter.emit(2001, "r"));

        assertTrue(store.activeAt(7, NOW).isEmpty(), "已删除的计划不得继续施加效果");
    }

    /**
     * 被禁用的计划不施加效果——包括手动触发的并集路径。
     *
     * <p>回归点：效果源若只排除 CEID、不排除禁用项，界面显示"已禁用"但值照样跳。</p>
     */
    @Test
    void disabledPlanDoesNotApplyEffects() {
        EventEffectStore store = new EventEffectStore();
        EventScheduler scheduler = scheduler();
        scheduler.enableSchedule(new EventSchedule(List.of(plan("off", 2001, 8, 1.0))), 10);
        EventReporter reporter = reporter(store, new CopyOnWriteArrayList<>(), scheduler::previewSchedule);

        assertTrue(scheduler.setEventEnabled("off", false));

        assertThrows(IOException.class, () -> reporter.emit(2001, null));

        assertTrue(store.activeAt(8, NOW).isEmpty(), "被禁用的计划不得施加效果");
    }

    /** planId 来自运行时编辑：引号/反斜杠/换行必须转义，通知仍能按 JSON 解析。 */
    @Test
    void planIdWithSpecialCharactersStaysValidJson() {
        List<String> notices = new CopyOnWriteArrayList<>();
        EventReporter reporter = reporter(new EventEffectStore(), notices);

        String planId = "a\"b\\c\nd";
        assertThrows(IOException.class, () -> reporter.emit(2001, planId));

        assertEquals(1, notices.size());
        JsonHub parsed = JsonHub.fromJson(notices.get(0));
        assertEquals(planId, parsed.get("planId").optionalString().orElse(null),
                "planId 必须原样往返: " + notices.get(0));
        assertEquals(2001, parsed.get("ceid").optionalInt().orElse(0));
    }
}
