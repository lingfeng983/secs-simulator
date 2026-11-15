package com.shimizukenta.secssim.runtime.hostsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.SecsMessagePassThroughListener;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secssim.store.HostRepository.HostView;

/**
 * 主机 S1F3 采集器的回归测试：CEID 触发启停、重复启动幂等、以及「一台主机多份采集器」。
 *
 * <p>前三条是历史问题：</p>
 * <ol>
 *   <li>实例从 host 表重建时没有恢复 {@code collector_json}，重连后内存里
 *       {@code collectorConfig == null}，{@code onS6f11} 直接 return；</li>
 *   <li>CEID 判定原先挂在 {@code onPrimary} 自动应答之后，S6F12 应答抛异常
 *       就整个跳过，改成挂在接收帧的 pass-through 上；</li>
 *   <li>点过启动之后界面又补发一次启动，把正在跑的轮询按回待命，表现成
 *       「只收到一两条 S1F3 就没数据」。</li>
 * </ol>
 *
 * <p>第四条是 3.1 的新要求：一台主机可以同时挂多份采集计划（A 与 B 并行采），
 * 每份只认自己的启停 CEID，互不干扰。</p>
 *
 * <p>测试不建立真实 socket：用动态代理把 communicator 的 pass-through listener
 * 捕获下来，直接喂 S6F11，观察 {@code collectorJson()} 的各计划状态。</p>
 */
class HostCollectorCeidTest {

    private static final int START_CEID = 101;
    private static final int STOP_CEID = 102;

    /** 老格式（根上直接是单份配置）：升级后仍必须能恢复成默认计划。 */
    private static final String LEGACY_CONFIG = """
            {"startCeid":101,"stopCeid":102,"intervalMs":60000,
             "svids":[{"id":401,"name":"T","unit":"C"}]}
            """;

    /** 新格式：两份计划，各自一对启停 CEID 与各自的 SVID。 */
    private static final String TWO_PLAN_CONFIG = """
            {"collectors":[
              {"planId":"plan-a","startCeid":201,"stopCeid":202,"intervalMs":60000,
               "svids":[{"id":401,"name":"T","unit":"C"}]},
              {"planId":"plan-b","startCeid":301,"stopCeid":302,"intervalMs":60000,
               "svids":[{"id":402,"name":"P","unit":"kPa"}]}
            ]}
            """;

    @Test
    void legacySingleConfigRestoresAsDefaultPlan() {
        HostSimulator sim = simulatorWith(LEGACY_CONFIG);

        JsonHub root = state(sim);
        assertEquals(1, root.get("collectors").length(), "单份老配置恢复成一份采集器");
        JsonHub plan = plan(root, HostCollectors.DEFAULT_PLAN_ID);
        assertNotNull(plan, "老配置落到默认计划上");
        assertTrue(flag(plan, "configured"), "重建实例后采集配置必须还在");
        assertEquals(START_CEID, intOf(plan, "startCeid"));
        assertEquals(STOP_CEID, intOf(plan, "stopCeid"));
        assertFalse(flag(plan, "running"));
    }

    /**
     * 两份计划并行：A 的启动 CEID 只启动 A，B 照旧等自己的 CEID；
     * 两份都跑起来之后，A 的停止 CEID 也只停 A。
     */
    @Test
    void twoPlansCollectIndependently() throws Exception {
        HostSimulator sim = simulatorWith(TWO_PLAN_CONFIG);
        setOpen(sim, true);
        SecsMessagePassThroughListener listener = capturePassThrough(sim);

        JsonHub root = state(sim);
        assertEquals(2, root.get("collectors").length(), "两份配置都要恢复");
        assertTrue(flag(plan(root, "plan-a"), "configured"));
        assertTrue(flag(plan(root, "plan-b"), "configured"));

        listener.passThrough(s6f11(Secs2.getBuilder().list(
                Secs2.getBuilder().uint4(1000),
                Secs2.getBuilder().uint4(201),
                Secs2.getBuilder().list())));
        root = state(sim);
        assertTrue(flag(plan(root, "plan-a"), "running"), "CEID 201 必须启动 plan-a");
        assertFalse(flag(plan(root, "plan-b"), "running"), "plan-a 的启动 CEID 不能顺带启动 plan-b");
        assertTrue(flag(plan(root, "plan-b"), "armed"), "plan-b 应继续待命");

        listener.passThrough(s6f11(Secs2.getBuilder().list(
                Secs2.getBuilder().uint4(1000),
                Secs2.getBuilder().uint4(301),
                Secs2.getBuilder().list())));
        root = state(sim);
        assertTrue(flag(plan(root, "plan-a"), "running"), "plan-b 启动不能把 plan-a 停掉");
        assertTrue(flag(plan(root, "plan-b"), "running"), "CEID 301 必须启动 plan-b");
        assertTrue(flag(root, "running"), "顶层 running 是「任意一份在采」的汇总");

        listener.passThrough(s6f11(Secs2.getBuilder().list(
                Secs2.getBuilder().uint4(1000),
                Secs2.getBuilder().uint4(202),
                Secs2.getBuilder().list())));
        root = state(sim);
        assertFalse(flag(plan(root, "plan-a"), "running"), "CEID 202 停 plan-a");
        assertTrue(flag(plan(root, "plan-b"), "running"), "plan-a 的停止 CEID 不能连累 plan-b");

        sim.collectorStopManual();
        root = state(sim);
        assertFalse(flag(root, "running"), "不带计划的停止命令停全部");
    }

