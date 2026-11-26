package com.shimizukenta.secssim.runtime.session;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secssim.app.session.EquipmentControlState;
import com.shimizukenta.secssim.app.session.SessionRuntime;
import com.shimizukenta.secssim.app.session.SessionState;
import com.shimizukenta.secssim.core.ManualEngineClock;

/**
 * 会话的链路生命周期：open / disconnect / close 与链路相位。
 *
 * <p>会话对象本身可复用（disconnect 后再 open），所以建链/断链的状态推进都在这里，
 * 会话层只保留内存态（模型 / 值来源 / 编排 / 钉值 / 计划启用态）。</p>
 */
final class SessionLifecycle {

    /** S6F11 等 ACK 的队列上限：主机不答 S6F12 时不能无限堆积。 */
    private static final int ACK_QUEUE_CAPACITY = 256;

    private final HsmsSessionRuntime host;

    /**
     * 生命周期锁：{@code open / disconnect / close} 互斥。
     *
     * <p>没有这把锁时，两个并发「连接」（HTTP 两次点击、或 connect 撞上 reload）
     * 都能通过 {@code communicator == null} 的检查，各建一条链路；后写者覆盖字段，
     * 先建的那条失去唯一引用，socket、协议线程、端口全部泄漏，而且两套心跳会
     * 同时推进调度器（同一事件被重复发出）。</p>
     */
    private final Object lifecycleLock = new Object();

    /**
     * 相位跳变锁：只保护 {@code linkPhase} 的读-改-写。
     *
     * <p>相位来自三条线程（协议回调、心跳、HTTP 命令），裸 volatile 上的读改写会丢更新：
     * 例如 {@code close()} 写入 STOPPED 后，迟到的 {@code communicatable(false)}
     * 又把它改回 DISCONNECTED，界面上就出现「已销毁但显示已断开」。通知放在锁外发，
     * 避免持锁做持久化与 SSE 广播。</p>
     */
    private final Object phaseLock = new Object();

    /** 链路相位（3.0 需求 1）；变化经 {@code linkListener} 通知（持久化 + SSE 在装配层接）。 */
    private volatile SessionRuntime.LinkPhase linkPhase = SessionRuntime.LinkPhase.STOPPED;
    private volatile BiConsumer<String, SessionRuntime.LinkPhase> linkListener;

    SessionLifecycle(HsmsSessionRuntime host) {
        this.host = host;
    }

