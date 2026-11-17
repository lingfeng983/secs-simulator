package com.shimizukenta.secssim.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secssim.app.sml.SmlParser;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.PlanAction;
import com.shimizukenta.secssim.core.event.Trigger;
import com.shimizukenta.secssim.store.Db;
import com.shimizukenta.secssim.store.PlanExecutionRepository;
import com.shimizukenta.secssim.store.RuntimeStateRepository;

/**
 * 编排执行器（设计 §2.7.2 / todo-3.0 §1.4）的单元测试。
 *
 * <p>不建真连接：{@link SecsCommunicator} 用动态代理记录 S/F/W 与调用线程；
 * 台账用内存 {@link Recorder} 捕获，只有最后一个用例接真实 SQLite 验证
 * 「发送线程零同步 DB 写」。</p>
 *
 * <p>核心回归网：顺序契约、幂等键、离线三策略、多动作、SML、W 位 ACK 队列、
 * 回滚边界、SSE 立即推标记、异步落库。</p>
 */
class PlanExecutorTest {

    private static final String DEVICE = "dev_01JTEST";
    private static final long AT = 1_234_000L;

    /* ------------------------------------------------------------------ */
    /* 顺序契约                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * effects → build → send。回归点：先组装 S6F11 再改值，报文里会是旧值；
     * pulse/decay 这类时间函数尤其明显。
     */
    @Test
    void bodyIsBuiltBeforeSend() throws Exception {
        Recorder rec = new Recorder();
        List<String> order = new CopyOnWriteArrayList<>();
        AtomicInteger bodyCeid = new AtomicInteger();
        List<Long> bodyAt = new CopyOnWriteArrayList<>();
        List<String> sends = new CopyOnWriteArrayList<>();

        PlanExecutor executor = new PlanExecutor(DEVICE, rec);
        PlanExecutor.Secs2BodySupplier body = (ceid, atMs) -> {
            order.add("build");
            bodyCeid.set(ceid);
            bodyAt.add(atMs);
            return null;
        };
        executor.execute(plain(), "p", 2001, AT, comm(sends, order), true,
                body, false);

        assertEquals(List.of("build", "send"), order,
                "组装必须早于发送");
        assertEquals(2001, bodyCeid.get(), "组装要拿到 CEID");
        assertEquals(List.of(AT), bodyAt, "组装要拿到调度锚点时刻");
        assertEquals(List.of("S6F11:w=false"), sends);
        assertEquals("SENT", rec.ledger.get(0).status());
        assertEquals(1, rec.ledger.size(), "一次触发 = 一条台账");
        assertFalse(rec.ledger.get(0).dryRun());
        assertEquals(List.of("p|true|"), rec.fires, "SENT 才计成功 fire");
        assertEquals(List.of(Boolean.FALSE), rec.immediate, "SENT 走合并窗口，不立即推");
        assertTrue(rec.notices.get(0).contains("\"executionId\":\"" + DEVICE + "|p|2001|" + AT + "\""),
                "SSE 摘要要带幂等键（含 CEID）: " + rec.notices.get(0));
    }

    /* ------------------------------------------------------------------ */
    /* 幂等 / 单步试跑                                                     */
    /* ------------------------------------------------------------------ */

    /** 同一 (deviceId, planId, atMs) 只发一次、只记一条台账。 */
    @Test
    void sameIdempotencyKeyIsExecutedOnce() throws Exception {
        Recorder rec = new Recorder();
        List<String> sends = new CopyOnWriteArrayList<>();
        PlanExecutor executor = new PlanExecutor(DEVICE, rec);

        for (int i = 0; i < 3; i++) {
            executor.execute(plain(), "p", 2001, AT, comm(sends, null), true,
                    (ceid, at) -> null, false);
        }

        assertEquals(1, sends.size(), "重复触发不得重复发送");
        assertEquals(1, rec.ledger.size(), "重复触发不得重复记台账");
        assertTrue(executor.lastReserveAt("p", 2001, AT).isPresent(), "幂等窗口要留下预留痕迹");

        /* 换一个调度锚点 = 新的一次触发 */
        executor.execute(plain(), "p", 2001, AT + 1, comm(sends, null), true,
                (ceid, at) -> null, false);
        assertEquals(2, sends.size());
        assertEquals(2, rec.ledger.size());
    }

