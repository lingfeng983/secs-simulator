import { useMemo, useState } from 'react';
import type { PreviewData, PreviewEventMark } from '../api';
import { eventColorIndexes, eventColorToken, eventKey, eventLegendLabel } from '../lib/eventColors';
import { Sparkline, type ZoomRange } from './Sparkline';

/**
 * 事件驱动曲线：一组 SVID 叠同一条时间轴，事件发生的时刻画竖线。
 *
 * <p>曲线与事件标记来自**同一次**后端模拟（曲线已叠加事件效果层），
 * 所以"事件在 t 时刻触发 → 曲线在 t 之后跳变"是对得上的。
 * 每条计划一种颜色（竖线、顶部三角与图例一致），同一张图里能分辨是谁触发的。</p>
 */

export interface EventPreviewProps {
  data: PreviewData;
  /** 只画这些 SVID；不给就画全部（后端已按上限截断）。 */
  svids?: readonly number[];
  /** 画布高度（px）。 */
  height?: number;
  /** 图例里显示的变量名（没给就用 SVID 编号）。 */
  labels?: ReadonlyMap<number, string>;
  /** 竖线标题前缀：默认「事件」。 */
  eventLabel?: string;
}

export function EventPreview({
  data,
  svids,
  height = 96,
  labels,
  eventLabel = '事件',
}: EventPreviewProps): JSX.Element {
  const selected = svids === undefined
    ? data.series
    : data.series.filter((item) => svids.includes(item.svid));

  const events: PreviewEventMark[] = data.events ?? [];
  /* 所有行共享一个缩放区间，便于对齐读同一段时间 */
  const [zoom, setZoom] = useState<ZoomRange | null>(null);
  const [showLegend, setShowLegend] = useState(true);
  const bucketCount = data.series[0]?.avg.length ?? 0;

  const zoomBy = (factor: number) => {
    if (bucketCount <= 1) {
      return;
    }
    const start = zoom?.start ?? 0;
    const end = zoom?.end ?? bucketCount - 1;
    const span = end - start + 1;
    const nextSpan = Math.max(2, Math.min(bucketCount, Math.round(span * factor)));
    const center = (start + end) / 2;
    let nextStart = Math.round(center - nextSpan / 2);
    nextStart = Math.max(0, Math.min(bucketCount - nextSpan, nextStart));
    setZoom({ start: nextStart, end: nextStart + nextSpan - 1 });
  };

  /* 按"计划/CEID"聚合图例：同一计划的事件只占一条，后面带次数 */
  const legendGroups = useMemo(() => {
    const colorIndexes = eventColorIndexes(events);
    const groups = new Map<string, { key: string; label: string; color: string; count: number; firstAt: number }>();
    for (const event of events) {
      const key = eventKey(event);
      const existing = groups.get(key);
      if (existing === undefined) {
        groups.set(key, {
          key,
          label: eventLegendLabel(event),
          color: eventColorToken(colorIndexes.get(key) ?? 1),
          count: 1,
          firstAt: event.at,
        });
      } else {
        existing.count += 1;
      }
    }
    return [...groups.values()].sort((left, right) => left.firstAt - right.firstAt);
  }, [events]);

  return (
    <div className="event-preview">
      <div className="chart-toolbar">
        <button type="button" onClick={() => zoomBy(0.7)} title="放大时间轴">＋</button>
        <button type="button" onClick={() => zoomBy(1.4)} title="缩小时间轴">－</button>
        <button type="button" onClick={() => setZoom(null)} title="恢复全窗口">复位</button>
        <span className="hint-inline">拖动任一行框选放大 · 双击复位</span>
        <span className="spacer" />
        <label className="check-inline">
          <input
            type="checkbox"
            checked={showLegend}
            onChange={(event) => setShowLegend(event.target.checked)}
          />
          显示事件图例
        </label>
      </div>
      {selected.map((series) => (
        <div className="event-preview-row" key={series.svid}>
          <div className="event-preview-label">
            <span className="mono">{series.svid}</span>
            <span className="hint-inline">{labels?.get(series.svid) ?? series.owner ?? ''}</span>
            {(series.unit ?? '') !== '' && <span className="hint-inline">({series.unit})</span>}
          </div>
          <Sparkline
            series={series}
            fromMs={data.from}
            bucketMs={data.bucketMs}
            events={events}
            zoom={zoom}
            onZoom={setZoom}
            height={height}
            showId={false}
          />
        </div>
      ))}

      {showLegend && events.length > 0 && (
        <div className="event-legend">
          <span className="event-legend-title">
            {eventLabel}（{events.length}）
          </span>
          {legendGroups.slice(0, 12).map((group) => (
            <span className="event-legend-item" key={group.key}>
              <span className="event-swatch" style={{ background: group.color }} aria-hidden="true" />
              <span>{group.label}</span>
              {group.count > 1 && <span className="hint-inline">×{group.count}</span>}
            </span>
          ))}
          {legendGroups.length > 12 && <span className="hint-inline">…还有 {legendGroups.length - 12} 类</span>}
        </div>
      )}
    </div>
  );
}
