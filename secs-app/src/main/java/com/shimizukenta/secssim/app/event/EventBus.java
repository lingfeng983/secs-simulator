package com.shimizukenta.secssim.app.event;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 极简事件总线。
 *
 * <p>订阅者抛异常不能影响发布者——一个卡住的 SSE 客户端不该让设备停止上报。
 * 因此这里捕获并记录，而不是向上抛。
 * </p>
 */
public final class EventBus {

    private final CopyOnWriteArrayList<Consumer<MessageEvent>> listeners = new CopyOnWriteArrayList<>();

    /**
     * 订阅消息事件。
     *
     * @param listener 订阅者
     * @return 取消订阅的句柄
     */
    public AutoCloseable subscribe(Consumer<MessageEvent> listener) {
        this.listeners.add(listener);
        return () -> this.listeners.remove(listener);
    }

    /**
     * 发布一条消息事件。
     *
     * @param event 事件
     */
    public void publish(MessageEvent event) {
        for (Consumer<MessageEvent> listener : this.listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                System.err.println("事件订阅者失败（已忽略）: " + e);
            }
        }
    }

    /**
     * 当前订阅者数量（诊断用）。
     *
     * @return 数量
     */
    public int subscriberCount() {
        return this.listeners.size();
    }
}
