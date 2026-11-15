import { describe, expect, it } from 'vitest';
import {
  BUILTIN_HOST_TEMPLATES,
  decodeHostTemplate,
  encodeHostTemplate,
  instantiateTemplatePlans,
  isHostTemplateBundle,
  templatePlanCount,
  type HostTemplate,
} from './hostTemplates';
import type { HostTemplateRow } from '../api';

/** 构造一行后端模板数据。 */
function row(note: string): HostTemplateRow {
  return { id: 'ht-1', name: '示例模板', host: '-', port: 5000, deviceId: 0, note };
}

const sample: HostTemplate = {
  id: 'ht-1',
  name: '示例模板',
  note: '两台设备共用',
  builtin: false,
  tracePlans: BUILTIN_HOST_TEMPLATES[0].tracePlans,
  collectPlans: BUILTIN_HOST_TEMPLATES[0].collectPlans,
};

describe('主机模板方案包', () => {
  it('内置两份模板，各自带 Trace 与采集方案', () => {
    expect(BUILTIN_HOST_TEMPLATES).toHaveLength(2);
    for (const template of BUILTIN_HOST_TEMPLATES) {
      expect(template.builtin).toBe(true);
      expect(template.tracePlans.length).toBeGreaterThan(0);
      expect(template.collectPlans.length).toBeGreaterThan(0);
    }
  });

  it('编解码往返不丢方案与说明', () => {
    const decoded = decodeHostTemplate(row(encodeHostTemplate(sample)));
    expect(decoded.name).toBe('示例模板');
    expect(decoded.note).toBe('两台设备共用');
    expect(decoded.tracePlans).toHaveLength(1);
    expect(decoded.collectPlans).toHaveLength(1);
    expect(decoded.tracePlans[0]).toMatchObject({ trid: 1, dsper: 1000, svids: '1001, 1003' });
    expect(templatePlanCount(decoded)).toBe(2);
  });

  it('旧数据（只有目标信息的纯说明）不算模板', () => {
    const legacy = row('日常联调');
    expect(isHostTemplateBundle(legacy)).toBe(false);
    expect(isHostTemplateBundle(row(encodeHostTemplate(sample)))).toBe(true);
  });

  it('套用时给每份方案换新 id，模板本身不被改动', () => {
    const before = sample.tracePlans.map((plan) => plan.id);
    const plans = instantiateTemplatePlans(sample);
    expect(plans.trace).toHaveLength(1);
    expect(plans.collect).toHaveLength(1);
    expect(plans.trace[0].id).not.toBe(before[0]);
    expect(plans.collect[0].id).not.toBe(sample.collectPlans[0].id);
    expect(sample.tracePlans.map((plan) => plan.id)).toEqual(before);
    expect(plans.trace[0]).toMatchObject({ trid: 1, dsper: 1000, svids: '1001, 1003' });
  });
});