    /**
     * 单步试跑：{@code dry_run=1}、幂等键带 {@code |step|seq}、不计 fire_count。
     * 同一 atMs 连点两次都必须真的执行（否则 UI 上「再试一次」无反应）。
     */
    @Test
    void stepRunsEveryTimeAsDryRunWithoutFireCount() throws Exception {
        Recorder rec = new Recorder();
        List<String> sends = new CopyOnWriteArrayList<>();
        PlanExecutor executor = new PlanExecutor(DEVICE, rec);

        executor.step(plain(), "p", 2001, AT, comm(sends, null), true,
                (ceid, at) -> null, false);
        executor.step(plain(), "p", 2001, AT, comm(sends, null), true,
                (ceid, at) -> null, false);

        assertEquals(2, sends.size(), "每次单步试跑都要真发");
        assertEquals(2, rec.ledger.size());
        assertTrue(rec.ledger.stream().allMatch(PlanExecutionRepository.Execution::dryRun));
        assertTrue(rec.ledger.get(0).idempotencyKey().contains("|step|1"));
        assertTrue(rec.ledger.get(1).idempotencyKey().contains("|step|2"));
        assertNotEquals(rec.ledger.get(0).idempotencyKey(), rec.ledger.get(1).idempotencyKey());
        assertTrue(rec.fires.isEmpty(), "试跑不计 fire_count");
        assertTrue(rec.notices.get(0).contains("\"dryRun\":true"));
    }

    /* ------------------------------------------------------------------ */
    /* 离线策略                                                            */
    /* ------------------------------------------------------------------ */

    /** skip：不组装、不发送，只记一条 SKIPPED。 */
    @Test
    void offlineSkipRecordsOnly() throws Exception {
        Recorder rec = new Recorder();
        List<String> sends = new CopyOnWriteArrayList<>();
        PlanExecutor executor = new PlanExecutor(DEVICE, rec);

        executor.execute(plan("p", 2001, List.of(), EventPlan.OFFLINE_SKIP, EventPlan.FAILURE_KEEP),
                "p", 2001, AT, null, false,
                (ceid, at) -> { throw new AssertionError("离线不该组装报文"); }, false);

        assertEquals(0, sends.size());
        assertEquals(1, rec.ledger.size());
        assertEquals("SKIPPED", rec.ledger.get(0).status());
        assertTrue(rec.ledger.get(0).error().contains("skip"));
        assertEquals("[]", rec.ledger.get(0).effectJson());
        assertEquals(List.of("p|false|device-offline"), rec.fires);
        assertEquals(List.of(Boolean.FALSE), rec.immediate, "SKIPPED 不立即推");
    }

    /** fail：记 FAILED 且 SSE 立即推（设计 §2.4.3）。 */
    @Test
    void offlineFailRecordsFailedAndPublishesImmediately() throws Exception {
        Recorder rec = new Recorder();
        PlanExecutor executor = new PlanExecutor(DEVICE, rec);

        executor.execute(plan("p", 2001, List.of(), EventPlan.OFFLINE_FAIL, EventPlan.FAILURE_KEEP),
                "p", 2001, AT, comm(new CopyOnWriteArrayList<>(), null), false,
                (ceid, at) -> null, false);

        assertEquals("FAILED", rec.ledger.get(0).status());
        assertTrue(rec.ledger.get(0).error().contains("offlinePolicy=fail"));
        assertEquals(List.of(Boolean.TRUE), rec.immediate, "FAILED 必须立即推、不进合并窗口");
        assertTrue(rec.notices.get(0).contains("\"status\":\"FAILED\""));
    }

