import { describe, expect, it } from 'vitest';
import { CONFIG_SECTIONS, effectNote, sectionLabels, sectionPayload, sectionLabel } from './configSections';

const root = {
  session: { id: 'D1', port: 5000 },
  equipment: {
    name: 'ETCH-01',
    idSpace: { svidBase: 1000, ceidBase: 2000 },
    units: [
      { unitId: 1, name: 'CH1', injectUnitId: true, svids: [{ offset: 1, slot: 'TEMP_PV' }], reports: [{ offset: 1 }], ceids: [{ offset: 1 }], constants: [] },
      { unitId: 2, name: 'CH2', svids: [], reports: [], ceids: [] },
    ],
    constants: [{ ecid: 1, value: 3 }],
    globalSvids: [{ offset: 900, slot: 'G' }],
  },
  events: { maxPerSec: 50, s6f11Wbit: true, s6f1Wbit: false, plans: [{ id: 'p1', ceid: 2001 }] },
  context: [{ id: 'LOTID', value: 'A' }],
  cycle: { durationMs: 180000, count: 25, events: [] },
};

describe('sectionPayload', () => {
  it('连接段直接取 session，不夹带别的东西', () => {
    expect(sectionPayload(['session'], root)).toEqual({ session: root.session });
  });

  it('结构段只带 unitId/名称/injectUnitId——变量与报告属于各自的段', () => {
    const payload = sectionPayload(['structure'], root) as { structure: Record<string, unknown> };
    expect(payload.structure.name).toBe('ETCH-01');
    expect(payload.structure.idSpace).toEqual(root.equipment.idSpace);
    expect(payload.structure.units).toEqual([
      { unitId: 1, name: 'CH1', injectUnitId: true },
      { unitId: 2, name: 'CH2', injectUnitId: undefined },
    ]);
    expect(JSON.stringify(payload)).not.toContain('TEMP_PV');
  });

  it('变量段按单元切 svids，并带 globalSvids', () => {
    const payload = sectionPayload(['svids'], root) as { svids: Record<string, unknown> };
    expect(payload.svids.units).toEqual([
      { unitId: 1, svids: root.equipment.units[0].svids },
      { unitId: 2, svids: [] },
    ]);
    expect(payload.svids.globalSvids).toEqual(root.equipment.globalSvids);
  });

  it('事件与报告页一次提交两段', () => {
    const payload = sectionPayload(['ceids', 'reports'], root);
    expect(Object.keys(payload)).toEqual(['ceids', 'reports']);
    expect((payload.ceids as { units: unknown[] }).units).toHaveLength(2);
    expect((payload.reports as { units: unknown[] }).units).toHaveLength(2);
  });

  it('周期段删掉时提交 null（表示删除该段），不是省略键', () => {
    const payload = sectionPayload(['cycle'], { ...root, cycle: undefined });
    expect(payload).toHaveProperty('cycle');
    expect(payload.cycle).toBeNull();
  });

  it('计划与发送口径从 events 里切，互不带上对方', () => {
    const plans = sectionPayload(['plans'], root) as { plans: unknown };
    expect(plans.plans).toEqual(root.events.plans);
    const throttle = sectionPayload(['throttle'], root) as { throttle: Record<string, unknown> };
    expect(throttle.throttle).toEqual({ maxPerSec: 50, s6f11Wbit: true, s6f1Wbit: false });
  });

  it('缺段时不抛异常：给空数组', () => {
    const payload = sectionPayload(['plans', 'constants'], {});
    expect(payload.plans).toEqual([]);
    expect(payload.constants).toEqual([]);
  });
});

describe('sectionLabel / sectionLabels', () => {
  it('段名翻中文，多段用顿号', () => {
    expect(sectionLabel('cycle')).toBe('工艺周期');
    expect(sectionLabels(['plans', 'throttle'])).toBe('事件计划、事件发送');
  });

  it('每个段都有中文名与影响级别', () => {
    for (const [name, meta] of Object.entries(CONFIG_SECTIONS)) {
      expect(meta.label).not.toBe(name);
      expect(['reconnect', 'hot-reset', 'hot']).toContain(meta.effect);
    }
  });
});

describe('effectNote', () => {
  it('周期被重置时明确要求重新启动', () => {
    expect(effectNote('hot', ['cycle'], 0, 0)).toContain('重新启动');
  });

  it('计划换表且保留了几条：说清保留的数量', () => {
    expect(effectNote('hot', ['plans'], 0, 3)).toContain('3 条未改动的计划保持运行');
  });

  it('连接段变化提示断链与全清', () => {
    expect(effectNote('reconnect', [], 0, 0)).toContain('主机需重新连接');
  });

  it('什么都没重置就不写提示（右下角已有保存 toast）', () => {
    expect(effectNote('hot', [], 0, 0)).toBe('');
  });
});
