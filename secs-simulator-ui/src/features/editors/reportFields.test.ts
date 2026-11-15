import { describe, expect, it } from 'vitest';
import { mergeReportFields, reportFieldGroupOf, splitReportFields } from './reportFields';

describe('reportFieldGroupOf', () => {
  it('按前缀分三类', () => {
    expect(reportFieldGroupOf('TEMP')).toBe('svid');
    expect(reportFieldGroupOf('ecid.501')).toBe('ecid');
    expect(reportFieldGroupOf('ctx.LOTID')).toBe('ctx');
  });

  it('名字里带点但不是前缀的仍算 SVID', () => {
    expect(reportFieldGroupOf('CH1.TEMP')).toBe('svid');
    expect(reportFieldGroupOf('ecid')).toBe('svid');
  });
});

describe('splitReportFields', () => {
  it('拆成三列且列内保持原文顺序', () => {
    const groups = splitReportFields(['ctx.LOTID', 'B', 'ecid.501', 'A', 'ctx.WAFERID', 'ecid.502']);
    expect(groups).toEqual({
      svid: ['B', 'A'],
      ecid: ['ecid.501', 'ecid.502'],
      ctx: ['ctx.LOTID', 'ctx.WAFERID'],
    });
  });

  it('空数组给出三个空列', () => {
    expect(splitReportFields([])).toEqual({ svid: [], ecid: [], ctx: [] });
  });
});

describe('mergeReportFields', () => {
  it('改动一列不影响其它列的原文顺序', () => {
    const current = ['A', 'ecid.501', 'ctx.LOTID', 'B'];
    expect(mergeReportFields(current, 'svid', ['A', 'B', 'C'])).toEqual([
      'A', 'B', 'C', 'ecid.501', 'ctx.LOTID',
    ]);
  });

  it('跨类交错时归一成 SVID → 设备常量 → 晶圆属性', () => {
    const current = ['ctx.LOTID', 'A', 'ecid.501'];
    expect(mergeReportFields(current, 'ctx', ['ctx.LOTID', 'ctx.WAFERID'])).toEqual([
      'A', 'ecid.501', 'ctx.LOTID', 'ctx.WAFERID',
    ]);
  });

  it('列内取消勾选只移除该项', () => {
    const current = ['A', 'B', 'ecid.501'];
    expect(mergeReportFields(current, 'svid', ['A'])).toEqual(['A', 'ecid.501']);
  });

  it('清空一列后其它列照常上报', () => {
    const current = ['A', 'ecid.501', 'ctx.LOTID'];
    expect(mergeReportFields(current, 'ecid', [])).toEqual(['A', 'ctx.LOTID']);
  });

  it('全选追加在已选项之后（保持勾选先后）', () => {
    const current = ['B'];
    expect(mergeReportFields(current, 'svid', ['B', 'A', 'C'])).toEqual(['B', 'A', 'C']);
  });
});