    /**
     * queue 已废弃（2026-11-16 设计 M2 去补跑）：按 skip 处理——记 SKIPPED 台账，
     * 不排队、不补发；配置解析保留该值只为给迁移 Issue（4.0 删除）。
     */
    @Test
    void offlineQueuePolicyIsDegradedToSkipWithoutReplay() throws Exception {
        Recorder rec = new Recorder();
        List<String> sends = new CopyOnWriteArrayList<>();
        PlanExecutor executor = new PlanExecutor(DEVICE, rec);
        EventPlan queued = plan("p", 2001, List.of(), EventPlan.OFFLINE_QUEUE, EventPlan.FAILURE_KEEP);

        executor.execute(queued, "p", 2001, AT, null, false,
                (ceid, at) -> null, false);

        assertEquals(0, sends.size(), "queue 降级后离线不发送");
        assertEquals(1, rec.ledger.size(), "按 skip 口径记台账");
        assertEquals("SKIPPED", rec.ledger.get(0).status());
        assertTrue(rec.ledger.get(0).error().contains("skip"));
    }

    /* ------------------------------------------------------------------ */
    /* 多动作 / SML / PARTIAL                                              */
    /* ------------------------------------------------------------------ */

    /** 一次触发发多条：默认 S6F11 + 两条自定义 SML，动作顺序即发送顺序。 */
    @Test
    void multipleActionsSendInDeclaredOrder() throws Exception {
        Recorder rec = new Recorder();
        List<String> sends = new CopyOnWriteArrayList<>();
        PlanExecutor executor = new PlanExecutor(DEVICE, rec);
        EventPlan multi = plan("p", 2001, List.of(
                PlanAction.s6f11(),
                PlanAction.sml("S5F1 <U1 7>."),
                PlanAction.sml("S10F1 <A \"hi\">.")),
                EventPlan.OFFLINE_SKIP, EventPlan.FAILURE_KEEP);

        executor.execute(multi, "p", 2001, AT, comm(sends, null), true,
                (ceid, at) -> null, false);

        assertEquals(3, sends.size(), "多 action = 多条报文");
        assertTrue(sends.get(0).startsWith("S6F11"));
        assertTrue(sends.get(1).startsWith("S5F1"));
        assertTrue(sends.get(2).startsWith("S10F1"));
        assertEquals("SENT", rec.ledger.get(0).status());
        assertEquals(3, count(rec.ledger.get(0).messageJson(), "\"kind\""),
                "台账要逐条记录报文，便于前端展开: " + rec.ledger.get(0).messageJson());
        assertTrue(rec.ledger.get(0).error().isEmpty());
    }

    /** SML 语法错误只让该动作失败：PARTIAL、错误逐条留痕。 */
    @Test
    void brokenSmlYieldsPartial() throws Exception {
        Recorder rec = new Recorder();
        List<String> sends = new CopyOnWriteArrayList<>();
        PlanExecutor executor = new PlanExecutor(DEVICE, rec);
        EventPlan mixed = plan("p", 2001, List.of(
                PlanAction.s6f11(),
                PlanAction.sml("S1F2 W <U1 1>.")),  /* secondary 带 W：解析期必失败 */
                EventPlan.OFFLINE_SKIP, EventPlan.FAILURE_ROLLBACK);

        executor.execute(mixed, "p", 2001, AT, comm(sends, null), true,
                (ceid, at) -> null, false);

        assertEquals(1, sends.size());
        assertEquals("PARTIAL", rec.ledger.get(0).status());
        assertTrue(rec.ledger.get(0).messageJson().contains("\"error\""));
        assertEquals(1, rec.fires.size(), "PARTIAL 仍要计一次 fire（但不算成功）");
        assertTrue(rec.fires.get(0).startsWith("p|false|"), "PARTIAL 不算成功 fire");
        assertEquals(List.of(Boolean.FALSE), rec.immediate, "PARTIAL 不立即推");
    }

    /* ------------------------------------------------------------------ */
    /* 发送失败                                                            */
    /* ------------------------------------------------------------------ */

