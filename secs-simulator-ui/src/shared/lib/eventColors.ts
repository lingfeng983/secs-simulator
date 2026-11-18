import type { PreviewEventMark } from '../../api';

/**
 * 事件预览的配色：按"计划（planId）"分色，没有 planId 时退回 CEID。
 *
 * <p>同一张图里同时画多条计划时，光靠竖线的位置分不出是谁触发的；
 * 颜色必须和事件绑定。颜色按"本图里出现的顺序"分配（而不是哈希），
 * 这样 6 条计划就有 6 种颜色，不会因哈希碰撞看混。</p>
 */

const COLOR_COUNT = 12;

/**
 * 事件的身份键。
 *
 * <p>默认识别"哪条计划"（{@code planId}）；工艺周期的片内事件在数据里统一
 * 带 {@code planId = 'cycle'}，一个周期里的几条不同事件就全被认成同一条——
 * 这时改用 {@code ceid} 归组（{@code groupBy = 'ceid'}）：片内事件的差别
 * 本来就在 CEID 上。</p>
 */
export function eventKey(event: PreviewEventMark, groupBy: EventGrouping = 'plan'): string {
  if (groupBy === 'ceid') {
    return `ceid:${event.ceid}`;
  }
  return event.planId !== undefined && event.planId !== ''
    ? `plan:${event.planId}`
    : `ceid:${event.ceid}`;
}

/** 事件归组口径：按计划（默认）或按 CEID（工艺周期片内事件用）。 */
export type EventGrouping = 'plan' | 'ceid';

/**
 * 本条事件集合 → 每个身份键的色号（1..12）。
 *
 * <p>按首次出现顺序编号：同一张图里的 EventCurveChart / VariableChart
 * 都从同一批事件生成映射，颜色自然一致。</p>
 */
export function eventColorIndexes(events: readonly PreviewEventMark[], groupBy: EventGrouping = 'plan'): Map<string, number> {
  const map = new Map<string, number>();
  for (const event of events) {
    const key = eventKey(event, groupBy);
    if (!map.has(key)) {
      map.set(key, (map.size % COLOR_COUNT) + 1);
    }
  }
  return map;
}

/** 色号 → canvas 用的 CSS 变量名。 */
export function eventColorVar(index: number): string {
  return `--event-${((index - 1) % COLOR_COUNT) + 1}`;
}

/** 色号 → DOM 用的颜色值。 */
export function eventColorToken(index: number): string {
  return `var(${eventColorVar(index)})`;
}

/** 图例文案：有计划名用计划名，否则用 CEID。 */
export function eventLegendLabel(event: PreviewEventMark, groupBy: EventGrouping = 'plan'): string {
  if (groupBy === 'ceid') {
    return `CEID ${event.ceid}`;
  }
  if (event.planId !== undefined && event.planId !== '') {
    return event.planId;
  }
  return `CEID ${event.ceid}`;
}
