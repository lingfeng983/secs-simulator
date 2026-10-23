import { describe, expect, it } from 'vitest';
import type { PreviewSeries } from '../api';
import { computeAllStats, computeSeriesStats } from './previewStats';

const SERIES: PreviewSeries = {
  svid: 1001,
  owner: 'CH1',
  unit: 'C',
  min: [10, 20, 30],
  max: [20, 30, 40],
  avg: [15, 25, 35],
};

describe('computeSeriesStats', () => {
  it('takes min and max across the envelope, not per-array', () => {
    const stats = computeSeriesStats(SERIES);
    expect(stats.min).toBe(10);
    expect(stats.max).toBe(40);
  });

  it('averages the avg series', () => {
    expect(computeSeriesStats(SERIES).avg).toBe(25);
  });

  it('reports the last finite bucket as the last value', () => {
    expect(computeSeriesStats(SERIES).last).toBe(35);
  });

  it('reports the peak-to-peak span', () => {
    expect(computeSeriesStats(SERIES).span).toBe(30);
  });

  it('counts buckets with non-finite values', () => {
    const broken: PreviewSeries = {
      svid: 1,
      min: [1, Number.NaN, 3],
      max: [1, 2, 3],
      avg: [1, 2, 3],
    };
    expect(computeSeriesStats(broken).invalid).toBe(1);
  });

  it('survives an entirely empty series', () => {
    const empty: PreviewSeries = { svid: 1, min: [], max: [], avg: [] };
    const stats = computeSeriesStats(empty);
    expect(stats.buckets).toBe(0);
    expect(stats.avg).toBe(0);
    expect(stats.last).toBe(0);
    expect(Number.isNaN(stats.span)).toBe(false);
  });

  it('skips trailing invalid buckets when finding the last value', () => {
    const trailing: PreviewSeries = {
      svid: 1,
      min: [1, 2, Number.NaN],
      max: [1, 2, Number.NaN],
      avg: [1, 2, Number.NaN],
    };
    expect(computeSeriesStats(trailing).last).toBe(2);
  });
});

describe('computeAllStats', () => {
  it('keeps the input order', () => {
    const stats = computeAllStats([
      { svid: 2, min: [0], max: [0], avg: [0] },
      { svid: 1, min: [0], max: [0], avg: [0] },
    ]);
    expect(stats.map((entry) => entry.svid)).toEqual([2, 1]);
  });
});
