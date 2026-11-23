// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useEventStream, type EventStreamHandlers, type NoticeWire } from './useEventStream';
import { actSync, flush, renderHook } from '../test/renderHook';
import type { ApiClient, WireMessage } from '../api';

/**
 * `useEventStream` 的行为测试。
 *
 * <p>这段逻辑有两个不能错的点：<b>票据只能用一次</b>（重连必须换新票，
 * 带旧票会被 401 打回），以及<b>暂停期间的报文要留在缓冲里</b>（有上限，
 * 恢复时一次性吐出）。两者都只在"事件真的来了"之后才看得出来，所以这里
 * 用一个假的 EventSource 把 SSE 帧直接喂进去。</p>
 */

type Listener = (event: { data?: string; type?: string }) => void;

class FakeEventSource {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSED = 2;

  static instances: FakeEventSource[] = [];

  readonly url: string;
  readyState = FakeEventSource.CONNECTING;
  private listeners = new Map<string, Listener[]>();

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: Listener): void {
    const list = this.listeners.get(type) ?? [];
    list.push(listener);
    this.listeners.set(type, list);
  }

  close(): void {
    this.readyState = FakeEventSource.CLOSED;
  }

  /** 测试侧喂一帧。 */
  emit(type: string, data?: string): void {
    for (const listener of this.listeners.get(type) ?? []) {
      listener({ data, type });
    }
  }
}

function fakeClient(tickets: string[]): { client: ApiClient; ticket: ReturnType<typeof vi.fn> } {
  let index = 0;
  const ticket = vi.fn(async () => {
    const next = tickets[Math.min(index, tickets.length - 1)];
    index += 1;
    return next;
  });
  const client = {
    ticket,
    eventsUrl: (value: string) => `http://test/events?ticket=${value}`,
  } as unknown as ApiClient;
  return { client, ticket };
}

function makeHandlers(): EventStreamHandlers & {
  flushed: WireMessage[][];
  notices: NoticeWire[];
  texts: string[];
  states: string[];
  reconnected: number;
} {
  const flushed: WireMessage[][] = [];
  const notices: NoticeWire[] = [];
  const texts: string[] = [];
  const states: string[] = [];
  const counters = { reconnected: 0 };
  return {
    flushed,
    notices,
    texts,
    states,
    get reconnected() {
      return counters.reconnected;
    },
    onFlush: (messages) => flushed.push(messages),
    onNotice: (text) => texts.push(text),
    onState: (state) => states.push(state),
    onNoticeEvent: (notice) => notices.push(notice),
    onReconnected: () => {
      counters.reconnected += 1;
    },
  } as EventStreamHandlers & {
    flushed: WireMessage[][];
    notices: NoticeWire[];
    texts: string[];
    states: string[];
    reconnected: number;
  };
}