    /** 全失败：记 FAILED、立即推；效果层已取消，台账 effects 恒为空数组。 */
    @Test
    void failedSendIsRecordedWithoutEffectLayer() throws Exception {
        Recorder rec = new Recorder();
        PlanExecutor executor = new PlanExecutor(DEVICE, rec);

        executor.execute(plan("p", 2001, List.of(), EventPlan.OFFLINE_SKIP,
                        EventPlan.FAILURE_KEEP),
                "p", 2001, AT, commThrows("链路断开"), true,
                (ceid, at) -> null, false);

        assertEquals("FAILED", rec.ledger.get(0).status());
        assertEquals("[]", rec.ledger.get(0).effectJson(), "事件不改值，台账不带效果");
        assertTrue(rec.ledger.get(0).error().contains("链路断开"));
        assertEquals(List.of(Boolean.TRUE), rec.immediate, "FAILED 立即推");
    }

    /* ------------------------------------------------------------------ */
    /* action 级 wbit / W 位 ACK 队列                                       */
    /* ------------------------------------------------------------------ */

    /** 动作上的 wbit 覆盖解析器结果；W 位发送必须走 ACK 队列而非心跳线程。 */
    @Test
    void actionWbitOverridesResolverAndUsesAckQueue() throws Exception {
        Recorder rec = new Recorder();
        List<String> sends = new CopyOnWriteArrayList<>();
        List<String> threads = new CopyOnWriteArrayList<>();
        CountDownLatch sent = new CountDownLatch(1);
        ExecutorService ack = Executors.newSingleThreadExecutor(runnable ->
                Thread.ofPlatform().name("plan-ack-test").unstarted(runnable));

        PlanExecutor executor = new PlanExecutor(DEVICE, rec);
        executor.actionResolver((action, ceid, atMs) -> new SmlParser.Message(6, 11, false, null));
        executor.ackSender(() -> ack);
        SecsCommunicator comm = comm(sends, null, args -> {
            threads.add(Thread.currentThread().getName());
            sent.countDown();
        });
        EventPlan overridden = plan("p", 2001,
                List.of(new PlanAction(PlanAction.KIND_S6F11, null, null, true, null, Map.of())),
                EventPlan.OFFLINE_SKIP, EventPlan.FAILURE_KEEP);

        try {
            executor.execute(overridden, "p", 2001, AT, comm, true,
                    (ceid, at) -> null, false);

            assertTrue(sent.await(2, TimeUnit.SECONDS), "ACK 队列必须真的执行发送");
            assertEquals(List.of("S6F11:w=true"), sends);
            assertEquals(List.of("plan-ack-test"), threads,
                    "W 位等待回复绝不能占用事件心跳线程");
            assertEquals("SENT", rec.ledger.get(0).status());
        } finally {
            ack.shutdownNow();
        }
    }

    /** 动作 wbit=false 反过来覆盖解析器的 true，且不进 ACK 队列。 */
    @Test
    void actionWbitFalseForcesPlainSend() throws Exception {
        Recorder rec = new Recorder();
        List<String> sends = new CopyOnWriteArrayList<>();
        List<String> threads = new CopyOnWriteArrayList<>();
        AtomicInteger queuedTasks = new AtomicInteger();
        PlanExecutor executor = new PlanExecutor(DEVICE, rec);
        executor.actionResolver((action, ceid, atMs) -> new SmlParser.Message(6, 11, true, null));
        executor.ackSender(() -> {
            queuedTasks.incrementAndGet();
            return Executors.newSingleThreadExecutor();
        });

        executor.execute(plan("p", 2001,
                        List.of(new PlanAction(PlanAction.KIND_S6F11, null, null, false, null, Map.of())),
                        EventPlan.OFFLINE_SKIP, EventPlan.FAILURE_KEEP),
                "p", 2001, AT, comm(sends, null, args -> threads.add(Thread.currentThread().getName())),
                true, (ceid, at) -> null, false);

        assertEquals(List.of("S6F11:w=false"), sends);
        assertEquals(List.of(Thread.currentThread().getName()), threads);
        assertEquals(0, queuedTasks.get(), "无 W 位不该占用 ACK 队列");
    }