    /**
     * 删采集方案 = 停掉并移除那份采集器。
     *
     * <p>不这么做会留个"孤儿"：后台还在按周期采、还会跟着配置持久化，重启后复活，
     * 而界面上已经没有它那一行。</p>
     */
    @Test
    void removingPlanStopsAndDropsThatCollector() throws Exception {
        HostSimulator sim = simulatorWith(TWO_PLAN_CONFIG);
        setOpen(sim, true);
        SecsMessagePassThroughListener listener = capturePassThrough(sim);

        listener.passThrough(s6f11(Secs2.getBuilder().list(
                Secs2.getBuilder().uint4(1000),
                Secs2.getBuilder().uint4(201),
                Secs2.getBuilder().list())));
        assertTrue(flag(plan(state(sim), "plan-a"), "running"), "先让 plan-a 跑起来");

        assertTrue(sim.collectorRemove("plan-a"), "删除返回 true");

        JsonHub root = state(sim);
        assertEquals(1, root.get("collectors").length(), "被删的那份不该再出现在状态里");
        assertNotNull(plan(root, "plan-b"), "其它计划必须原样保留");
        assertFalse(sim.collectorConfigJson().contains("plan-a"), "持久化的配置里也要删掉");
        assertFalse(sim.collectorRemove("plan-a"), "重复删除返回 false");
    }

    @Test
    void receivePassThroughStartsAndStopsCollector() throws Exception {
        HostSimulator sim = simulatorWith(LEGACY_CONFIG);
        setOpen(sim, true);
        SecsMessagePassThroughListener listener = capturePassThrough(sim);
        assertNotNull(listener, "安装 pass-through 时必须注册 S6F11 监听");

        /* 标准 L[3]{DATAID, CEID, REPORTS} */
        listener.passThrough(s6f11(Secs2.getBuilder().list(
                Secs2.getBuilder().uint4(1000),
                Secs2.getBuilder().uint4(START_CEID),
                Secs2.getBuilder().list())));
        assertTrue(flag(plan(state(sim), HostCollectors.DEFAULT_PLAN_ID), "running"),
                "收到启动 CEID 必须触发 S1F3 采集");

        listener.passThrough(s6f11(Secs2.getBuilder().list(
                Secs2.getBuilder().uint4(1000),
                Secs2.getBuilder().uint4(STOP_CEID),
                Secs2.getBuilder().list())));
        assertFalse(flag(plan(state(sim), HostCollectors.DEFAULT_PLAN_ID), "running"),
                "收到停止 CEID 必须停下采集");
    }

    @Test
    void simplifiedL2BodyAndUnrelatedFramesAreHandled() throws Exception {
        HostSimulator sim = simulatorWith(LEGACY_CONFIG);
        setOpen(sim, true);
        SecsMessagePassThroughListener listener = capturePassThrough(sim);

        /* 非 S6F11 帧不得误触发 */
        SecsMessage other = (SecsMessage) Proxy.newProxyInstance(
                SecsMessage.class.getClassLoader(),
                new Class<?>[] {SecsMessage.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getStream" -> 6;
                    case "getFunction" -> 1;
                    case "secs2" -> Secs2.getBuilder().list();
                    default -> null;
                });
        listener.passThrough(other);
        assertFalse(flag(plan(state(sim), HostCollectors.DEFAULT_PLAN_ID), "running"));

        /* 部分设备发 L[2]{CEID, REPORTS}：第 2 项是列表，要退回第 1 项 */
        listener.passThrough(s6f11(Secs2.getBuilder().list(
                Secs2.getBuilder().uint4(START_CEID),
                Secs2.getBuilder().list())));
        assertTrue(flag(plan(state(sim), HostCollectors.DEFAULT_PLAN_ID), "running"),
                "L[2] 简写报文也要能启动采集");

        /* 无关 CEID 不该把正在跑的采集停掉 */
        listener.passThrough(s6f11(Secs2.getBuilder().list(
                Secs2.getBuilder().uint4(1000),
                Secs2.getBuilder().uint4(9999),
                Secs2.getBuilder().list())));
        assertTrue(flag(plan(state(sim), HostCollectors.DEFAULT_PLAN_ID), "running"));
        sim.collectorStopManual();
    }

