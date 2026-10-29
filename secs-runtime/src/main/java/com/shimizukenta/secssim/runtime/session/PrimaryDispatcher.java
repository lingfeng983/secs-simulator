package com.shimizukenta.secssim.runtime.session;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.app.session.EquipmentControlState;
import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.gem.COMMACK;
import com.shimizukenta.secs.gem.ONLACK;
import com.shimizukenta.secs.gem.TIACK;

/**
 * 允许抛受检异常的消费者。
 *
 * <p>{@link java.util.function.Consumer} 不能抛受检异常，会把"发送失败"
 * 这类错误吞掉——现场表现为主机一直等 T3。</p>
 *
 * @param <T> 参数类型
 */
@FunctionalInterface
interface ThrowingConsumer<T> {

    /**
     * 消费一个值。
     *
     * @param value 值
     * @throws Exception 处理失败
     */
    void accept(T value) throws Exception;
}

/**
 * Primary 分发：把主机发来的报文分派到对应的处理路径。
 *
 * <p>控制状态类报文（S1F1/S1F13/S1F15/S1F17/S2F17/S2F31）委托给协议层的
 * {@code Gem}，形状已按 E30/E5 实现；其余报文走设备侧的专用处理；
 * 认不出来的回一条 S9Fx。</p>
 */
final class PrimaryDispatcher {

    /** 通信器。 */
    private final java.util.function.Supplier<SecsCommunicator> communicator;

    /** 设备侧报文处理。 */
    private final HostSideMessages hostSide;

    /** Trace（周期采样）。 */
    private final TracePump tracePump;

    /** 终端消息收件箱。 */
    private final TerminalInbox terminalInbox;

    /** 当前控制状态。 */
    private final java.util.function.Supplier<com.shimizukenta.secssim.app.session.EquipmentControlState> controlState;

    /** 迁控制状态。 */
    private final java.util.function.Consumer<com.shimizukenta.secssim.app.session.EquipmentControlState> changeControlState;

    /** S1F3 的应答（允许抛受检异常：发送失败要往上冒）。 */
    private final ThrowingConsumer<SecsMessage> replyS1F4;

    /** 最近一条 S9Fx 回应。 */
    private final AtomicReference<String> lastS9 = new AtomicReference<>("");

    /** S9Fx 回了几次。 */
    private final AtomicLong s9Replies = new AtomicLong();

    /** 出错回调。 */
    private final java.util.function.Consumer<Exception> errorSink;

    /** 会话 id（错误日志用）。 */
    private final String sessionId;

    PrimaryDispatcher(java.util.function.Supplier<SecsCommunicator> communicator,
                     HostSideMessages hostSide,
                     TracePump tracePump,
                     TerminalInbox terminalInbox,
                     java.util.function.Supplier<com.shimizukenta.secssim.app.session.EquipmentControlState> controlState,
                     java.util.function.Consumer<com.shimizukenta.secssim.app.session.EquipmentControlState> changeControlState,
                     ThrowingConsumer<SecsMessage> replyS1F4,
                     java.util.function.Consumer<Exception> errorSink,
                     String sessionId) {
        this.communicator = communicator;
        this.hostSide = hostSide;
        this.tracePump = tracePump;
        this.terminalInbox = terminalInbox;
        this.controlState = controlState;
        this.changeControlState = changeControlState;
        this.replyS1F4 = replyS1F4;
        this.errorSink = errorSink;
        this.sessionId = sessionId;
    }

    /** 最近一条 S9Fx 回应。 */
    String lastS9() {
        return this.lastS9.get();
    }

    /** S9Fx 回了几次。 */
    long s9Replies() {
        return this.s9Replies.get();
    }

