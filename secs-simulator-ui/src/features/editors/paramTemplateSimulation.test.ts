import { describe, expect, it } from 'vitest';
import {
  SIM_DURATION_CHOICES,
  compileExpression,
  formatSimTime,
  formatSimValue,
  simulateSource,
  suggestDurationMs,
} from './paramTemplateSimulation';

const SINE = { type: 'sine', min: 100, max: 300, periodMs: 60_000 };

describe('simulateSource · const', () => {
  it('常量是一条水平线', () => {
    const sim = simulateSource({ mode: 'const', value: 42 }, { durationMs: 60_000 });
    expect(sim.min).toBe(42);
    expect(sim.max).toBe(42);
    expect(sim.avg).toBeCloseTo(42, 6);
    expect(sim.warning).toBe('');
  });

  it('文本常量没有曲线，样本为空并给提示', () => {
    const sim = simulateSource({ mode: 'const', value: 'LOT01' }, { durationMs: 60_000 });
    expect(sim.samples).toEqual([]);
    expect(sim.warning).toContain('画不出曲线');
  });
});

describe('simulateSource · curve', () => {
  it('正弦落在 [min,max] 内，且中点接近均值', () => {
    const sim = simulateSource({ mode: 'curve', clock: 'phase', curve: SINE }, { durationMs: 60_000 });
    expect(sim.min).toBeGreaterThanOrEqual(99.9);
    expect(sim.max).toBeLessThanOrEqual(300.1);
    expect(sim.avg).toBeCloseTo(200, 0);
    expect(sim.shape).toContain('sine');
  });

  it('transform 对曲线输出再算一次（roundTo 到 0.5）', () => {
    const sim = simulateSource(
      { mode: 'curve', curve: { type: 'constant', value: 12.345 }, transform: 'roundTo(V, 0.5)' },
      { durationMs: 10_000 },
    );
    expect(sim.min).toBeCloseTo(12.5, 6);
  });

  it('transform 写错时给提示而不是抛错', () => {
    const sim = simulateSource(
      { mode: 'curve', curve: { type: 'constant', value: 1 }, transform: 'roundTo(' },
      { durationMs: 10_000 },
    );
    expect(sim.warning).toContain('变换表达式');
  });
});

describe('simulateSource · expr', () => {
  it('私有曲线参与表达式（a * 2）', () => {
    const sim = simulateSource(
      { mode: 'expr', clock: 'phase', vars: { a: { type: 'constant', value: 10 } }, expr: 'a * 2' },
      { durationMs: 10_000 },
    );
    expect(sim.min).toBeCloseTo(20, 6);
    expect(sim.max).toBeCloseTo(20, 6);
    expect(sim.shape).toContain('表达式');
  });

  it('未知变量按 0 处理，不会中断预览', () => {
    const sim = simulateSource({ mode: 'expr', expr: 'nope + 5' }, { durationMs: 10_000 });
    expect(sim.min).toBeCloseTo(5, 6);
  });
});

describe('simulateSource · cycle', () => {
  it('按片阶跃：每片一档，片长取模拟时长', () => {
    const sim = simulateSource(
      { mode: 'cycle', start: 1, step: 1, every: 1 },
      { durationMs: 10_000, samples: 32, cycleMs: 20_000 },
    );
    /* 片长 20s > 窗口 10s → 窗口内都是第 0 片 */
    expect(sim.min).toBe(1);
    expect(sim.max).toBe(1);
  });

  it('片边界落在窗口内时逐片进一档', () => {
    const sim = simulateSource(
      { mode: 'cycle', start: 1, step: 1, every: 1 },
      { durationMs: 10_000, samples: 32, cycleMs: 5_000 },
    );
    /* 窗口 0..10s、片长 5s：第 0/1/2 片的起点都在窗口内（含右端点） */
    expect(sim.min).toBe(1);
    expect(sim.max).toBe(3);
  });

  it('文本按片（pattern）没有曲线', () => {
    const sim = simulateSource({ mode: 'cycle', pattern: 'W{03}', start: 1 }, { durationMs: 10_000 });
    expect(sim.samples).toEqual([]);
  });
});

describe('suggestDurationMs', () => {
  it('按曲线周期翻倍，落在 [30s, 2h]', () => {
    expect(suggestDurationMs({ mode: 'curve', curve: SINE })).toBe(120_000);
    expect(suggestDurationMs({ mode: 'curve', curve: { type: 'constant', value: 1 } })).toBe(60_000);
    expect(suggestDurationMs({ mode: 'expr', vars: { a: SINE }, expr: 'a' })).toBe(120_000);
  });
});

describe('compileExpression', () => {
  it('支持算术、比较与 12 个纯函数', () => {
    expect(compileExpression('1 + 2 * 3')(() => 0)).toBe(7);
    expect(compileExpression('select(a > 1, 5, 6)')((name) => (name === 'a' ? 2 : 0))).toBe(5);
    expect(compileExpression('clamp(5, 0, 3)')(() => 0)).toBe(3);
  });

  it('非法表达式抛错（界面据此给提示）', () => {
    expect(() => compileExpression('1 +')).toThrow();
    expect(() => compileExpression('unknownFn(1)')).toThrow();
  });
});

describe('格式化', () => {
  it('时间与数值都有可读输出', () => {
    expect(formatSimTime(0)).toBe('00:00');
    expect(formatSimTime(90_000)).toBe('01:30');
    expect(formatSimTime(3_600_000)).toBe('1:00:00');
    expect(formatSimValue(Number.NaN)).toBe('—');
    expect(formatSimValue(0)).toBe('0');
    expect(formatSimValue(123.456)).toBe('123.5');
  });

  it('时长候选含"自动"', () => {
    expect(SIM_DURATION_CHOICES[0].value).toBe(0);
  });
});
