import { describe, expect, it } from 'vitest';
import { diffLines, foldUnchanged } from './textDiff';

describe('diffLines', () => {
  it('marks a single changed line pair', () => {
    const diff = diffLines('{"a": 1}', '{"a": 2}');
    expect(diff).toEqual([
      { op: 'removed', lineNumber: 1, text: '{"a": 1}' },
      { op: 'added', lineNumber: 1, text: '{"a": 2}' },
    ]);
  });

  it('keeps unchanged lines as same with new line numbers', () => {
    const diff = diffLines('a\nb\nc', 'a\nB\nc');
    expect(diff[0]).toEqual({ op: 'same', lineNumber: 1, text: 'a' });
    expect(diff[1]).toEqual({ op: 'removed', lineNumber: 2, text: 'b' });
    expect(diff[2]).toEqual({ op: 'added', lineNumber: 2, text: 'B' });
    expect(diff[3]).toEqual({ op: 'same', lineNumber: 3, text: 'c' });
  });

  it('handles pure addition at the end', () => {
    const diff = diffLines('a', 'a\nb');
    expect(diff).toEqual([
      { op: 'same', lineNumber: 1, text: 'a' },
      { op: 'added', lineNumber: 2, text: 'b' },
    ]);
  });

  it('handles identical text', () => {
    expect(diffLines('x\ny', 'x\ny').every((line) => line.op === 'same')).toBe(true);
  });
});

describe('foldUnchanged', () => {
  it('collapses long unchanged runs between edits', () => {
    const lines = [
      ...Array.from({ length: 10 }, (_, i) => ({ op: 'same' as const, lineNumber: i + 1, text: `same${i}` })),
      { op: 'removed' as const, lineNumber: 11, text: 'old' },
      { op: 'added' as const, lineNumber: 11, text: 'new' },
    ];
    const folded = foldUnchanged(lines, 2);
    /* 前 10 行 same:保留改动前 2 行,其余折叠 */
    expect(folded[0].op).toBe('skip');
    expect(folded[0].count).toBe(8);
    expect(folded.slice(-3).map((line) => line.op)).toEqual(['same', 'removed', 'added']);
  });

  it('keeps everything when nothing is folded', () => {
    const folded = foldUnchanged([
      { op: 'same', lineNumber: 1, text: 'a' },
      { op: 'added', lineNumber: 2, text: 'b' },
    ]);
    expect(folded).toHaveLength(2);
  });
});
