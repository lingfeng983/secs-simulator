import { useEffect, useRef } from 'react';
import type { ApiClient, WireMessage } from '../api';

/** 事件流状态。 */
export type StreamState = 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'closed';

/** 事件流回调。 */
export interface EventStreamHandlers {
  /** 新到的报文（暂停时会被暂存，恢复时一次性吐出）。 */
  onFlush: (messages: WireMessage[]) => void;
  /** 状态变化。 */
  onState: (state: StreamState, detail: string) => void;
  /** 需要写进事件流日志的提示（重连、票据失败等）。 */
  onNotice: (text: string) => void;
}

/** 重连间隔（毫秒）。 */
const RECONNECT_MS = 2000;

/**
 * 事件流（SSE）连接。
 *
 * <p>单独抽出来是因为这段逻辑有两个坑：
 * 一是<b>票据只能用一次</b>，自动重连必须换一张新票，
 * 带着旧票重连会被 401 打回；二是<b>断线期间的报文后端不补发</b>，
 * 界面必须明说，否则用户会以为"中间本来就没有报文"。</p>
 *
 * <p>回调通过 ref 传递：面板每次渲染都会造新函数，
 * 如果直接进依赖数组，连接会被反复拆建。</p>
 *
 * @param client 后端客户端；为 null 表示还没连上后端
 * @param paused 暂停接收：报文照收，但先攒着，恢复时一次性补上
 */
export function useEventStream(
  client: ApiClient | null,
  paused: boolean,
  handlers: EventStreamHandlers,
): void {
  const handlersRef = useRef(handlers);
  handlersRef.current = handlers;

  const pausedRef = useRef(paused);
  const bufferRef = useRef<WireMessage[]>([]);

  /* 暂停 → 恢复：把暂存的报文一次性交给上层 */
  useEffect(() => {
    const wasPaused = pausedRef.current;
    pausedRef.current = paused;
    if (wasPaused && !paused) {
      const pending = bufferRef.current;
      bufferRef.current = [];
      if (pending.length > 0) {
        handlersRef.current.onFlush(pending);
      }
    }
  }, [paused]);

  useEffect(() => {
    if (client === null) {
      handlersRef.current.onState('idle', '未连接');
      return;
    }

    let stream: EventSource | null = null;
    let retryTimer = 0;
    let disposed = false;
    let openedOnce = false;
    let lastErrorAt = 0;
    let lastTicketErrorAt = 0;

    const scheduleReconnect = () => {
      if (disposed || retryTimer !== 0) {
        return;
      }
      retryTimer = window.setTimeout(() => {
        retryTimer = 0;
        void connect();
      }, RECONNECT_MS);
    };

    const connect = async () => {
      if (disposed) {
        return;
      }

      try {
        /* 票据是一次性的：每次（重）连都要重新换一张，不能复用 URL */
        const ticket = await client.ticket();
        if (disposed) {
          return;
        }

        const source = new EventSource(client.eventsUrl(ticket));
        stream = source;
        handlersRef.current.onState(
          openedOnce ? 'reconnecting' : 'connecting',
          openedOnce ? '重连中' : '连接中',
        );

        source.addEventListener('open', () => {
          handlersRef.current.onState('connected', '已连接');
          if (openedOnce) {
            /* 断线期间的报文后端不补发，界面必须明说 */
            handlersRef.current.onNotice('事件流已重连：断线期间的报文不会补发');
          }
          openedOnce = true;
        });

        source.addEventListener('heartbeat', (event) => {
          const at = Number(event.data);
          handlersRef.current.onState(
            'connected',
            Number.isFinite(at) ? `心跳 ${new Date(at).toLocaleTimeString()}` : '心跳',
          );
        });

        source.addEventListener('message', (event) => {
          let parsed: WireMessage;
          try {
            parsed = JSON.parse(event.data) as WireMessage;
          } catch {
            handlersRef.current.onNotice('收到无法解析的报文事件');
            return;
          }
          if (pausedRef.current) {
            bufferRef.current.push(parsed);
            return;
          }
          handlersRef.current.onFlush([parsed]);
        });

        source.addEventListener('error', () => {
          const state = source.readyState === EventSource.CLOSED ? '已关闭' : '连接中断';
          handlersRef.current.onState('reconnecting', `断开（${state}）`);

          const now = Date.now();
          if (now - lastErrorAt > 5000) {
            lastErrorAt = now;
            handlersRef.current.onNotice(`事件流断开（${state}），2 秒后用新票据重连`);
          }

          /* 票据只能用一次，自动重连会带着旧票据被 401，必须自己换票重连 */
          source.close();
          if (stream === source) {
            stream = null;
          }
          scheduleReconnect();
        });
      } catch (error) {
        const now = Date.now();
        if (now - lastTicketErrorAt > 10_000) {
          lastTicketErrorAt = now;
          handlersRef.current.onNotice(`获取事件流票据失败: ${(error as Error).message}`);
        }
        handlersRef.current.onState('reconnecting', '等待重连');
        scheduleReconnect();
      }
    };

    void connect();

    return () => {
      disposed = true;
      if (retryTimer !== 0) {
        window.clearTimeout(retryTimer);
      }
      stream?.close();
    };
  }, [client]);
}

/**
 * 事件流的可见状态文案。
 *
 * <p>状态和心跳时间要能同时显示：心跳时间一直在变，
 * 塞进同一个字段会互相覆盖。</p>
 */
export function streamStateLabel(state: StreamState, detail: string): string {
  switch (state) {
    case 'connected':
      return detail === '' ? '已连接' : `已连接 · ${detail}`;
    case 'connecting':
      return '连接中';
    case 'reconnecting':
      return detail === '' ? '重连中' : `重连中 · ${detail}`;
    case 'closed':
      return '已关闭';
    default:
      return detail === '' ? '未连接' : detail;
  }
}
