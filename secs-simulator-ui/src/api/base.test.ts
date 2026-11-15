import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, request, readJson, setRuntimeApiFailure } from './base';

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

/**
 * 桌面壳里后端没起来时的两种失败形态。
 *
 * <p>壳内页面的"同源"是壳自带的静态资源：本机后端没起时 /api/* 会落到 index.html，
 * 于是以前只能看到"200 返回的不是 JSON"。这里锁住修好之后的行为：
 * 请求就地带着真正原因失败，且 HTML 兜底页要翻译成人话。</p>
 */
describe('后端不可用', () => {
  afterEach(() => {
    setRuntimeApiFailure(null);
    vi.unstubAllGlobals();
  });

  it('壳内后端未就绪时请求直接失败，不打同源静态页', async () => {
    const fakeFetch = vi.fn(async () => new Response('{}', { status: 200 }));
    vi.stubGlobal('fetch', fakeFetch);
    setRuntimeApiFailure('本机后端没有启动');

    await expect(request('http://example.test/api/v1/health')).rejects.toMatchObject({
      name: 'ApiError',
      status: 0,
      code: 'backend-unavailable',
      message: '本机后端没有启动',
    });
    expect(fakeFetch).not.toHaveBeenCalled();
  });

  it('200 但回的是网页时，提示说清"拿到的是网页"', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('<!DOCTYPE html><html><head></head></html>', { status: 200 })),
    );

    await expect(readJson(await request('http://example.test/api/v1/health'))).rejects.toMatchObject({
      code: 'bad-json',
      message: expect.stringContaining('网页'),
    });
  });
});
