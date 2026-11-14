import { describe, expect, it } from 'vitest';
import type { WireMessage } from '../../api';
import {
  categorizeMessage,
  mergeMessages,
  messageCounts,
  messageKey,
} from './messageFilter';

function message(partial: Partial<WireMessage>): WireMessage {
  return {
    sessionId: 'line1',
    direction: 'in',
    stream: 1,
    function: 1,
    wbit: true,
    body: '',
    at: 1,
    ...partial,
  };
}

describe('messageFilter', () => {
  it('按 SxFy 把报文归类', () => {
    expect(categorizeMessage(1, 1)).toBe('probe');
    expect(categorizeMessage(1, 2)).toBe('probe');
    expect(categorizeMessage(1, 3)).toBe('collection');
    expect(categorizeMessage(1, 4)).toBe('collection');
    expect(categorizeMessage(6, 1)).toBe('trace');
    expect(categorizeMessage(6, 2)).toBe('trace');
    expect(categorizeMessage(6, 11)).toBe('event');
    expect(categorizeMessage(6, 12)).toBe('event');
    expect(categorizeMessage(1, 13)).toBe('control');
    expect(categorizeMessage(1, 18)).toBe('control');
    expect(categorizeMessage(5, 1)).toBe('alarm');
    expect(categorizeMessage(10, 3)).toBe('terminal');
    expect(categorizeMessage(2, 41)).toBe('other');
  });

  it('合并时去重、按时间排序并保留最近 N 条', () => {
    const a = message({ at: 1, body: 'a' });
    const b = message({ at: 3, body: 'b' });
    const c = message({ at: 2, body: 'c' });

    const merged = mergeMessages([b], [a, b, c], 10);
    expect(merged.map((item) => item.body)).toEqual(['a', 'c', 'b']);

    const capped = mergeMessages([a], [b, c], 2);
    expect(capped.map((item) => item.body)).toEqual(['c', 'b']);
  });

  it('去重键区分方向与内容', () => {
    const left = message({ direction: 'in', at: 1, body: 'x' });
    const right = message({ direction: 'out', at: 1, body: 'x' });
    expect(messageKey(left)).not.toBe(messageKey(right));
    expect(mergeMessages([left], [left, right])).toHaveLength(2);
  });

  it('统计各分类条数', () => {
    const counts = messageCounts([
      message({ stream: 1, function: 1 }),
      message({ stream: 1, function: 2 }),
      message({ stream: 6, function: 11 }),
      message({ stream: 6, function: 1 }),
      message({ stream: 2, function: 41 }),
    ]);
    expect(counts.probe).toBe(2);
    expect(counts.event).toBe(1);
    expect(counts.trace).toBe(1);
    expect(counts.other).toBe(1);
  });
});
