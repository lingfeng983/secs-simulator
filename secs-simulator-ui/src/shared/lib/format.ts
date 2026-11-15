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

/**
 * 目标串里的主机部分："127.0.0.1:5000" → "127.0.0.1"。
 *
 * <p>主机实例的 target 是后端拼好的 host:port，端口另有独立字段；
 * 两处都取会把端口显示两遍。</p>
 */
export function hostOfTarget(target: string): string {
  const text = target.trim();
  const match = /^(.*):(\d{1,5})$/.exec(text);
  return match === null ? text : match[1];
}