    /** ACK 队列被拒绝 → 该动作 FAILED 并在台账写明队列已满。 */
    @Test
    void rejectedAckQueueIsRecordedAsFailedAction() throws Exception {
        Recorder rec = new Recorder();
        ExecutorService dead = Executors.newSingleThreadExecutor();
        dead.shutdown();
        PlanExecutor executor = new PlanExecutor(DEVICE, rec);
        executor.ackSender(() -> dead);
        executor.actionResolver((action, ceid, atMs) -> new SmlParser.Message(6, 11, true, null));

        executor.execute(plan("p", 2001, List.of(), EventPlan.OFFLINE_SKIP,
                        EventPlan.FAILURE_KEEP),
                "p", 2001, AT, comm(new CopyOnWriteArrayList<>(), null), true,
                (ceid, at) -> null, true);

        assertEquals("FAILED", rec.ledger.get(0).status());
        assertTrue(rec.ledger.get(0).error().contains("ACK 队列已满"));
        assertEquals(List.of(Boolean.TRUE), rec.immediate);
    }

    /** ACK 队列里发送抛错 → 走 errorSink，不炸事件心跳线程。 */
    @Test
    void ackQueueSendErrorGoesToErrorSink() throws Exception {
        Recorder rec = new Recorder();
        CountDownLatch reported = new CountDownLatch(1);
        List<String> errors = new CopyOnWriteArrayList<>();
        ExecutorService ack = Executors.newSingleThreadExecutor();
        PlanExecutor executor = new PlanExecutor(DEVICE, rec);
        executor.ackSender(() -> ack);
        executor.errorSink(e -> {
            errors.add(e.getMessage() == null ? e.toString() : e.getMessage());
            reported.countDown();
        });

        try {
            executor.execute(plan("p", 2001, List.of(), EventPlan.OFFLINE_SKIP,
                            EventPlan.FAILURE_KEEP),
                    "p", 2001, AT, commThrows("对端拒收"), true,
                    (ceid, at) -> null, true);

            assertTrue(reported.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("对端拒收"), errors);
            /* D7：W 位异步失败在入队行（SENT）之外补一条 FAILED 台账（|ack|seq），
               并计入计划 fire（失败口径），不重试不补发 */
            assertEquals(2, rec.ledger.size(), "入队行 + 异步失败行");
            assertEquals("SENT", rec.ledger.get(0).status());
            assertEquals("FAILED", rec.ledger.get(1).status());
            assertTrue(rec.ledger.get(1).idempotencyKey().contains("|ack|"),
                    "异步失败行用新幂等键: " + rec.ledger.get(1).idempotencyKey());
            assertEquals("对端拒收", rec.ledger.get(1).error());
            assertEquals(List.of("p|true|", "p|false|对端拒收"), rec.fires,
                    "入队行计成功 fire，异步失败行再计一次失败 fire");
        } finally {
            ack.shutdownNow();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 台账异步落库                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * 发送线程零同步 DB 写：故意用另一线程占住唯一的 runtime.db 写事务，
     * 执行器仍须立刻返回；释放后由专用写线程把台账与 fire 计数一起落库。
     */
    @Test
    void ledgerAndFireCountAreFlushedOffTheSendingThread(
            @TempDir(cleanup = CleanupMode.NEVER) Path workDir) throws Exception {
        try (Db db = Db.open(workDir);
             PlanExecutionRepository repo = new PlanExecutionRepository(db)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread holder = Thread.ofPlatform().name("db-write-lock-holder").start(() ->
                    db.writeRuntime(connection -> {
                        entered.countDown();
                        try {
                            release.await(3, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    }));
            assertTrue(entered.await(2, TimeUnit.SECONDS), "写锁要先被占住");

            PlanExecutor.Sink sink = new PlanExecutor.Sink() {
                @Override
                public void commit(PlanExecutionRepository.Execution execution) {
                    repo.append(execution);
                }

                @Override
                public void recordFire(String planId, boolean succeeded, String error) {
                    repo.appendFire(new PlanExecutionRepository.FireRecord(
                            DEVICE, planId, succeeded, error));
                }
            };
            PlanExecutor executor = new PlanExecutor(DEVICE, sink);
            long start = System.nanoTime();
            executor.execute(plain(), "p", 2001, AT,
                    comm(new CopyOnWriteArrayList<>(), null), true,
                    (ceid, at) -> null, false);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(elapsedMs < 300,
                    "DB 写事务被占住时执行器不得等待（实测 " + elapsedMs + "ms）");
            release.countDown();
            holder.join(3_000);

            for (int waited = 0; waited < 40
                    && repo.query(DEVICE, null, null, true, 10).isEmpty(); waited++) {
                Thread.sleep(50);
            }
            assertEquals(1, repo.query(DEVICE, null, null, true, 10).size(),
                    "台账最终由写线程落库");
            RuntimeStateRepository states = new RuntimeStateRepository(db);
            List<RuntimeStateRepository.PlanStateRow> rows = states.planStates(DEVICE);
            assertEquals(1, rows.size(), "fire 计数与台账同批落库");
            assertEquals(1, rows.get(0).fireCount());
            assertEquals(0, rows.get(0).skippedCount());
        }
    }

    /* ------------------------------------------------------------------ */
    /* helpers                                                            */
    /* ------------------------------------------------------------------ */

    /** 内存台账捕获器：commit / fire / SSE 摘要都只读内存，不碰 DB。 */
    private static final class Recorder implements PlanExecutor.Sink {
        private final List<PlanExecutionRepository.Execution> ledger = new CopyOnWriteArrayList<>();
        private final List<String> fires = new CopyOnWriteArrayList<>();
        private final List<String> notices = new CopyOnWriteArrayList<>();
        private final List<Boolean> immediate = new CopyOnWriteArrayList<>();

        @Override
        public void commit(PlanExecutionRepository.Execution execution) {
            this.ledger.add(execution);
        }

        @Override
        public void recordFire(String planId, boolean succeeded, String error) {
            this.fires.add(planId + "|" + succeeded + "|" + (error == null ? "" : error));
        }

        @Override
        public void publish(String payloadJson, boolean immediateFlag) {
            this.notices.add(payloadJson);
            this.immediate.add(immediateFlag);
        }
    }

    private static EventPlan plain() {
        return plan("p", 2001, List.of(), EventPlan.OFFLINE_SKIP, EventPlan.FAILURE_KEEP);
    }

    private static EventPlan plan(String id, int ceid, List<PlanAction> actions,
                                  String offlinePolicy, String onSendFailure) {
        return new EventPlan(id, ceid, new Trigger.At(0L), actions,
                offlinePolicy, onSendFailure);
    }


    private static SecsCommunicator comm(List<String> sends, List<String> order) {
        return comm(sends, order, args -> { });
    }

    private static SecsCommunicator comm(List<String> sends, List<String> order,
                                         Consumer<Object[]> onSend) {
        return proxy((proxy, method, args) -> {
            if ("send".equals(method.getName()) && args != null && args.length >= 4) {
                sends.add("S" + args[0] + "F" + args[1] + ":w=" + args[2]);
                if (order != null) {
                    order.add("send");
                }
                onSend.accept(args);
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static SecsCommunicator commThrows(String message) {
        return proxy((proxy, method, args) -> {
            if ("send".equals(method.getName())) {
                throw new IllegalStateException(message);
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static SecsCommunicator proxy(java.lang.reflect.InvocationHandler handler) {
        return (SecsCommunicator) Proxy.newProxyInstance(
                SecsCommunicator.class.getClassLoader(),
                new Class<?>[] {SecsCommunicator.class}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == Optional.class) {
            return Optional.empty();
        }
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
    }

    private static int count(String text, String token) {
        int total = 0;
        for (int at = text.indexOf(token); at >= 0; at = text.indexOf(token, at + token.length())) {
            total++;
        }
        return total;
    }
}
