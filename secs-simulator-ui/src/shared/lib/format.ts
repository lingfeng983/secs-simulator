/**
 * 界面用的格式化函数。
 *
 * <p>全部是纯函数：同样的输入永远得到同样的输出，
 * 这样才能单独测，也才能在多个面板之间复用同一套口径。</p>
 */

/** 数值：够用就好，不制造假精度。 */
export function formatValue(value: number): string {
  if (!Number.isFinite(value)) {
    return '-';
  }
  if (Math.abs(value) >= 1000) {
    return value.toFixed(1);
  }
  return value.toFixed(3).replace(/0+$/, '').replace(/\.$/, '');
}

/** 时钟时间（毫秒时间戳 → 本地时:分:秒）。 */
export function formatClock(at: number): string {
  if (!Number.isFinite(at) || at <= 0) {
    return '-';
  }
  return new Date(at).toLocaleTimeString();
}

/** 日期时间。 */
export function formatDateTime(at: number): string {
  if (!Number.isFinite(at) || at <= 0) {
    return '-';
  }
  return new Date(at).toLocaleString();
}

/** 逗号分隔的千分位，给计数用。 */
export function formatCount(value: number | undefined): string {
  if (value === undefined || !Number.isFinite(value)) {
    return '0';
  }
  return value.toLocaleString();
}
