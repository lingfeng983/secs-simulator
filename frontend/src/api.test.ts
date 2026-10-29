import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, request } from './api';

/**
 * 请求封装的单测。
 *
 * <p>只测最容易回归的契约：超时会被转成可读的 ApiError；
 * 内部工具直连访问，请求不再携带任何凭据头。</p>
 */
describe('request', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('超时抛 ApiError(timeout)，且不会自己弹提示', async () => {
    /* 模拟浏览器：fetch 在 signal abort 时以 reason 拒绝 */
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: string, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
        const signal = init?.signal;
        if (signal === undefined || signal === null) {
          return;
        }
        signal.addEventListener('abort', () => reject(signal.reason));
      })),
    );

    await expect(request('http://example.test/slow', {}, 20)).rejects.toMatchObject({
      name: 'ApiError',
      status: 408,
      code: 'timeout',
    });
  });

  it('直连访问：请求不带 Authorization', async () => {
    const fakeFetch = vi.fn(async (_url: string, _init?: RequestInit) => new Response('{}', { status: 200 }));
    vi.stubGlobal('fetch', fakeFetch);

    await request('http://example.test/health', { signal: new AbortController().signal });

    const init = fakeFetch.mock.calls[0]?.[1] as RequestInit;
    expect(new Headers(init.headers).has('Authorization')).toBe(false);
  });

  it('调用方自带的 signal 优先，不被默认超时覆盖', async () => {
    const controller = new AbortController();
    const fakeFetch = vi.fn(async (_url: string, _init?: RequestInit) => new Response('{}', { status: 200 }));
    vi.stubGlobal('fetch', fakeFetch);

    await request('http://example.test/health', { signal: controller.signal });

    const init = fakeFetch.mock.calls[0]?.[1] as RequestInit;
    expect(init.signal).toBe(controller.signal);
  });

  it('ApiError 保留后端错误码', () => {
    const error = new ApiError(403, 'forbidden', '403 forbidden');
    expect(error.status).toBe(403);
    expect(error.code).toBe('forbidden');
    expect(error.message).toContain('forbidden');
  });
});
