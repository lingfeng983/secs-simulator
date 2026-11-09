import { describe, expect, it } from 'vitest';
import { previewWindow } from './previewWindow';

describe('previewWindow', () => {
  it('以 uptime 为右端点、windowMs 为宽度', () => {
    expect(previewWindow(300_000, 120_000)).toEqual({ from: 180_000, to: 300_000 });
  });

  it('uptime 比窗口短时左端点夹到 0', () => {
    expect(previewWindow(60_000, 120_000)).toEqual({ from: 0, to: 60_000 });
  });

  it('老后端没有 uptime 时给一个合法的非零窗口', () => {
    expect(previewWindow(undefined, 120_000)).toEqual({ from: 0, to: 1 });
  });

  it('负数窗口按 0 处理，不会算出负起点', () => {
    expect(previewWindow(5_000, -1_000)).toEqual({ from: 5_000, to: 5_000 });
  });
});
