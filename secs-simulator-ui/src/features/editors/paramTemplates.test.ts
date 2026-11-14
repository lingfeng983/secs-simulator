import { describe, expect, it } from 'vitest';
import { materializeIntoRoot, singleCurveBodyOf } from './paramTemplates';
import type { ParamTemplate } from '../../api/paramTemplates';
import { readTemplates } from './templateConfig';

/** 造一枚库条目。 */
function entry(id: string, body: Record<string, unknown>): ParamTemplate {
  return { id, name: id, description: '', source: 'custom', readonly: false, body, updatedAt: 1 };
}

const SINE = { type: 'sine', min: 0, max: 10, periodMs: 1000 };

describe('paramTemplates 物化（复制快照进设备配置）', () => {
  it('单曲线简形：追加曲线+实例，直配落点不带 output', () => {
    const root: Record<string, unknown> = {};
    const binding = materializeIntoRoot(
      root,
      entry('pid', { curves: [{ id: 'main', ...SINE }], result: 'main.V' }),
      1001,
    );

    expect(typeof binding).toBe('object');
    const view = readTemplates(root);
    expect(view.ok).toBe(true);
    const templates = (view as { ok: true; templates: { curves: unknown[]; instances: unknown[] } }).templates;
    expect(templates.curves).toHaveLength(1);
    expect(templates.instances).toHaveLength(1);
    const ok = binding as { templateId: string; output?: string };
    expect(ok.templateId).toBe('pid-main');
    expect(ok.output).toBeUndefined();
    /* 中间实例不绑 SVID */
    expect(root.templates).toMatchObject({
      curves: [{ id: 'pid-main', type: 'sine' }],
      instances: [{ id: 'pid-main', template: 'pid-main', svids: {} }],
    });
  });

  it('id 冲突时改写并同步实例引用', () => {
    const root: Record<string, unknown> = {
      templates: { curves: [{ id: 'pid-main', ...SINE }], instances: [] },
    };
    const binding = materializeIntoRoot(
      root,
      entry('pid', { curves: [{ id: 'main', ...SINE }], result: 'main.V' }),
      1002,
    );

    const ok = binding as { templateId: string };
    expect(ok.templateId).toBe('pid-main2');
    const templates = (root.templates as { curves: Array<{ id: string }>; instances: Array<{ id: string; template: string }> });
    expect(templates.curves.map((item) => item.id)).toEqual(['pid-main', 'pid-main2']);
    expect(templates.instances[0].template).toBe('pid-main2');
  });

  it('表达式条目：多输出组合带 output，表达式引用随实例改写，params 跟随', () => {
    const root: Record<string, unknown> = {};
    const binding = materializeIntoRoot(
      root,
      entry('mix', {
        curves: [{ id: 'base', ...SINE }],
        composites: [{ id: 'main', outputs: { low: 'in.base-i.V * 0.5', high: 'in.base-i.V * 2' } }],
        instances: [
          { id: 'base-i', template: 'base', svids: {} },
          { id: 'app', template: 'main', svids: {}, params: { k: 1.5 } },
        ],
        result: 'app.high',
      }),
      1003,
    );

    const ok = binding as { templateId: string; output: string; params: Record<string, number> };
    expect(ok.templateId).toBe('mix-main');
    expect(ok.output).toBe('high');
    expect(ok.params).toEqual({ k: 1.5 });
    const templates = (root.templates as {
      composites: Array<{ id: string; outputs: Record<string, string> }>;
      instances: Array<{ id: string; template: string; svids: Record<string, never> }>;
    });
    expect(templates.composites[0].outputs.high).toBe('in.mix-base-i.V * 2');
    expect(templates.composites[0].outputs.low).toBe('in.mix-base-i.V * 0.5');
    expect(templates.instances[0].id).toBe('mix-base-i');
    expect(templates.instances[0].svids).toEqual({});
  });

  it('同一条目绑两个 SVID 得到两份独立拷贝', () => {
    const root: Record<string, unknown> = {};
    const body = { curves: [{ id: 'main', ...SINE }], result: 'main.V' };
    const first = materializeIntoRoot(root, entry('pid', body), 1001) as { templateId: string };
    const second = materializeIntoRoot(root, entry('pid', body), 1002) as { templateId: string };

    expect(first.templateId).toBe('pid-main');
    expect(second.templateId).toBe('pid-main2');
    const templates = (root.templates as { instances: Array<{ id: string; template: string }> });
    expect(templates.instances.map((item) => item.template)).toEqual(['pid-main', 'pid-main2']);
  });

  it('result 形状不对时报错且不落盘', () => {
    const root: Record<string, unknown> = {};
    const binding = materializeIntoRoot(root, entry('pid', { curves: [{ id: 'main', ...SINE }] }), 1001);
    expect(typeof binding).toBe('string');
    expect(root.templates).toBeUndefined();
  });

  it('singleCurveBodyOf：单曲线条目取曲线（去 id），组合条目不行', () => {
    expect(singleCurveBodyOf(entry('a', { curves: [{ id: 'm', ...SINE }], result: 'm.V' })))
      .toEqual({ type: 'sine', min: 0, max: 10, periodMs: 1000 });
    expect(singleCurveBodyOf(entry('b', {
      curves: [{ id: 'm', ...SINE }],
      composites: [{ id: 'c', outputs: { v: '1' } }],
      result: 'c.v',
    }))).toBeUndefined();
  });
});
