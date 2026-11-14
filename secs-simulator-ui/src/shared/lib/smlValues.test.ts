import { describe, expect, it } from 'vitest';
import { ceidFromS6f11, parseSmlBody, traceFrameFromS6f1 } from './smlValues';

/**
 * 轻量 SML 解析：界面靠它从实时报文里取 CEID / S6F1 采样值。
 * 解析失败必须返回 null（不能抛），否则一条异常报文会把监控面板打白。
 */
describe('parseSmlBody', () => {
  it('parses the renderer output into a node tree', () => {
    const root = parseSmlBody('<L[3] <U4 1> <A "TEXT"> <L[2] <F4 1.5> <F4 2>>>');
    expect(root).not.toBeNull();
    expect(root?.type).toBe('L[3]');
    expect(root?.children).toHaveLength(3);
    expect(root?.children[2].children).toHaveLength(2);
  });

  it('strips a leading SxFy header and trailing dot', () => {
    const root = parseSmlBody('S6F11 W\n<L[3] <U4 1> <U4 2001> <L[0]>>.');
    expect(root?.children).toHaveLength(3);
  });

  it('returns null instead of throwing on truncated input', () => {
    expect(parseSmlBody('<L[2] <U4 1>')).toBeNull();
  });
});

describe('ceidFromS6f11', () => {
  it('reads the standard L[3]{DATAID, CEID, REPORTS} layout', () => {
    expect(ceidFromS6f11('<L[3] <U4 1000> <U4 2001> <L[0]>>')).toBe(2001);
  });

  it('falls back to the first item for the simplified L[2]{CEID, REPORTS}', () => {
    expect(ceidFromS6f11('<L[2] <U4 2001> <L[0]>>')).toBe(2001);
  });
});

describe('traceFrameFromS6f1', () => {
  it('flattens TRID / SMPLN / STIME / values', () => {
    const frame = traceFrameFromS6f1(
      '<L[4] <U4 1> <U4 3> <A "120000"> <L[3] <F4 23.8> <F4 24.1> <F4 23.9>>>',
      1_700_000_000_000,
    );
    expect(frame).toEqual({
      trid: 1,
      smpln: 3,
      values: [23.8, 24.1, 23.9],
      at: 1_700_000_000_000,
    });
  });

  it('returns null when the body carries no values', () => {
    expect(traceFrameFromS6f1('<L[4] <U4 1> <U4 0> <A "000000"> <L[0]>>', 1)).toBeNull();
  });
});
