package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.ManualEngineClock;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.runtime.session.DemoEquipment;
import com.shimizukenta.secssim.runtime.session.HostSimulator;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;

/**
 * 主机模拟：新建本机主机实例。
 */
class HostSimCreateTest {

    /**
     * 以主机角色连一台本机设备。
     */
    @Test
    void localHostSimCanBeCreated() throws Exception {
        int port = freePort();
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);
        SessionSpec spec = new SessionSpec("cs", true, "127.0.0.1", port, 10, 1L, demo);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));
        session.open();
        try {
            HostSimulator sim = HostSimulator.open("host-1", session, null);
            assertNotNull(sim);
            assertTrue(sim.isOpen(), "新建的主机实例应是打开状态");
            sim.close();
        } finally {
            session.close();
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
