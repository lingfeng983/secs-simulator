package com.shimizukenta.secssim.app.event;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.shimizukenta.secssim.app.log.Log;

/**
 * 状态通知总线：与 {@link EventBus}（报文）并行的第二条 SSE 数据源。
 *
 * <p>不合并进 EventBus 的原因：报文事件的订阅者（报文日志/历史补拉）
 * 只认 {@link MessageEvent}，塞进来的通知要么被迫多态判断、要么丢类型。
 * 两条总线在 HTTP 层汇进同一条 SSE 流，订阅者按 {@code event:} 类型分发。</p>
 */
public final class NoticeBus {

    private final CopyOnWriteArrayList<Consumer<NoticeEvent>> listeners = new CopyOnWriteArrayList<>();

    /**
     * 订阅通知。
     *
     * @param listener 订阅者
     * @return 取消订阅句柄
     */
    public AutoCloseable subscribe(Consumer<NoticeEvent> listener) {
        this.listeners.add(listener);
        return () -> this.listeners.remove(listener);
    }

    /**
     * 发布一条通知。
     *
     * @param event 通知
     */
    public void publish(NoticeEvent event) {
        for (Consumer<NoticeEvent> listener : this.listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                Log.error("通知订阅者失败（已忽略）", e);
            }
        }
    }
}
