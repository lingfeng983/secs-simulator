package com.shimizukenta.secssim.runtime.session;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import com.shimizukenta.secssim.app.event.NoticeEvent;
import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.secs2.Secs2;

/**
 * 报警（S5F1）的发送与活动报警表。
 *
 * <p>报警是独立于采集事件的一条路：置位/清除走 S5F1，活动状态存在
 * 一张表里给界面看。把它从会话里拆出来，会话层只留"建连接、发报文"。</p>
 */
final class AlarmReporter {

    /** 已发出的报警条数。 */
    private final AtomicLong alarmsSent = new AtomicLong();

    /** 因 ACK 队列满而丢弃的报警条数。 */
    private final AtomicLong alarmDropped = new AtomicLong();

    /** 当前处于置位状态的报警。 */
    private final Map<Integer, HsmsSessionRuntime.AlarmState> activeAlarms = new ConcurrentHashMap<>();

    /** S5F1 的 ACK 队列：W 位会阻塞到 S5F2 或 T3（惰性取，会话开起来才有）。 */
    private final java.util.function.Supplier<ExecutorService> ackSender;

    /** 会话 id（错误日志用）。 */
    private final String sessionId;

    /** 当前连接；未连接时为 {@code null}。 */
    private final java.util.function.Supplier<SecsCommunicator> communicator;

    /** 状态通知出口。 */
    private final java.util.function.BiConsumer<String, String> notice;

    /** 记录一次错误。 */
    private final java.util.function.Consumer<Exception> errorSink;

    AlarmReporter(java.util.function.Supplier<ExecutorService> ackSender, String sessionId,
                  java.util.function.Supplier<SecsCommunicator> communicator,
                  java.util.function.BiConsumer<String, String> notice,
                  java.util.function.Consumer<Exception> errorSink) {
        this.ackSender = ackSender;
        this.sessionId = sessionId;
        this.communicator = communicator;
        this.notice = notice;
        this.errorSink = errorSink;
    }

    /** 已发出的报警条数。 */
    long alarmsSent() {
        return this.alarmsSent.get();
    }

    /** 因 ACK 队列满而丢弃的报警条数。 */
    long alarmDropped() {
        return this.alarmDropped.get();
    }

    /** 当前处于置位状态的报警。 */
    List<HsmsSessionRuntime.AlarmState> alarms() {
        return List.copyOf(this.activeAlarms.values());
    }

    /**
     * 置位或清除一条报警。
     *
     * @param alid     报警号
     * @param set      {@code true} 置位，{@code false} 清除
     * @param text     报警文本；空则自动生成
     * @param category 类别（E30：ALCD 低 3 位）
     * @throws IOException 会话未打开、参数非法或队列已满
     */
    void emit(int alid, boolean set, String text, int category) throws IOException {
        SecsCommunicator comm = this.communicator.get();
        if (comm == null) {
            throw new IOException("session not open: " + this.sessionId);
        }
        if (alid < 1) {
            throw new IOException("ALID 必须 >= 1: " + alid);
        }
        /* E30：ALCD 低 3 位是类别 bit 组合（1 安全 / 2 控制 / 4 故障…），0 无定义。
           原来的 0..7 放行了 0，主机侧会收到一个"没有类别"的报警，难以归类展示。 */
        if (category < 1 || category > 7) {
            throw new IOException("报警类别必须在 1..7（0 无定义）: " + category);
        }

        String altx = text == null || text.isBlank()
                ? (set ? "ALARM " + alid : "CLEAR " + alid)
                : text;
        /* SECS-II 的 A 是 US-ASCII。含中文等非 ASCII 字符时编码会失败，
           而真正的发送在异步 ACK 队列里：HTTP 已回 202、主机却收不到，
           现场只会看到"接口成功但设备没反应"。所以在入口就挡掉。 */
        if (!isAsciiEncodable(altx)) {
            throw new IOException("ALTX 只能包含 US-ASCII 字符: " + altx);
        }
        byte alcd = (byte) ((set ? 0x80 : 0x00) | category);
        Secs2 body = Secs2.getBuilder().list(
                Secs2.getBuilder().binary(alcd),
                Secs2.getBuilder().uint4(alid),
                Secs2.getBuilder().ascii(altx));

        try {
            this.ackSender.get().execute(() -> {
                try {
                    comm.send(5, 1, true, body);
                    this.alarmsSent.incrementAndGet();
                    if (set) {
                        this.activeAlarms.put(alid,
                                new HsmsSessionRuntime.AlarmState(alid, altx, category, System.currentTimeMillis()));
                    } else {
                        this.activeAlarms.remove(alid);
                    }
                    this.notice.accept(NoticeEvent.ALARM,
                            "{\"alid\":" + alid + ",\"set\":" + set
                                    + ",\"category\":" + category
                                    + ",\"text\":\"" + altx.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    this.errorSink.accept(new IllegalStateException("等待 S5F2 被中断: ALID " + alid));
                } catch (Exception e) {
                    this.errorSink.accept(e);
                    Log.error("会话 " + this.sessionId + " 等待 S5F2 失败: ALID " + alid, e);
                }
            });
        } catch (RejectedExecutionException e) {
            this.alarmDropped.incrementAndGet();
            throw new IOException("S5F1 ACK 队列已满: ALID " + alid, e);
        }
    }

    /** SECS-II 的 A 是 US-ASCII：非 ASCII 字符编码会失败。 */
    private static boolean isAsciiEncodable(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }
}