beforeEach(() => {
  vi.useFakeTimers();
  FakeEventSource.instances = [];
  vi.stubGlobal('EventSource', FakeEventSource);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('useEventStream · 票据与重连', () => {
  it('连上就建一条 SSE，url 带的是刚取到的票', async () => {
    const { client, ticket } = fakeClient(['t1']);
    const handlers = makeHandlers();
    const hook = renderHook(() => useEventStream(client, false, handlers));
    await flush();

    expect(ticket).toHaveBeenCalledTimes(1);
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(FakeEventSource.instances[0].url).toContain('ticket=t1');
    hook.unmount();
  });

  it('断开重连要换新票：旧票只能用一次', async () => {
    const { client, ticket } = fakeClient(['t1', 't2']);
    const handlers = makeHandlers();
    const hook = renderHook(() => useEventStream(client, false, handlers));
    await flush();

    FakeEventSource.instances[0].emit('error');
    /* 断线先报一次状态，再排一次 2 秒后的重连 */
    expect(handlers.states).toContain('reconnecting');
    expect(ticket).toHaveBeenCalledTimes(1);

    actSync(() => vi.advanceTimersByTime(2000));
    await flush();

    expect(ticket).toHaveBeenCalledTimes(2);
    expect(FakeEventSource.instances).toHaveLength(2);
    expect(FakeEventSource.instances[1].url).toContain('ticket=t2');
    /* 旧的流必须真的关掉，否则两条通道同时往界面上灌报文 */
    expect(FakeEventSource.instances[0].readyState).toBe(FakeEventSource.CLOSED);
    hook.unmount();
  });

  it('首次 open 不触发补拉，重连后的 open 才补', async () => {
    const { client } = fakeClient(['t1', 't2']);
    const handlers = makeHandlers();
    const hook = renderHook(() => useEventStream(client, false, handlers));
    await flush();

    FakeEventSource.instances[0].emit('open');
    expect(handlers.reconnected).toBe(0);

    FakeEventSource.instances[0].emit('error');
    actSync(() => vi.advanceTimersByTime(2000));
    await flush();

    FakeEventSource.instances[1].emit('open');
    expect(handlers.reconnected).toBe(1);
    expect(handlers.texts.some((text) => text.includes('补拉'))).toBe(true);
    hook.unmount();
  });

  it('取票失败不炸：只报一次提示并继续等重连', async () => {
    const client = {
      ticket: vi.fn(async () => {
        throw new Error('后端不可达');
      }),
      eventsUrl: () => 'http://test/events',
    } as unknown as ApiClient;
    const handlers = makeHandlers();
    const hook = renderHook(() => useEventStream(client, false, handlers));
    await flush();

    expect(FakeEventSource.instances).toHaveLength(0);
    expect(handlers.texts.some((text) => text.includes('票据失败'))).toBe(true);
    expect(handlers.states).toContain('reconnecting');
    hook.unmount();
  });
});

describe('useEventStream · 报文与暂停', () => {
  it('报文逐条交给 onFlush', async () => {
    const { client } = fakeClient(['t1']);
    const handlers = makeHandlers();
    const hook = renderHook(() => useEventStream(client, false, handlers));
    await flush();

    FakeEventSource.instances[0].emit('message', JSON.stringify({ id: 'm1', stream: 6 }));
    expect(handlers.flushed).toHaveLength(1);
    expect(handlers.flushed[0][0]).toMatchObject({ id: 'm1' });
    hook.unmount();
  });

  it('解析不了的报文只提示，不打断连接', async () => {
    const { client } = fakeClient(['t1']);
    const handlers = makeHandlers();
    const hook = renderHook(() => useEventStream(client, false, handlers));
    await flush();

    FakeEventSource.instances[0].emit('message', '{ 不是 JSON');
    expect(handlers.flushed).toHaveLength(0);
    expect(handlers.texts.some((text) => text.includes('无法解析'))).toBe(true);
    hook.unmount();
  });

  it('暂停期间攒着，恢复时一次吐出', async () => {
    const { client } = fakeClient(['t1']);
    const handlers = makeHandlers();
    let paused = true;
    const hook = renderHook(() => useEventStream(client, paused, handlers));
    await flush();

    FakeEventSource.instances[0].emit('message', JSON.stringify({ id: 'a' }));
    FakeEventSource.instances[0].emit('message', JSON.stringify({ id: 'b' }));
    expect(handlers.flushed).toHaveLength(0);

    paused = false;
    actSync(() => hook.rerender());

    expect(handlers.flushed).toHaveLength(1);
    expect(handlers.flushed[0].map((message) => (message as unknown as { id: string }).id)).toEqual(['a', 'b']);
    hook.unmount();
  });

  it('暂停太久有上限：只留最近 5000 条，丢最旧的', async () => {
    const { client } = fakeClient(['t1']);
    const handlers = makeHandlers();
    let paused = true;
    const hook = renderHook(() => useEventStream(client, paused, handlers));
    await flush();

    for (let index = 0; index < 6000; index += 1) {
      FakeEventSource.instances[0].emit('message', JSON.stringify({ seq: index }));
    }

    paused = false;
    actSync(() => hook.rerender());

    expect(handlers.flushed).toHaveLength(1);
    const batch = handlers.flushed[0] as unknown as { seq: number }[];
    expect(batch).toHaveLength(5000);
    expect(batch[0].seq).toBe(1000);
    expect(batch[batch.length - 1].seq).toBe(5999);
    hook.unmount();
  });
});

describe('useEventStream · 类型化通知', () => {
  it('会话域事件带出顶层 sessionId 与时间戳', async () => {
    const { client } = fakeClient(['t1']);
    const handlers = makeHandlers();
    const hook = renderHook(() => useEventStream(client, false, handlers));
    await flush();

    FakeEventSource.instances[0].emit(
      's6f11',
      JSON.stringify({ sessionId: 'S1', at: 1700000000000, ceid: 2001 }),
    );

    expect(handlers.notices).toHaveLength(1);
    expect(handlers.notices[0]).toMatchObject({
      type: 's6f11',
      sessionId: 'S1',
      at: 1700000000000,
      data: { ceid: 2001 },
    });
    hook.unmount();
  });

  it('主机/配置类事件没有顶层 sessionId：规整成空串，身份从 data 里读', async () => {
    const { client } = fakeClient(['t1']);
    const handlers = makeHandlers();
    const hook = renderHook(() => useEventStream(client, false, handlers));
    await flush();

    FakeEventSource.instances[0].emit(
      'config-changed',
      JSON.stringify({ name: 'line1.json', action: 'save-section' }),
    );

    expect(handlers.notices[0].type).toBe('config-changed');
    expect(handlers.notices[0].sessionId).toBe('');
    expect(handlers.notices[0].data).toMatchObject({ name: 'line1.json', action: 'save-section' });
    hook.unmount();
  });

  it('七类通知都注册了监听：漏一个等于后端白发', async () => {
    const { client } = fakeClient(['t1']);
    const handlers = makeHandlers();
    const hook = renderHook(() => useEventStream(client, false, handlers));
    await flush();

    for (const type of ['s6f11', 'alarm', 'session', 'plan-fired', 'device-link', 'host-link', 'config-changed']) {
      FakeEventSource.instances[0].emit(type, JSON.stringify({ at: 1 }));
    }

    expect(handlers.notices.map((notice) => notice.type)).toEqual([
      's6f11', 'alarm', 'session', 'plan-fired', 'device-link', 'host-link', 'config-changed',
    ]);
    hook.unmount();
  });
});

describe('useEventStream · 没有客户端', () => {
  it('client 为 null 时不上连接，状态是 idle', async () => {
    const handlers = makeHandlers();
    const hook = renderHook(() => useEventStream(null, false, handlers));
    await flush();

    expect(FakeEventSource.instances).toHaveLength(0);
    expect(handlers.states).toEqual(['idle']);
    hook.unmount();
  });
});
