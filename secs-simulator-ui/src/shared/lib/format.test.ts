import { describe, expect, it } from 'vitest';
import {
  formatCount,
  formatValue,
  hostOfTarget } from './format';

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

describe('formatCount', () => {
  it('defaults missing counters to zero', () => {
    expect(formatCount(undefined)).toBe('0');
  });

  it('keeps zero as zero', () => {
    expect(formatCount(0)).toBe('0');
  });
});

describe('hostOfTarget', () => {
  it('strips the port the backend already appended', () => {
    expect(hostOfTarget('127.0.0.1:5000')).toBe('127.0.0.1');
    expect(hostOfTarget('192.168.8.30:7000')).toBe('192.168.8.30');
  });

  it('keeps a bare host untouched', () => {
    expect(hostOfTarget('plc-01')).toBe('plc-01');
    expect(hostOfTarget('  10.0.0.9 ')).toBe('10.0.0.9');
  });
});
