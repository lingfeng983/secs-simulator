package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.hsmsss.HsmsSsProtocol;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.EngineClock;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.runtime.hostsim.HostSimulator;
import com.shimizukenta.secssim.runtime.session.DemoEquipment;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;

/**
 * P0-1 的真实主站复验。
 *
 * <p>与 {@code HsmsSsIntegrationTest} 的裸 socket 用例互补：这里的主站是<b>产品自己的
 * 主机模拟器</b>（真实 HSMS-SS ACTIVE + SELECT + SECS-II 事务），设备也是真实的
 * {@link HsmsSessionRuntime}。场景是现场最常见的"主机重连重叠 / 端口扫描"：
 * 已选中的主站链路之外，又来了第二条主站连接。</p>
 *
 * <p>修复前的缺陷：第二条连接结束时，被动端的 {@code finally} 会把
 * <b>别人</b>的选中连接注销掉，设备侧进入 NOT_CONNECTED —— 主站还在正常发报文，
 * 设备却"假掉线"。本用例断言第二条连接<b>存在期间和离开之后</b>，
 * 主站的 S1F1/S1F2 都仍然走得通。</p>
 */
class SessionSelectedLinkSurvivesRogueHostTest {

    /** 第二条主站连接存在多久（够它重试几次 SELECT 并被拒）。 */
    private static final long ROGUE_DWELL_MS = 800L;

    @Test
    @Timeout(90)
    void selectedHostKeepsWorkingWhileAndAfterSecondHostConnects() throws Exception {
        int port = freePort();

        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);
        assertTrue(compiled.ok(), () -> "内置演示设备不合法: " + compiled.issues());

        SessionSpec spec = new SessionSpec("p0-1", true, "127.0.0.1", port, 10, 20240601L, demo);
        HsmsSessionRuntime device = new HsmsSessionRuntime(spec, compiled.equipment(),
                EngineClock.manual(0L));
        device.open();

        HostSimulator mainHost = null;
        SecsCommunicator rogue = null;

        try {
            /* 真实主站上线：ACTIVE + SELECT，随后做一次真实事务 */
            mainHost = HostSimulator.open("host-main", "main", device.hostTarget(), null);
            SessionTestSupport.awaitPeer(device);

            JsonHub first = JsonHub.fromJson(mainHost.sendSml("S1F1 W."));
            assertTrue(first.get("ok").booleanValue(), "主机首次 S1F1 发送失败");
            assertFalse(first.get("timedOut").booleanValue(), "主机首次 S1F1 不应超时");
            assertEquals(2, first.get("replyFunction").intValue(), "应收到 S1F2");

            /* 第二条主站连接：真实 HSMS ACTIVE 栈。它的 SELECT 会被拒（ALREADY_USED），
               并在 T5 后反复重试——这正是现场"主机重连重叠/端口扫描"的样子。 */
            HsmsSsCommunicatorConfig rogueConfig = new HsmsSsCommunicatorConfig();
            rogueConfig.protocol(HsmsSsProtocol.ACTIVE);
            rogueConfig.socketAddress(new InetSocketAddress("127.0.0.1", port));
            rogueConfig.deviceId(10);
            rogueConfig.isEquip(false);
            rogueConfig.timeout().t3(2F);
            rogueConfig.timeout().t5(0.3F);
            rogue = HsmsSsCommunicator.open(rogueConfig);

            Thread.sleep(ROGUE_DWELL_MS);

            /* 断言 A：第二条连接还在时，设备仍认为主站在线 */
            assertTrue(device.hasPeer(),
                    "第二条主站连接存在期间，已选中的链路不能被注销（修复前此处已是假掉线）");

            rogue.close();
            rogue = null;

            /* 断言 B：第二条连接离开后，设备仍认为主站在线 */
            SessionTestSupport.awaitPeer(device);
            assertTrue(device.hasPeer(), "第二条主站连接离开后，已选中的链路不能被注销");

            /* 断言 C：真实事务仍能走通——这是"设备没假掉线"的最终证据 */
            JsonHub after = JsonHub.fromJson(mainHost.sendSml("S1F1 W."));
            assertTrue(after.get("ok").booleanValue(), "主站链路在事件后发送失败");
            assertFalse(after.get("timedOut").booleanValue(),
                    "第二条连接离开后，已选中链路必须还能完成 S1F1/S1F2");
            assertEquals(2, after.get("replyFunction").intValue());

            /* 断言 D：S1F3 取数也正常（证明会话数据面没被扰动） */
            JsonHub read = JsonHub.fromJson(mainHost.sendSml("S1F3 W. <L [2] <U4 1001> <U4 1101>>"));
            assertTrue(read.get("ok").booleanValue(), "S1F3 发送失败");
            assertFalse(read.get("timedOut").booleanValue(), "S1F3 不应超时");
            assertEquals(4, read.get("replyFunction").intValue(), "应收到 S1F4");
        }
        finally {
            if (rogue != null) {
                rogue.close();
            }
            if (mainHost != null) {
                mainHost.close();
            }
            device.close();
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
