import { describe, expect, it } from 'vitest';
import { createRequestSeq, isFreshResponse } from './requestSeq';

describe('requestSeq', () => {
  it('后取的号会让更早的号过期', () => {
    const seq = createRequestSeq();
    const first = seq.begin();
    const second = seq.begin();

    expect(seq.isCurrent(first)).toBe(false);
    expect(seq.isCurrent(second)).toBe(true);
  });

  it('模拟 load 竞态：先发的请求后回，不能覆盖后发的结果', async () => {
    const seq = createRequestSeq();
    const applied: string[] = [];
    const guard = (token: number, name: string, activeName: string, text: string) => {
      if (isFreshResponse(token, seq, name, activeName)) {
        applied.push(text);
      }
    };

    /* 请求 A 先发、B 后发；用可控 Promise 让 B 先落地、A 后落地 */
    let releaseA: (text: string) => void = () => undefined;
    const slowA = new Promise<string>((resolve) => {
      releaseA = resolve;
    });
    const tokenA = seq.begin();
    const tokenB = seq.begin();

    guard(tokenB, 'line1.json', 'line1.json', 'B 的内容');
    releaseA('A 的旧内容');
    guard(tokenA, 'line1.json', 'line1.json', await slowA);

    expect(applied).toEqual(['B 的内容']);
  });

  it('序号最新但配置名已切换时也丢弃', () => {
    const seq = createRequestSeq();
    const token = seq.begin();

    expect(isFreshResponse(token, seq, 'line1.json', 'line2.json')).toBe(false);
    expect(isFreshResponse(token, seq, 'line2.json', 'line2.json')).toBe(true);
  });
});
