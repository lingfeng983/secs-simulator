import type { PreviewSeries } from '../../api';

/**
 * 曲线窗口统计。
 *
 * <p>图只能看出"形状"，看不出"这个变量到底是 12 还是 1200"。
 * 窗口统计把 min/max/avg/last 并排列出来，
 * 值班的人扫一眼就知道该看哪条线。</p>
 */
export interface SeriesStats {
  svid: number;
  owner?: string;
  unit?: string;
  /** 窗口内的最小值。 */
  min: number;
  /** 窗口内的最大值。 */
  max: number;
  /** 窗口内的平均值（对 avg 序列再平均）。 */
  avg: number;
  /** 最后一个桶的值。 */
  last: number;
  /** 非有限值的桶数（NaN / Infinity 说明模拟器没在跑）。 */
  invalid: number;
  /** 样本桶数。 */
  buckets: number;
  /** 峰峰值：max - min，用来判断这条线有没有在动。 */
  span: number;
}

function finiteOr(value: number | undefined, fallback: number): number {
  return value !== undefined && Number.isFinite(value) ? value : fallback;
}

/**
 * 统计一条序列。
 *
 * <p>min/max 取 min、max 数组的并集（不是分开取），
 * 否则"下限曲线的最小值"和"上限曲线的最小值"会混为一谈，
 * 峰峰值就错了。</p>
 */
export function computeSeriesStats(series: PreviewSeries): SeriesStats {
  const buckets = series.avg.length;
  let min = Number.POSITIVE_INFINITY;
  let max = Number.NEGATIVE_INFINITY;
  let sum = 0;
  let counted = 0;
  let invalid = 0;

  for (let index = 0; index < buckets; index += 1) {
    const low = series.min[index];
    const high = series.max[index];
    const mid = series.avg[index];

    const lowOk = low !== undefined && Number.isFinite(low);
    const highOk = high !== undefined && Number.isFinite(high);
    const midOk = mid !== undefined && Number.isFinite(mid);

    if (lowOk) {
      min = Math.min(min, low);
    }
    if (highOk) {
      max = Math.max(max, high);
    }
    if (midOk) {
      sum += mid;
      counted += 1;
    }
    if (!lowOk || !highOk || !midOk) {
      invalid += 1;
    }
  }

  if (!Number.isFinite(min)) {
    min = 0;
  }
  if (!Number.isFinite(max)) {
    max = 0;
  }

  /* 最后一个有效桶；全空时给 0，不要给 NaN */
  let last = 0;
  for (let index = buckets - 1; index >= 0; index -= 1) {
    const mid = series.avg[index];
    if (mid !== undefined && Number.isFinite(mid)) {
      last = mid;
      break;
    }
  }

  return {
    svid: series.svid,
    owner: series.owner,
    unit: series.unit,
    min,
    max,
    avg: counted === 0 ? 0 : sum / counted,
    last,
    invalid,
    buckets,
    span: max - min,
  };
}

/** 批量统计。 */
export function computeAllStats(series: readonly PreviewSeries[]): SeriesStats[] {
  return series.map(computeSeriesStats);
}