    /**
     * 「收到启动 CEID 之后只发了一两条 S1F3 就没数据了」的回归。
     *
     * <p>界面的兜底逻辑：看到启动 CEID 的 S6F11 就再发一次「启动」。它每 2 秒才刷新
     * 一次采集器状态，所以报文到达时快照往往还是 running=false。老实现的手动启动
     * 无条件走 standby（stop() 掉轮询执行器），于是采集器刚跑起来就被按回待命，
     * 而那个 CEID 通常只来一次——现场表现就是一两条 S1F3 之后彻底安静。
     * 这里钉住"重复的手动启动必须幂等"。</p>
     */
    @Test
    void repeatedManualStartKeepsRunningCollector() throws Exception {
        HostSimulator sim = simulatorWith(LEGACY_CONFIG);
        setOpen(sim, true);
        SecsMessagePassThroughListener listener = capturePassThrough(sim);

        listener.passThrough(s6f11(Secs2.getBuilder().list(
                Secs2.getBuilder().uint4(1000),
                Secs2.getBuilder().uint4(START_CEID),
                Secs2.getBuilder().list())));
        assertTrue(flag(plan(state(sim), HostCollectors.DEFAULT_PLAN_ID), "running"),
                "收到启动 CEID 必须开始采集");

        sim.collectorStartManual();

        JsonHub after = plan(state(sim), HostCollectors.DEFAULT_PLAN_ID);
        assertTrue(flag(after, "running"), "重复的手动启动不能把正在跑的采集按回待命");
        assertFalse(flag(after, "armed"), "armed 与 running 不能同时为真（armed 意味着已停采集）");
        sim.collectorStopManual();
    }

    /* ---------- 夹具 ---------- */

    private static HostSimulator simulatorWith(String collectorJson) {
        HostView view = new HostView(
                "host-ceid-regression", "CEID 回归", "127.0.0.1", 5000, 1,
                null, collectorJson, null, false, "", 1L, 0L, 0L);
        return HostSimulator.fromView(view, null);
    }

    /** 只把链路标志置位；communicator 保持 null，采集首拍会安全跳过。 */
    private static void setOpen(HostSimulator sim, boolean value) throws Exception {
        Field field = HostSimulator.class.getDeclaredField("open");
        field.setAccessible(true);
        ((AtomicBoolean) field.get(sim)).set(value);
    }

    /** 安装 pass-through 并把第一个接收 listener 拿出来（bus 为 null 时只注册这一条）。 */
    private static SecsMessagePassThroughListener capturePassThrough(HostSimulator sim) throws Exception {
        AtomicReference<SecsMessagePassThroughListener> captured = new AtomicReference<>();
        SecsCommunicator comm = (SecsCommunicator) Proxy.newProxyInstance(
                SecsCommunicator.class.getClassLoader(),
                new Class<?>[] {SecsCommunicator.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addReceiveMessagePassThroughListener")) {
                        captured.compareAndSet(null, (SecsMessagePassThroughListener) args[0]);
                        return Boolean.TRUE;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return Boolean.FALSE;
                    }
                    if (method.getReturnType() == int.class) {
                        return Integer.valueOf(0);
                    }
                    return null;
                });
        Method install = HostSimulator.class.getDeclaredMethod("installPassThrough", SecsCommunicator.class);
        install.setAccessible(true);
        install.invoke(sim, comm);
        return captured.get();
    }

    private static SecsMessage s6f11(Secs2 body) {
        return (SecsMessage) Proxy.newProxyInstance(
                SecsMessage.class.getClassLoader(),
                new Class<?>[] {SecsMessage.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getStream" -> 6;
                    case "getFunction" -> 11;
                    case "secs2" -> body;
                    case "wbit" -> Boolean.FALSE;
                    case "deviceId", "sessionId" -> Integer.valueOf(0);
                    case "header10Bytes" -> new byte[10];
                    case "toString" -> "S6F11(test)";
                    default -> null;
                });
    }

    private static JsonHub state(HostSimulator sim) {
        return JsonHub.fromJson(sim.collectorJson());
    }

    /** 按计划 id 取那一份的状态；没有返回 null。 */
    private static JsonHub plan(JsonHub root, String planId) {
        JsonHub list = root.get("collectors");
        if (list == null || !list.isArray()) {
            return null;
        }
        for (int i = 0; i < list.length(); i += 1) {
            JsonHub item = list.get(i);
            if (planId.equals(item.get("planId") == null ? "" : item.get("planId").optionalString().orElse(""))) {
                return item;
            }
        }
        return null;
    }

    private static boolean flag(JsonHub node, String key) {
        if (node == null) {
            return false;
        }
        JsonHub value = node.get(key);
        return value != null && value.optionalBoolean().orElse(false);
    }

    private static int intOf(JsonHub node, String key) {
        JsonHub value = node == null ? null : node.get(key);
        return value == null ? -1 : value.optionalInt().orElse(-1);
    }
}
