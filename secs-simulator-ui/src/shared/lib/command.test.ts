import { describe, expect, it } from 'vitest';
import { groupCommands, matchCommands, scoreCommand, MAX_COMMANDS, type CommandItem } from './command';

function item(label: string, group = '操作', keywords: string[] = []): CommandItem {
  return { id: label, label, group, keywords, run: () => undefined };
}

describe('scoreCommand', () => {
  it('scores an exact match highest', () => {
    const target = item('曲线预览');
    expect(scoreCommand(target, '曲线预览')).toBeGreaterThan(scoreCommand(target, '曲线'));
    expect(scoreCommand(target, '曲线预览')).toBeGreaterThan(scoreCommand(target, '预览 曲线'));
  });

  it('scores a prefix above a mere substring', () => {
    const target = item('曲线预览');
    expect(scoreCommand(target, '曲线')).toBeGreaterThan(scoreCommand(target, '览'));
  });

  it('matches keywords', () => {
    const target = item('上报', '操作', ['ceid', '触发']);
    expect(scoreCommand(target, 'ceid')).toBeGreaterThan(0);
  });

  it('returns -1 when nothing matches', () => {
    expect(scoreCommand(item('上报'), 'zzz')).toBe(-1);
  });

  it('matches everything for an empty query', () => {
    expect(scoreCommand(item('上报'), '')).toBeGreaterThan(0);
  });
});

describe('matchCommands', () => {
  const items = [
    item('曲线预览', '跳转面板'),
    item('预览统计', '数据'),
    item('上报 CEID 2001', '设备目录', ['ceid']),
  ];

  it('keeps matching items only', () => {
    const result = matchCommands(items, 'ceid');
    expect(result.map((entry) => entry.label)).toEqual(['上报 CEID 2001']);
  });

  it('sorts by score, not by declaration order', () => {
    /* 「预览统计」以查询词开头（前缀 80 分），「曲线预览」只是包含（40 分），
       所以前者排前面——敲了词首就该看到以它开头的条目。 */
    const result = matchCommands(items, '预览');
    expect(result.map((entry) => entry.label)).toEqual(['预览统计', '曲线预览']);
  });

  it('ranks a word-start match above a plain substring match', () => {
    /* 「曲线 预览」里「预览」是独立的一词（词首命中 60 分）；
       「数据预览表」只能算整体包含（40 分），而且不以查询词开头。 */
    const spaced = [item('曲线 预览'), item('数据预览表')];
    const result = matchCommands(spaced, '预览');
    expect(result.map((entry) => entry.label)).toEqual(['曲线 预览', '数据预览表']);
  });

  it('caps the number of results', () => {
    const many = Array.from({ length: MAX_COMMANDS + 20 }, (_, index) => item(`命令 ${index}`));
    expect(matchCommands(many, '')).toHaveLength(MAX_COMMANDS);
  });
});

describe('groupCommands', () => {
  it('groups items and preserves order inside a group', () => {
    const grouped = groupCommands([item('a', '甲'), item('b', '乙'), item('c', '甲')]);
    expect(grouped.map((group) => group.group)).toEqual(['甲', '乙']);
    expect(grouped[0].items.map((entry) => entry.label)).toEqual(['a', 'c']);
  });
});
