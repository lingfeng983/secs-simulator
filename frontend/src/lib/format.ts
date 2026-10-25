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

/** 秒数：超过一分钟用 m+s，够看趋势就行。 */
export function formatSeconds(seconds: number): string {
  if (!Number.isFinite(seconds)) {
    return '-';
  }
  if (seconds >= 60) {
    const minutes = Math.floor(seconds / 60);
    return `${minutes}m${(seconds - minutes * 60).toFixed(1)}s`;
  }
  return `${seconds.toFixed(2)}s`;
}

/** 时长（毫秒）：给"多久以前"和"耗时"用。 */
export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) {
    return '-';
  }
  if (ms < 1000) {
    return `${Math.round(ms)} ms`;
  }
  if (ms < 60_000) {
    return `${(ms / 1000).toFixed(1)} s`;
  }
  const minutes = Math.floor(ms / 60_000);
  const seconds = Math.round((ms - minutes * 60_000) / 1000);
  return `${minutes} min ${seconds} s`;
}

/** 文件大小。 */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) {
    return '-';
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
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

/**
 * 相对时间："3 秒前" / "2 分钟前"。
 *
 * <p>监控场景里"多久之前发生"比绝对时间更有用：
 * 绝对时间要人脑做减法，相对时间直接就是答案。</p>
 */
export function formatRelative(at: number, now: number = Date.now()): string {
  if (!Number.isFinite(at) || at <= 0) {
    return '-';
  }
  const delta = now - at;
  if (delta < 0) {
    return '刚刚';
  }
  if (delta < 1000) {
    return '刚刚';
  }
  if (delta < 60_000) {
    return `${Math.floor(delta / 1000)} 秒前`;
  }
  if (delta < 3_600_000) {
    return `${Math.floor(delta / 60_000)} 分钟前`;
  }
  if (delta < 86_400_000) {
    return `${Math.floor(delta / 3_600_000)} 小时前`;
  }
  return `${Math.floor(delta / 86_400_000)} 天前`;
}

/** 逗号分隔的千分位，给计数用。 */
export function formatCount(value: number | undefined): string {
  if (value === undefined || !Number.isFinite(value)) {
    return '0';
  }
  return value.toLocaleString();
}

/**
 * 从 S6F11 的 SML body 反解 CEID。
 *
 * <p>历史报文里没有现成的 CEID 字段，只能从 body 取：S6F11 的数据是
 * <code>&lt;L[3] DATAID CEID 报告列表&gt;</code>,前两个数值项就是
 * DATAID 和 CEID——取第二个。解不出(非典型 body)返回 undefined,
 * 界面显示 '-'。仅供事件流「载入历史」展示,不参与逻辑。</p>
 */
export function ceidFromS6f11Body(body: string): number | undefined {
  const matches = body.matchAll(/<U\d+ (\d+)>/g);
  let index = 0;
  for (const match of matches) {
    index += 1;
    if (index === 2) {
      return Number.parseInt(match[1], 10);
    }
  }
  return undefined;
}
