import { describe, expect, it } from 'vitest';
import { collectorEngaged, traceStarted, type ArmedCollectorState } from './hostRuntimeStatus';
import type { TraceFrame } from '../../../shared/lib/smlValues';

/**
 * 主机运行区「启动 / 停止」按钮的状态推断。
 *
 * <p>用户的硬要求：按钮只有"启动 ↔ 停止"两态，点完立刻切过去，
 * **已启动的不能再启动**。这两个纯函数就是这个约定的唯一实现点，
 * 所以逐条钉住——尤其是"点启动后、首帧还没到"和"点停止后、窗口还没过期"这两段。</p>
 */

const NOW = 10_000_000;

function frame(trid: number, smpln: number, at: number): TraceFrame {
  return { trid, smpln, values: [1], at };
}

describe('traceStarted', () => {
  it('没点过也没收到帧：未启动', () => {
    expect(traceStarted(7, 5, [], undefined, NOW)).toBe(false);
  });

  it('收到本 TRID 的帧：采集中（其他 TRID 的帧不算）', () => {
    const frames = [frame(7, 1, NOW - 500), frame(8, 1, NOW - 500)];
    expect(traceStarted(7, 5, frames, undefined, NOW)).toBe(true);
    expect(traceStarted(9, 5, frames, undefined, NOW)).toBe(false);
  });

  it('刚点启动、首帧还没到：已经是「停止」（不能给重复启动的机会）', () => {
    expect(traceStarted(7, 5, [], { started: NOW - 120, stopped: 0 }, NOW)).toBe(true);
  });

  it('刚点停止、设备还没停完：立刻回「启动」，不用等 60 秒窗口过期', () => {
    const frames = [frame(7, 3, NOW - 300)];
    expect(traceStarted(7, 5, frames, { started: NOW - 3_000, stopped: NOW - 100 }, NOW)).toBe(false);
  });

  it('停止后设备还在推帧：仍算采集中（以报文为准）', () => {
    const frames = [frame(7, 4, NOW - 50)];
    expect(traceStarted(7, 5, frames, { started: NOW - 3_000, stopped: NOW - 100 }, NOW)).toBe(true);
  });

  it('采满 TOTSMP：采样自然结束，按钮回「启动」', () => {
    const frames = [frame(7, 5, NOW - 200)];
    expect(traceStarted(7, 5, frames, { started: NOW - 5_000, stopped: 0 }, NOW)).toBe(false);
  });

  it('再次启动后，旧帧不算数（按本次启动时刻切开）', () => {
    const frames = [frame(7, 5, NOW - 60_000), frame(7, 1, NOW - 100)];
    expect(traceStarted(7, 5, frames, { started: NOW - 200, stopped: NOW - 30_000 }, NOW)).toBe(true);
  });

  it('窗口过期且没有手点记录：回落到未启动', () => {
    const frames = [frame(7, 1, NOW - 120_000)];
    expect(traceStarted(7, 0, frames, undefined, NOW)).toBe(false);
  });
});

describe('collectorEngaged', () => {
  const base = { configured: true, running: false, samples: 0, decodeErrors: 0, svids: [], staleSvids: [], series: [] };

  it('未配置 / 无状态：按钮给「启动」', () => {
    expect(collectorEngaged(null)).toBe(false);
    expect(collectorEngaged({ ...base, configured: false })).toBe(false);
  });

  it('采集中：给「停止」', () => {
    expect(collectorEngaged({ ...base, running: true })).toBe(true);
  });

  it('待命（等下一条启动 CEID）也算已启动：给「停止」，不给重复启动', () => {
    const standby: ArmedCollectorState = { ...base, armed: true };
    expect(collectorEngaged(standby)).toBe(true);
  });

  it('配置已下发但没启动过：给「启动」', () => {
    expect(collectorEngaged({ ...base })).toBe(false);
  });
});