    ExecutorService newAckSender(String threadName) {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(ACK_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, threadName);
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /** 挂链路相位监听（幂等覆盖）。 */
    void onLinkPhase(BiConsumer<String, SessionRuntime.LinkPhase> listener) {
        this.linkListener = listener;
    }

    SessionRuntime.LinkPhase linkPhase() {
        return this.linkPhase;
    }

    void disconnect() {
        synchronized (this.lifecycleLock) {
            this.disconnectLocked();
        }
    }

    void reconnect() throws IOException {
        /* 对象复用：open() 在 communicator 为空时重建链路，内存态天然保留 */
        this.open();
    }

    void open() throws IOException {
        synchronized (this.lifecycleLock) {
            this.openLocked();
        }
    }

    void close() {
        synchronized (this.lifecycleLock) {
            this.closeLocked();
        }
    }

    private void changeLinkPhase(SessionRuntime.LinkPhase next) {
        boolean changed;
        synchronized (this.phaseLock) {
            changed = this.linkPhase != next;
            if (changed) {
                this.linkPhase = next;
            }
        }
        if (!changed) {
            return;
        }
        var listener = this.linkListener;
        if (listener != null) {
            try {
                listener.accept(this.host.spec.id(), next);
            } catch (RuntimeException e) {
                this.host.recordError(e);
            }
        }
    }

    private void disconnectLocked() {
        /* 只断链：模型 / 值来源 / 编排 / 钉值 / 计划启用态全留在对象里，
           reconnect() 后原样恢复（3.0 设计 §2.4.1）。 */
        this.stopTicker();
        for (ExecutorService pool : new ExecutorService[] {
                this.host.ackSender, this.host.traceAckSender, this.host.alarmAckSender }) {
            if (pool != null) {
                pool.shutdownNow();
            }
        }
        SecsCommunicator comm = this.host.communicator;
        this.host.communicator = null;
        this.host.dispatcher = null;
        this.host.terminalInbox = null;
        if (comm != null) {
            try {
                comm.close();
            } catch (IOException e) {
                this.host.recordError(e);
            }
        }
        this.host.changeControlState(EquipmentControlState.OFFLINE);
        this.host.state.set(SessionState.CLOSED);
        this.changeLinkPhase(SessionRuntime.LinkPhase.DISCONNECTED);
    }

    private void openLocked() throws IOException {
        if (this.host.communicator != null) {
            throw new IOException("session already opened: " + this.host.spec.id());
        }

        this.host.state.set(SessionState.OPENING);
        this.changeLinkPhase(SessionRuntime.LinkPhase.STARTING);
        if (this.host.ackSender == null || this.host.ackSender.isShutdown()) {
            this.host.ackSender = this.newAckSender("s6f11-ack-" + this.host.spec.id());
        }
        if (this.host.traceAckSender == null || this.host.traceAckSender.isShutdown()) {
            this.host.traceAckSender = this.newAckSender("s6f1-ack-" + this.host.spec.id());
        }
        if (this.host.alarmAckSender == null || this.host.alarmAckSender.isShutdown()) {
            this.host.alarmAckSender = this.newAckSender("s5f1-ack-" + this.host.spec.id());
        }

        try {
            /* open() 返回之前就可能收到报文（主机 SELECT 后立刻发 S1F1）。
               dispatcher 还没建好，直接 dispatch 会 NPE 丢帧——先存起来，就绪后按序补发。 */
            final Object gate = new Object();
            final List<SecsMessage> early = new ArrayList<>();
            this.host.communicator = this.host.connector.open(
                    /* 原始收帧（含 secondary 应答）只进报文监控 */
                    message -> this.host.publish("in", message),
                    message -> {
                        PrimaryDispatcher ready;
                        synchronized (gate) {
                            /* 取到局部变量再出锁用：出锁后 dispatcher 可能被
                               disconnect() 置空（旧写法会在读线程里 NPE，或把旧连接的
                               在途报文投给重连后的新 dispatcher） */
                            ready = this.host.dispatcher;
                            if (ready == null) {
                                early.add(message);
                                return;
                            }
                        }
                        ready.dispatch(message);
                    },
                    message -> this.host.publish("out", message),
                    value -> {
                        this.host.control.communicatable(value);
                        /* 链路相位跟着 communicatable 走：select 成功 = CONNECTED，
                           掉线 = DISCONNECTED（被动监听未 select 时保持 LISTENING） */
                        if (value) {
                            this.changeLinkPhase(SessionRuntime.LinkPhase.CONNECTED);
                            if (this.host.control.state() == EquipmentControlState.OFFLINE) {
                                this.host.changeControlState(EquipmentControlState.COMMUNICATING);
                            }
                        } else {
                            this.changeLinkPhase(SessionRuntime.LinkPhase.DISCONNECTED);
                            this.host.changeControlState(EquipmentControlState.OFFLINE);
                        }
                    },
                    this.host::recordFailure);
            this.host.terminalInbox = new TerminalInbox(this.host.communicator,
                    this.host.spec.id(), this.host::recordError);
            synchronized (gate) {
                this.host.dispatcher = new PrimaryDispatcher(() -> this.host.communicator,
                        this.host.hostSide,
                        this.host.tracePump,
                        this.host.terminalInbox,
                        () -> this.host.control.state(),
                        this.host::changeControlState,
                        this.host::replyS1F4,
                        this.host::recordError,
                        this.host.spec.id());
                for (SecsMessage pending : early) {
                    this.host.dispatcher.dispatch(pending);
                }
                early.clear();
            }
            this.host.state.set(SessionState.OPEN);
            /* 只有被动端在这里推进到 LISTENING（端口已绑、等主机 SELECT）。
               主动端的 CONNECTED 交给 communicatable(true) 回调：在这里强写会盖掉
               "连不上"那次回调写的 DISCONNECTED，界面上就成了假的已连接。 */
            if (this.host.spec.passive() && this.linkPhase == SessionRuntime.LinkPhase.STARTING) {
                this.changeLinkPhase(SessionRuntime.LinkPhase.LISTENING);
            }
            this.startTicker();
        } catch (IOException | RuntimeException e) {
            this.host.recordFailure(e);
            this.host.state.set(SessionState.FAILED);
            this.changeLinkPhase(SessionRuntime.LinkPhase.FAILED);
            /* connector.open 成功之后、TerminalInbox/PrimaryDispatcher 构造失败时，
               已打开的 communicator 必须关掉：否则端口/线程会跟着一个 FAILED 会话泄漏。 */
            SecsCommunicator opened = this.host.communicator;
            if (opened != null) {
                try {
                    opened.close();
                } catch (IOException closeError) {
                    this.host.recordFailure(closeError);
                } finally {
                    this.host.communicator = null;
                }
            }
            throw e;
        }
    }

    private void closeLocked() {
        this.stopTicker();
        ExecutorService sender = this.host.ackSender;
        if (sender != null) {
            sender.shutdownNow();
        }
        ExecutorService traceSender = this.host.traceAckSender;
        if (traceSender != null) {
            traceSender.shutdownNow();
        }
        ExecutorService alarmSender = this.host.alarmAckSender;
        if (alarmSender != null) {
            alarmSender.shutdownNow();
        }

        SecsCommunicator comm = this.host.communicator;
        if (comm == null) {
            this.host.state.set(SessionState.CLOSED);
            this.changeLinkPhase(SessionRuntime.LinkPhase.STOPPED);
            return;
        }

        this.host.state.set(SessionState.CLOSING);
        try {
            comm.close();
        } catch (IOException e) {
            this.host.recordError(e);
        } finally {
            this.host.communicator = null;
            this.host.dispatcher = null;
            this.host.terminalInbox = null;
            this.host.state.set(SessionState.CLOSED);
            this.changeLinkPhase(SessionRuntime.LinkPhase.STOPPED);
        }
    }

    private void startTicker() {
        /* 事件调度从"现在"起算，不补开以来的周期 */
        this.host.scheduler.start();
        this.host.ticker.start(this.host.clock instanceof ManualEngineClock, this.host::pumpNow);
    }

    private void stopTicker() {
        this.host.ticker.stop();
    }
}