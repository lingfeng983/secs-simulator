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
 * 主机 S1F3 采集器「收到启动 CEID 却没开始采集」的回归测试。
 *
 * <p>用户反馈的第 9 条：配置下发了，设备也上报了启动 CEID，采集器却纹丝不动。
 * 三个原因分别在这里被钉住：</p>
 * <ol>
 *   <li>实例从 host 表重建时没有恢复 {@code collector_json}，重连后内存里
 *       {@code collectorConfig == null}，{@code onS6f11} 直接 return；</li>
 *   <li>CEID 判定原先挂在 {@code onPrimary} 自动应答之后，S6F12 应答抛异常
 *       就整个跳过，改成挂在接收帧的 pass-through 上；</li>
 *   <li>{@code onS6f11} 原先要求 {@code open}，链路建立窗口里早退丢帧。</li>
 * </ol>
 *
 * <p>测试不建立真实 socket：用动态代理把 communicator 的 pass-through listener
 * 捕获下来，直接喂一条 S6F11，观察 {@code collectorJson()} 的 running 位。</p>
 */
class HostCollectorCeidTest {

    private static final int START_CEID = 101;
    private static final int STOP_CEID = 102;

    /** 周期给到 60 s：首拍会跑一次（communicator 为 null 会安全返回），之后不干扰断言。 */
    private static final String COLLECTOR_CONFIG = """
            {"startCeid":101,"stopCeid":102,"intervalMs":60000,
             "svids":[{"id":401,"name":"T","unit":"C"}]}
            """;

    @Test
    void fromViewRestoresCollectorConfig() {
        HostSimulator sim = restoredSimulator();

        JsonHub state = state(sim);
        assertTrue(flag(state, "configured"), "重建实例后采集配置必须还在");
        assertEquals(START_CEID, state.get("startCeid").optionalInt().orElse(-1));
        assertEquals(STOP_CEID, state.get("stopCeid").optionalInt().orElse(-1));
        assertFalse(flag(state, "running"));
    }

    @Test
    void receivePassThroughStartsAndStopsCollector() throws Exception {
        HostSimulator sim = restoredSimulator();
        setOpen(sim, true);
        SecsMessagePassThroughListener listener = capturePassThrough(sim);
        assertNotNull(listener, "安装 pass-through 时必须注册 S6F11 监听");

        /* 标准 L[3]{DATAID, CEID, REPORTS} */
        listener.passThrough(s6f11(Secs2.getBuilder().list(
                Secs2.getBuilder().uint4(1000),
                Secs2.getBuilder().uint4(START_CEID),
                Secs2.getBuilder().list())));
        assertTrue(flag(state(sim), "running"), "收到启动 CEID 必须触发 S1F3 采集");

        listener.passThrough(s6f11(Secs2.getBuilder().list(
                Secs2.getBuilder().uint4(1000),
                Secs2.getBuilder().uint4(STOP_CEID),
                Secs2.getBuilder().list())));
        assertFalse(flag(state(sim), "running"), "收到停止 CEID 必须停下采集");
    }

    @Test
    void simplifiedL2BodyAndUnrelatedFramesAreHandled() throws Exception {
        HostSimulator sim = restoredSimulator();
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
        assertFalse(flag(state(sim), "running"));

        /* 部分设备发 L[2]{CEID, REPORTS}：第 2 项是列表，要退回第 1 项 */
        listener.passThrough(s6f11(Secs2.getBuilder().list(
                Secs2.getBuilder().uint4(START_CEID),
                Secs2.getBuilder().list())));
        assertTrue(flag(state(sim), "running"), "L[2] 简写报文也要能启动采集");

        /* 无关 CEID 不该把正在跑的采集停掉 */
        listener.passThrough(s6f11(Secs2.getBuilder().list(
                Secs2.getBuilder().uint4(1000),
                Secs2.getBuilder().uint4(9999),
                Secs2.getBuilder().list())));
        assertTrue(flag(state(sim), "running"));
        sim.collectorStopManual();
    }

    /* ---------- 夹具 ---------- */

    private static HostSimulator restoredSimulator() {
        HostView view = new HostView(
                "host-ceid-regression", "CEID 回归", "127.0.0.1", 5000, 1,
                null, COLLECTOR_CONFIG, null, false, "", 1L, 0L, 0L);
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

    private static boolean flag(JsonHub state, String key) {
        JsonHub value = state.get(key);
        return value != null && value.optionalBoolean().orElse(false);
    }
}
