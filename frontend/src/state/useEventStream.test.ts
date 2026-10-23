import { describe, expect, it } from 'vitest';
import { streamStateLabel } from './useEventStream';

describe('streamStateLabel', () => {
  it('combines the connected state with the heartbeat detail', () => {
    expect(streamStateLabel('connected', '心跳 12:00:01')).toBe('已连接 · 心跳 12:00:01');
  });

  it('falls back to a plain label when there is no detail', () => {
    expect(streamStateLabel('connected', '')).toBe('已连接');
    expect(streamStateLabel('connecting', '')).toBe('连接中');
    expect(streamStateLabel('idle', '')).toBe('未连接');
  });

  it('keeps the reconnect reason visible', () => {
    expect(streamStateLabel('reconnecting', '断开（连接中断）')).toBe('重连中 · 断开（连接中断）');
  });

  it('reports a closed stream', () => {
    expect(streamStateLabel('closed', '')).toBe('已关闭');
  });
});
