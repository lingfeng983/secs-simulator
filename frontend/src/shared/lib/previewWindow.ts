/**
 * 预览窗口计算。
 *
 * <p>后端引擎时钟从进程启动起算（uptime），所以「最近 N 秒」必须先用
 * {@code health().uptimeMs} 换算成绝对时间窗。抽成纯函数是为了能直接单测
 * 三个边界：uptime 缺失、窗口比 uptime 长、正常窗口。</p>
 */
export interface PreviewWindow {
  /** 左端点（毫秒，不小于 0）。 */
  from: number;
  /** 右端点（毫秒，至少 1，避免后端把 0 当成非法窗口）。 */
  to: number;
}

/**
 * 由进程 uptime 与窗口长度算出 [from, to]。
 *
 * @param uptimeMs 后端 uptime；老后端没有时按 0 处理
 * @param windowMs 想看的最近时长；负数按 0 处理
 */
export function previewWindow(uptimeMs: number | undefined, windowMs: number): PreviewWindow {
  const to = Math.max(1, uptimeMs ?? 0);
  const span = Math.max(0, windowMs);
  return { from: Math.max(0, to - span), to };
}
