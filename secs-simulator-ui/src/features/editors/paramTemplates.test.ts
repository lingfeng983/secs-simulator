import { describe, expect, it } from 'vitest';
import { copyTemplateSource, templateShapeOf, templateSourceOf } from './paramTemplates';
import type { ParamTemplate } from '../../api/paramTemplates';

function entry(id: string, body: Record<string, unknown>): ParamTemplate {
  return { id, name: id, description: '', source: 'builtin', readonly: true, body, updatedAt: 0 };
}

const SINE = { type: 'sine', min: 100, max: 300, periodMs: 60_000 };

describe('templateSourceOf', () => {
  it('读 body.source；没有或形状不对时返回 undefined', () => {
    expect(templateSourceOf(entry('a', { source: { mode: 'curve', curve: SINE } })))
      .toEqual({ mode: 'curve', curve: SINE });
    expect(templateSourceOf(entry('b', {}))).toBeUndefined();
    expect(templateSourceOf(entry('c', { source: 1 }))).toBeUndefined();
  });
});

describe('templateShapeOf', () => {
  it('给出可读形态描述', () => {
    expect(templateShapeOf(entry('a', { source: { mode: 'curve', curve: SINE } }))).toContain('sine');
    expect(templateShapeOf(entry('b', { source: { mode: 'expr', vars: { a: SINE }, expr: 'a' } })))
      .toContain('表达式');
    expect(templateShapeOf(entry('c', {}))).toBe('');
  });
});

describe('copyTemplateSource', () => {
  it('深拷贝：改设备侧对象不影响库条目，反之亦然', () => {
    const source = entry('a', { source: { mode: 'curve', curve: { ...SINE } } });
    const first = copyTemplateSource(source);
    const second = copyTemplateSource(source);
    expect(first).toEqual(second);
    expect(first).not.toBe(second);

    if (first !== undefined && first.curve !== null && typeof first.curve === 'object') {
      (first.curve as Record<string, unknown>).max = 999;
    }
    expect((source.body.source as { curve: { max: number } }).curve.max).toBe(300);
    expect(second).toEqual(templateSourceOf(source));
  });

  it('没有 source 时返回 undefined', () => {
    expect(copyTemplateSource(entry('x', {}))).toBeUndefined();
  });
});
