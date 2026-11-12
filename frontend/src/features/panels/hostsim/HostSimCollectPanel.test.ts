import { describe, expect, it } from 'vitest';
import { parseSvidList } from './HostSimCollectPanel';

/**
 * 远程主机的 SVID 手填解析。
 *
 * <p>远程模式没有 catalog，用户会把列表从 Excel / 对端配置里直接粘进来：
 * 逗号、空格、换行、中文逗号都可能出现。这里只负责把能认出来的数字拆出来，
 * 其余片段丢弃，避免一个笔误让整份配置下发失败。</p>
 */
describe('parseSvidList', () => {
  it('splits on commas, spaces, newlines and Chinese punctuation', () => {
    expect(parseSvidList('1001, 1003;1005\n1007、1009，1011')).toEqual([
      1001, 1003, 1005, 1007, 1009, 1011,
    ]);
  });

  it('drops non-numeric fragments instead of failing the whole list', () => {
    expect(parseSvidList('1001 abc 1003')).toEqual([1001, 1003]);
  });

  it('keeps the first occurrence and drops duplicates', () => {
    expect(parseSvidList('1001 1001 1003')).toEqual([1001, 1003]);
  });

  it('rejects zero, negatives and decimals', () => {
    expect(parseSvidList('0 -1 1001 10.5')).toEqual([1001]);
  });

  it('returns an empty list for blank input', () => {
    expect(parseSvidList('')).toEqual([]);
    expect(parseSvidList('   \n  ')).toEqual([]);
  });

  it('does not cap the count: the device decides the limit', () => {
    const many = Array.from({ length: 40 }, (_, index) => String(1000 + index)).join(' ');
    expect(parseSvidList(many)).toHaveLength(40);
  });
});
