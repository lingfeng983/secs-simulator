import { describe, expect, it } from 'vitest';
import {
  formatBytes,
  formatCount,
  formatDuration,
  formatRelative,
  formatSeconds,
  formatValue,
} from './format';

describe('formatValue', () => {
  it('drops trailing zeros but keeps significant decimals', () => {
    expect(formatValue(12.5)).toBe('12.5');
    expect(formatValue(12)).toBe('12');
    expect(formatValue(12.5001)).toBe('12.5');
  });

  it('switches to one decimal for large magnitudes', () => {
    expect(formatValue(1234.56)).toBe('1234.6');
  });

  it('renders non-finite values as a dash', () => {
    expect(formatValue(Number.NaN)).toBe('-');
    expect(formatValue(Number.POSITIVE_INFINITY)).toBe('-');
  });
});

describe('formatSeconds', () => {
  it('uses seconds below a minute', () => {
    expect(formatSeconds(12.345)).toBe('12.35s');
  });

  it('switches to minutes above a minute', () => {
    expect(formatSeconds(90)).toBe('1m30.0s');
  });
});

describe('formatDuration', () => {
  it('keeps milliseconds under a second', () => {
    expect(formatDuration(250)).toBe('250 ms');
  });

  it('uses seconds then minutes', () => {
    expect(formatDuration(1500)).toBe('1.5 s');
    expect(formatDuration(90_000)).toBe('1 min 30 s');
  });

  it('rejects negative input', () => {
    expect(formatDuration(-1)).toBe('-');
  });
});

describe('formatBytes', () => {
  it('scales units', () => {
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(2048)).toBe('2.0 KB');
    expect(formatBytes(5 * 1024 * 1024)).toBe('5.0 MB');
  });
});

describe('formatRelative', () => {
  const now = 1_700_000_000_000;

  it('reports sub-second differences as "just now"', () => {
    expect(formatRelative(now - 200, now)).toBe('刚刚');
  });

  it('reports seconds, minutes, hours and days', () => {
    expect(formatRelative(now - 5_000, now)).toBe('5 秒前');
    expect(formatRelative(now - 120_000, now)).toBe('2 分钟前');
    expect(formatRelative(now - 7_200_000, now)).toBe('2 小时前');
    expect(formatRelative(now - 172_800_000, now)).toBe('2 天前');
  });

  it('handles future timestamps without going negative', () => {
    expect(formatRelative(now + 1000, now)).toBe('刚刚');
  });

  it('rejects invalid timestamps', () => {
    expect(formatRelative(0, now)).toBe('-');
  });
});

describe('formatCount', () => {
  it('defaults missing counters to zero', () => {
    expect(formatCount(undefined)).toBe('0');
  });

  it('keeps zero as zero', () => {
    expect(formatCount(0)).toBe('0');
  });
});
