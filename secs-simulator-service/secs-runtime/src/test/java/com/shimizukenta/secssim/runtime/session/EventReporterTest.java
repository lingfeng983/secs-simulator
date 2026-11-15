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
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.event.Trigger;

/**
 * 事件上报：通知里的计划身份、运行态三态、启停默认值。
 *
 * <p>schema v7 起事件只上报、不改值，所以这里不再断言"效果施加范围"——
 * 值由数据项自己的 {@code source} 决定。仍然要守住的语义是：</p>
 * <ul>
 *   <li>通知必须携带<b>真正触发</b>的那条计划 id，而不是同 CEID 的第一条；</li>
 *   <li>配置里的计划默认禁用，必须显式启动才参与调度；</li>
 *   <li>"运行中"三态（stopped / waiting / running）与"消息真的在发"一致。</li>
 * </ul>
 */
class EventReporterTest {

    /** 固定时钟。 */
    private static final long NOW = 5_000L;

    /** 组装器返回 null：emit 在发布通知之后才失败，用异常当"到此为止"的哨兵。 */
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

    /** 装载期编排表：a 是首条，b 与它同 CEID。 */
    private static EventSchedule schedule() {
        return new EventSchedule(List.of(
                new EventPlan("a", 2001, new Trigger.At(0L)),
                new EventPlan("b", 2001, new Trigger.At(0L)),
                new EventPlan("other-ceid", 2002, new Trigger.At(0L))));
    }

    private static EventPlan plan(String id, int ceid) {
        return new EventPlan(id, ceid, new Trigger.At(0L));
    }

    /** 真实调度器；值来源与发送口都不参与（测试只走 add/update/remove 与预览表）。 */
    private static EventScheduler scheduler() {
        return new EventScheduler(new Rng(7L).derive("events"),
                () -> NOW, () -> null, () -> true, (ceid, planId) -> { });
    }

    private static EventReporter reporter(List<String> notices) {
        return reporter(notices, EventReporterTest::schedule);
    }

    private static EventReporter reporter(List<String> notices,
                                          java.util.function.Supplier<EventSchedule> schedule) {
        return new EventReporter(NO_ASSEMBLER, () -> STUB_COMMUNICATOR, () -> null,
                schedule,
                ceid -> true, () -> { }, () -> { }, (type, payload) -> notices.add(payload),
                () -> NOW, () -> false, "s1", e -> { });
    }

    @Test
    void noticeCarriesTheFiringPlanId() {
        List<String> notices = new CopyOnWriteArrayList<>();
        EventReporter reporter = reporter(notices);

        assertThrows(IOException.class, () -> reporter.emit(2001, "b"));

        assertEquals(List.of("{\"ceid\":2001,\"planId\":\"b\"}"), notices,
                "通知必须携带真正触发的那条计划 id，而不是同 CEID 的第一条");
    }

    @Test
    void manualTriggerHasNoPlanId() {
        List<String> notices = new CopyOnWriteArrayList<>();
        EventReporter reporter = reporter(notices);

        assertThrows(IOException.class, () -> reporter.emit(2001, null));

        assertEquals(List.of("{\"ceid\":2001}"), notices, "手动触发没有计划上下文");
    }

    /**
     * 配置里的计划默认禁用：加载后不参与调度，必须显式启动。
     *
     * <p>回归点：设备一上线就按周期往主机灌 S6F11，现场看起来像"没人点过就开始发"。</p>
     */
    @Test
    void plansAreDisabledByDefault() {
        EventScheduler scheduler = scheduler();
        scheduler.enableSchedule(new EventSchedule(List.of(plan("d", 2001))), 10);

        assertTrue(scheduler.previewSchedule().plans().isEmpty(), "加载后默认不参与调度");
        assertTrue(scheduler.setEventEnabled("d", true));
        assertEquals(1, scheduler.previewSchedule().plans().size(), "显式启动后才参与调度");
    }

    /** 运行时新增/修改/删除都立刻反映到"实时表"（调度器预览表）。 */
    @Test
    void runtimeEditsShowUpInLiveSchedule() {
        EventScheduler scheduler = scheduler();
        scheduler.enableSchedule(EventSchedule.empty(), 10);

        assertTrue(scheduler.addEventPlan(plan("c", 2001)));
        assertTrue(scheduler.setEventEnabled("c", true));
        assertEquals(1, scheduler.previewSchedule().plans().size());

        assertTrue(scheduler.updateEventPlan("c", plan("c", 2002)));
        assertEquals(2002, scheduler.previewSchedule().plans().get(0).ceid());

        assertTrue(scheduler.removeEventPlan("c"));
        assertTrue(scheduler.previewSchedule().plans().isEmpty());
    }

    /**
     * 计划三态：没启用 {@code stopped}、启用了但设备不在线 {@code waiting}、
     * 在线且 CEID 可用 {@code running}。
     *
     * <p>界面上"运行中"必须等于"消息真的在发"。只按 {@code enabled} 判断会把
     * "启动了但设备离线"也显示成运行中。</p>
     */
    @Test
    void planStatusReflectsEnabledOnlineAndCeidGate() {
        java.util.concurrent.atomic.AtomicBoolean online = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean ceidAllowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        EventScheduler scheduler = new EventScheduler(new Rng(7L).derive("events"),
                () -> NOW, () -> null, online::get, (ceid, planId) -> { });
        scheduler.ceidEnabled(ceid -> ceidAllowed.get());

        EventPlan plan = plan("s", 2001);
        scheduler.enableSchedule(new EventSchedule(List.of(plan)), 10);

        assertEquals("stopped", scheduler.planStatus(plan).status(), "默认禁用");

        assertTrue(scheduler.setEventEnabled("s", true));
        assertEquals("waiting", scheduler.planStatus(plan).status(), "启用了但设备没在线");
        assertEquals("offline", scheduler.planStatus(plan).reason());

        online.set(true);
        assertEquals("running", scheduler.planStatus(plan).status(), "在线且在发");

        ceidAllowed.set(false);
        assertEquals("waiting", scheduler.planStatus(plan).status(), "主机 S2F37 屏蔽了该事件");
        assertEquals("ceid-disabled", scheduler.planStatus(plan).reason());
    }

    /** planId 来自运行时编辑：引号/反斜杠/换行必须转义，通知仍能按 JSON 解析。 */
    @Test
    void planIdWithSpecialCharactersStaysValidJson() {
        List<String> notices = new CopyOnWriteArrayList<>();
        EventReporter reporter = reporter(notices);

        String planId = "a\"b\\c\nd";
        assertThrows(IOException.class, () -> reporter.emit(2001, planId));

        assertEquals(1, notices.size());
        JsonHub parsed = JsonHub.fromJson(notices.get(0));
        assertEquals(planId, parsed.get("planId").optionalString().orElse(null),
                "planId 必须原样往返: " + notices.get(0));
        assertEquals(2001, parsed.get("ceid").optionalInt().orElse(0));
    }
}