    void dispatch(SecsMessage primary) {
        try {
            int stream = primary.getStream();
            int function = primary.getFunction();

            /* E5：S9F3/S9F5 只能针对无法识别的 primary 发送，迟到的 secondary 必须静默丢弃。
               协议层在 W=0 时不登记应答管理器，主机按惯例回的 S6F12/S6F2 会一路进到本方法；
               若不在这里挡掉，就会对一条 secondary 回 S9F5（SystemBytes 与主机应答相同的新 primary），
               严格的主机会走异常处理甚至断链。偶数 function 一律视为 secondary。 */
            if (function % 2 == 0) {
                return;
            }

            /* 控制状态类报文委托给协议层的 Gem：这些应答的形状（COMMACK/ONLACK/TIACK/时钟格式）
               都已按 E30/E5 实现，自己重写只会引入偏差 */
            switch (stream * 100 + function) {
                case 101 -> {
                    this.communicator.get().gem().s1f2(primary);
                    return;
                }
                case 113 -> {
                    if (this.controlState.get() == EquipmentControlState.OFFLINE) {
                        this.changeControlState.accept(EquipmentControlState.COMMUNICATING);
                    }
                    this.communicator.get().gem().s1f14(primary, COMMACK.OK);
                    return;
                }
                case 115 -> {
                    /* 同理：应答到达时"已离线"必须已生效，否则主机以为下线了，设备还在报 */
                    this.changeControlState.accept(EquipmentControlState.OFFLINE);
                    this.communicator.get().gem().s1f16(primary);
                    return;
                }
                case 117 -> {
                    /* 先迁状态再回应答：主机收到 S1F18 时，"设备已 ONLINE" 必须已经成立。
                       反过来（先回后迁）存在一个窗口——主机收到应答立刻做 S1F13 之后的动作，
                       设备这边还是 COMMUNICATING，主动上报会被状态门槛挡掉。 */
                    this.changeControlState.accept(EquipmentControlState.ONLINE);
                    this.communicator.get().gem().s1f18(primary, ONLACK.OK);
                    return;
                }
                case 217 -> {
                    this.communicator.get().gem().s2f18Now(primary);
                    return;
                }
                case 231 -> {
                    /* E30：时间格式非法应回 TIACK=1，恒回 0 等于告诉主机"已同步"，
                       而设备时钟其实没被改，主机的对时流程会带着错误继续往下走。 */
                    this.communicator.get().gem().s2f32(primary, this.hostSide.isValidTimeSet(primary) ? TIACK.OK : TIACK.NotDone);
                    return;
                }
                default -> {
                    /* 其余报文按下面的分支处理 */
                }
            }

            if (stream == 1 && function == 3) {
                this.replyS1F4.accept(primary);
                return;
            }

            if (stream == 1 && function == 11) {
                this.hostSide.replyS1F12(primary);
                return;
            }

            if (stream == 2 && function == 23) {
                this.tracePump.handleInitialize(primary);
                return;
            }

            switch (stream * 100 + function) {
                case 233 -> {
                    this.hostSide.handleDefineReport(primary);
                    return;
                }
                case 235 -> {
                    this.hostSide.handleLinkEventReport(primary);
                    return;
                }
                case 237 -> {
                    this.hostSide.handleEnableEventReport(primary);
                    return;
                }
                case 213 -> {
                    this.hostSide.replyS2F14(primary);
                    return;
                }
                case 215 -> {
                    this.hostSide.handleConstantSet(primary);
                    return;
                }
                case 241 -> {
                    this.hostSide.handleRemoteCommand(primary);
                    return;
                }
                case 1003 -> {
                    this.terminalInbox.handle(primary);
                    return;
                }
                default -> {
                    /* 其余报文交给 S9Fx 统一回答 */
                }
            }

            replyUnsupported(primary);
        } catch (Exception e) {
            this.errorSink.accept(e);
            Log.error("会话 " + this.sessionId + " 处理报文失败", e);
        }
    }

    /**
     * 对无法处理的 Primary 回 S9Fx。
     *
     * <p>E5 把“流不认识”和“功能不认识”分成 S9F3 / S9F5。
     * 不回应的后果比回错更糟：主机侧只会看到 T3 超时，无法区分
     * “设备不支持”与“设备坏了”。</p>
     *
     * <p>S9 本身不再回 S9，避免两端互打形成死循环。</p>
     */
    private void replyUnsupported(SecsMessage primary) {
        int stream = primary.getStream();
        if (stream == 9) {
            return;
        }

        try {
            if (isKnownStream(stream)) {
                this.communicator.get().gem().s9f5(primary);
                this.lastS9.set("S9F5 ← S" + stream + "F" + primary.getFunction() + "（功能不支持）");
            } else {
                this.communicator.get().gem().s9f3(primary);
                this.lastS9.set("S9F3 ← S" + stream + "F" + primary.getFunction() + "（流不支持）");
            }
            this.s9Replies.incrementAndGet();
        } catch (Exception e) {
            this.errorSink.accept(e);
            Log.error("会话 " + this.sessionId + " 回复 S9Fx 失败", e);
        }
    }

    private static boolean isKnownStream(int stream) {
        return stream == 1 || stream == 2 || stream == 5 || stream == 6 || stream == 10;
    }
}
