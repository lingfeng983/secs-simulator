package com.shimizukenta.secssim.runtime.session;

import com.shimizukenta.secssim.app.session.HsmsConnectionTarget;
import com.shimizukenta.secssim.app.session.SessionRuntime;
import com.shimizukenta.secs.AbstractSecsMessage;
import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.hsmsss.HsmsSsProtocol;
import com.shimizukenta.secssim.runtime.sml.SmlParser;
import com.shimizukenta.secssim.runtime.sml.SmlParseException;
import com.shimizukenta.secssim.runtime.sml.SmlRenderer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主机模拟器：以<b>主机角色</b>连到一台设备会话，补上 S2F33/35/37
 * 这类"只有主机能发"的报文的界面入口。
 *
 * <p>设备只接受一个选中的主机连接：本模拟器 open 失败（ALREADY_USED）
 * 通常意味着真主机已经在线——这是正确行为，不是缺陷。</p>
 *
 * <p>收到设备的 W 位 primary（S6F11/S6F1/S2F17）时按 E5 自动应答
 * S6F12 / S6F2 / S2F18，让设备侧的事件与对钟流程能走通。</p>
 */
public final class HostSimulator {

    private final String sessionId;
    private final HsmsConnectionTarget target;
    private final AtomicBoolean open = new AtomicBoolean(false);
    private volatile SecsCommunicator communicator;
    private volatile String lastError = "";

    private HostSimulator(String sessionId, HsmsConnectionTarget target) {
        this.sessionId = sessionId;
        this.target = target;
    }

    /**
     * 连接到设备会话。
     *
     * @param runtime 目标会话
     * @return 模拟器
     * @throws IOException         连接失败
     * @throws IllegalStateException 已有主机占用设备
     */
    public static HostSimulator open(SessionRuntime runtime) throws IOException {
        HsmsConnectionTarget target = runtime.hostTarget();
        if (target == null) {
            throw new IOException("会话 " + runtime.id() + " 不支持主机模拟（无连接目标）");
        }

        HostSimulator sim = new HostSimulator(runtime.id(), target);
        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.protocol(HsmsSsProtocol.ACTIVE);
        config.socketAddress(new InetSocketAddress(target.bindHost(), target.port()));
        config.deviceId(target.deviceId());
        config.isEquip(false);

        SecsCommunicator comm = HsmsSsCommunicator.open(config);
        comm.addSecsMessageReceiveListener(sim::onPrimary);
        sim.communicator = comm;
        sim.open.set(true);
        Log.info("主机模拟器已连接会话 " + runtime.id() + "（" + target.bindHost() + ":" + target.port() + "）");
        return sim;
    }

    /**
     * 设备发来的 W 位 primary 的自动应答，让设备侧流程不卡 T3。
     */
    private void onPrimary(SecsMessage message) {
        int stream = message.getStream();
        int function = message.getFunction();
        try {
            if (message.wbit() && stream == 6 && (function == 11 || function == 1)) {
                this.communicator.send(message, 6, function == 11 ? 12 : 2, false,
                        com.shimizukenta.secs.secs2.Secs2.getBuilder().binary(new byte[] {0x00}));
            }
        } catch (Exception e) {
            Log.warn("主机模拟器自动应答失败（会话 " + this.sessionId + "）: " + e);
        }
    }

    /**
     * 以主机角色发送一条 SML。
     *
     * @param sml SML 文本
     * @return 与设备 sendSml 同形状的结果 JSON
     * @throws IOException 发送失败
     */
    public String sendSml(String sml) throws IOException {
        SecsCommunicator comm = this.communicator;
        if (comm == null || !this.open.get()) {
            throw new IllegalStateException("主机模拟器未连接");
        }

        SmlParser.Message message;
        try {
            message = SmlParser.parse(sml);
        } catch (SmlParseException e) {
            throw new IllegalArgumentException("SML 解析失败: " + e.getMessage(), e);
        }

        long startedAt = System.currentTimeMillis();
        Optional<SecsMessage> reply;
        boolean waitTimedOut = false;
        try {
            reply = comm.send(message.stream(), message.function(), message.wbit(), message.body());
        } catch (com.shimizukenta.secs.SecsWaitReplyMessageException e) {
            reply = Optional.empty();
            waitTimedOut = true;
        } catch (com.shimizukenta.secs.SecsSendMessageException e) {
            throw new IOException("发送失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("发送被中断", e);
        } catch (com.shimizukenta.secs.SecsException e) {
            throw new IOException("发送失败: " + e.getMessage(), e);
        }
        long elapsedMs = System.currentTimeMillis() - startedAt;

        String replySml = reply.map(value -> SmlRenderer.render(value.secs2())).orElse(null);
        String sentSml = message.body().isEmpty() ? "" : SmlRenderer.render(message.body());
        com.shimizukenta.jsonhub.JsonHubBuilder b = com.shimizukenta.jsonhub.JsonHub.getBuilder();
        return b.object(
                b.pair("ok", true),
                b.pair("stream", message.stream()),
                b.pair("function", message.function()),
                b.pair("wbit", message.wbit()),
                b.pair("sentSml", sentSml),
                b.pair("timedOut", waitTimedOut || (message.wbit() && reply.isEmpty())),
                b.pair("replyStream", reply.map(SecsMessage::getStream).orElse(0)),
                b.pair("replyFunction", reply.map(SecsMessage::getFunction).orElse(0)),
                b.pair("replyWbit", reply.map(SecsMessage::wbit).orElse(false)),
                b.pair("replySml", replySml),
                b.pair("elapsedMs", elapsedMs))
                .toJson();
    }

    /**
     * 关闭连接。
     */
    public void close() {
        if (this.open.compareAndSet(true, false)) {
            SecsCommunicator comm = this.communicator;
            if (comm != null) {
                try {
                    comm.close();
                } catch (IOException e) {
                    Log.warn("主机模拟器关闭失败（会话 " + this.sessionId + "）: " + e);
                }
            }
            this.communicator = null;
            Log.info("主机模拟器已断开会话 " + this.sessionId);
        }
    }

    /**
     * 连接状态。
     *
     * @return 已连接返回 {@code true}
     */
    public boolean isOpen() {
        return this.open.get() && this.communicator != null;
    }

    /**
     * 最近一次错误（连接/发送）；空串表示没有。
     *
     * @return 错误描述
     */
    public String lastError() {
        return this.lastError;
    }

    /**
     * 状态 JSON。
     *
     * @return {@code {"open":true,"target":"127.0.0.1:5000"}}
     */
    public String stateJson() {
        com.shimizukenta.jsonhub.JsonHubBuilder b = com.shimizukenta.jsonhub.JsonHub.getBuilder();
        return b.object(
                b.pair("open", this.isOpen()),
                b.pair("target", this.target.bindHost() + ":" + this.target.port()))
                .toJson();
    }
}
