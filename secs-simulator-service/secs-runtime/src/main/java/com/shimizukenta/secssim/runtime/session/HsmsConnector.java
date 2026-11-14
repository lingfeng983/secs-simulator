package com.shimizukenta.secssim.runtime.session;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.function.Consumer;

import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.hsmsss.HsmsSsProtocol;

/**
 * HSMS 连接：按会话规格把通信器建起来。
 *
 * <p>协议（主动/被动）、地址、deviceId、超时都在这里定，
 * 会话层只管"开"与"关"。</p>
 *
 * <p>这里还负责两件"设备必须诚实"的事：</p>
 * <ul>
 *   <li><b>端口可绑定性预检</b>：被动端在 open 之前先用普通 ServerSocket 试绑一次。
 *       Windows 的 {@code SO_REUSEADDR} 允许重复绑定，不预检的话端口被别的进程占着
 *       也能"启动成功"，主机却永远连不上。</li>
 *   <li><b>协议日志接线</b>：库里的 bind/accepted/reject/timeout 日志必须有监听者，
 *       否则现场排障时 {"设备起来了但连不上"} 没有任何线索。</li>
 * </ul>
 */
final class HsmsConnector {

    /**
     * 周期链路测试间隔（秒）。
     *
     * <p>E37 允许任一端发起 LINKTEST。默认关闭时，主机断电/拔网线（TCP 半开，
     * 没有 FIN/RST）会让本端读线程永久阻塞、状态永远 SELECTED——界面显示在线，
     * 报文却全进了黑洞。30 秒是"发现得够快"与"不打扰主机"之间的折中。</p>
     */
    static final float LINKTEST_SECONDS = 30.0F;

    /** 会话规格。 */
    private final SessionSpec spec;

    HsmsConnector(SessionSpec spec) {
        this.spec = spec;
    }

    /**
     * 建立通信器并挂上监听。
     *
     * <p>监听器在 {@code open()} <b>之前</b>注册：主动端的 SELECT 可能在 open 返回前就完成，
     * 之后再注册会漏掉那一次状态迁移（表现为"已经连上但 hasPeer 一直是 false"）。</p>
     *
     * @param onReceiveRaw      收到的每一条 DATA 帧（含 W 位应答；仅用于报文监控，不参与分发）
     * @param onReceive         收到主机报文（已由应答管理器过滤，只含 primary）
     * @param onSent            本机发出的报文
     * @param onState           通信状态变化
     * @param onProtocolFailure 协议层报错（bind 失败、连接被拒、超时等）；{@code null} 表示忽略
     * @return 通信器
     * @throws IOException 连接失败
     */
    SecsCommunicator open(java.util.function.Consumer<SecsMessage> onReceiveRaw,
                          java.util.function.Consumer<SecsMessage> onReceive,
                          java.util.function.Consumer<SecsMessage> onSent,
                          java.util.function.Consumer<Boolean> onState,
                          Consumer<Throwable> onProtocolFailure) throws IOException {
        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.protocol(this.spec.passive() ? HsmsSsProtocol.PASSIVE : HsmsSsProtocol.ACTIVE);
        config.socketAddress(new InetSocketAddress(this.spec.bindHost(), this.spec.port()));
        config.deviceId(this.spec.deviceId());
        config.isEquip(true);
        config.linktest(LINKTEST_SECONDS);
        applyTimeouts(config.timeout(), this.spec.timeouts());

        if (this.spec.passive()) {
            requireBindable();
        }

        SecsCommunicator comm = HsmsSsCommunicator.newInstance(config);
        comm.addSecsLogListener(log -> {
            if (onProtocolFailure == null) {
                return;
            }
            Object value = log.value().orElse(null);
            if (value instanceof Throwable t) {
                onProtocolFailure.accept(t);
            }
        });
        /* 报文监控走 pass-through：W 位的 secondary 应答会被应答管理器
           （replyMgr.put）直接消费掉，不会进普通接收队列；只挂
           addSecsMessageReceiveListener 的话，界面只看得见发出去的 S1F1，
           看不见主机回的 S1F2。pass-through 在消费之前触发，收发都能看见。 */
        comm.addReceiveMessagePassThroughListener(message -> onReceiveRaw.accept(message));
        comm.addSecsMessageReceiveListener(message -> onReceive.accept(message));
        comm.addSendedMessagePassThroughListener(message -> onSent.accept(message));
        comm.addSecsCommunicatableStateChangeListener(value -> onState.accept(value));

        try {
            comm.open();
        } catch (IOException | RuntimeException e) {
            try {
                comm.close();
            } catch (IOException giveup) {
                /* open 已经失败，close 的二次失败没有更安全的去处 */
            }
            throw e;
        }
        return comm;
    }

    /**
     * 被动端：先用不带 SO_REUSEADDR 的普通 ServerSocket 试绑一次。
     *
     * <p>只探测"外部进程是否占着这个端口"。进程内多会话的端口冲突由
     * {@code SessionLauncher.requirePortFree} 先挡；这里补的是它看不见的那一半。</p>
     *
     * @throws IOException 端口不可绑定
     */
    private void requireBindable() throws IOException {
        int port = this.spec.port();
        if (port <= 0) {
            return;
        }
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(false);
            probe.bind(new InetSocketAddress(this.spec.bindHost(), port));
        } catch (IOException e) {
            throw new IOException("端口 " + port + " 不可绑定（可能被其它进程占用）: " + e.getMessage(), e);
        }
    }

    /** 把配置里的 HSMS 超时（可选段）落到协议层；null 的键保留协议层默认。 */
    private static void applyTimeouts(com.shimizukenta.secs.SecsTimeout timeout,
                                      com.shimizukenta.secssim.app.session.HsmsTimeouts timeouts) {
        if (timeouts.t3() != null) {
            timeout.t3(timeouts.t3().floatValue());
        }
        if (timeouts.t5() != null) {
            timeout.t5(timeouts.t5().floatValue());
        }
        if (timeouts.t6() != null) {
            timeout.t6(timeouts.t6().floatValue());
        }
        if (timeouts.t7() != null) {
            timeout.t7(timeouts.t7().floatValue());
        }
        if (timeouts.t8() != null) {
            timeout.t8(timeouts.t8().floatValue());
        }
    }
}
