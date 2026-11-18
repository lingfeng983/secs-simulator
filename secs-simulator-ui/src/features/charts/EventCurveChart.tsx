import { useEffect, useMemo, useRef, useState } from 'react';
import type { PointerEvent as ReactPointerEvent } from 'react';
import type { PreviewData, PreviewEventMark, PreviewSeries } from '../../api';
import { eventColorIndexes, eventColorVar, eventKey, eventLegendLabel, type EventGrouping } from '../../shared/lib/eventColors';
import { formatSimTime, formatSimValue } from '../editors/paramTemplateSimulation';
import { useThemeVersion } from '../../state/useThemeVersion';

/**
 * 工艺参数 × 事件时刻图（草稿预览用）。
 *
 * <p>横轴是模拟时间，纵轴是参数值；选中的参数叠在<b>同一张</b>图上，
 * 每条一个颜色。事件的发送时刻画成竖虚线（颜色按计划/CEID 区分），
 * 顶部小三角标出确切位置——"这一刻发了什么"与"曲线当时在哪"对得上。</p>
 *
 * <p>为什么不用一排小图：现场的读法是"事件发生在曲线的哪个位置"，
 * 分成多行后竖直方向对不齐，反而要多看几眼。所以这里默认只画一个参数，
 * 需要对比时再勾选别的（曲线会叠上来），而不是一次把几十条画满。</p>
 */

export interface EventCurveChartProps {
  data: PreviewData;
  /** 要叠在图上的 SVID（按传入顺序取色与排序）；空数组 = 不画曲线。 */
  svids: readonly number[];
  /** 图例里显示的变量名（没给就用 SVID 编号）。 */
  labels?: ReadonlyMap<number, string>;
  /** 绘图区高度（px，不含坐标轴留白）。 */
  height?: number;
  /** 竖线标题前缀：默认「事件」。 */
  eventLabel?: string;
  /**
   * 横轴刻度怎么读：{@code absolute}（默认）按时间窗内的绝对时刻，
   * {@code relative} 按"距窗口起点多久"——运行区看的是最近 N 秒，
   * 标成 00:00..02:00 比标成 2:39:33 更好读。
   */
  relativeTime?: boolean;
  /**
   * 竖线怎么归组：按计划（默认）或按 CEID。
   * 工艺周期的片内事件在数据里都带 planId = 'cycle'，按计划归组会把不同
   * CEID 画成同一个颜色/同一条图例——那种情况传 'ceid'。
   */
  eventGrouping?: EventGrouping;
}

/** 坐标轴留白：左边放 Y 轴刻度，底下放 X 轴刻度。 */
const PAD_LEFT = 58;
const PAD_RIGHT = 14;
const PAD_TOP = 16;
const PAD_BOTTOM = 24;
/** 参数曲线色号循环（令牌见 tokens.css 的 --series-N）。 */
const SERIES_TOKENS = 8;

function seriesColorVar(index: number): string {
  return `--series-${(index % SERIES_TOKENS) + 1}`;
}

/** 缩放区间（桶下标，闭区间）。 */
interface BucketRange {
  start: number;
  end: number;
}

interface Palette {
  grid: string;
  axis: string;
  cursor: string;
  band: string;
  event: string;
}

function readPalette(canvas: HTMLCanvasElement): Palette {
  const styles = getComputedStyle(canvas);
  const read = (name: string, fallback: string): string => {
    const value = styles.getPropertyValue(name).trim();
    return value === '' ? fallback : value;
  };
  return {
    grid: read('--line', '#dde4ed'),
    axis: read('--line-strong', '#b9c4d2'),
    cursor: read('--muted', '#5f6f85'),
    band: read('--surface-inset', 'rgba(15,118,110,0.04)'),
    event: read('--warn', '#b45309'),
  };
}

const FALLBACK_MONO_FONT = '"JetBrains Mono", "Cascadia Mono", "SF Mono", Consolas, monospace';

/** canvas 不解析 CSS 变量：字体栈要读计算后的值，否则整条 font 声明会被丢掉。 */
function readFontFamily(canvas: HTMLCanvasElement): string {
  const styles = getComputedStyle(canvas);
  const mono = styles.getPropertyValue('--font-mono').trim();
  return mono !== '' ? mono : (styles.fontFamily.trim() || FALLBACK_MONO_FONT);
}

export function EventCurveChart({
  data,
  svids,
  labels,
  height = 190,
  eventLabel = '事件',
  eventGrouping = 'plan',
  relativeTime = false,
}: EventCurveChartProps): JSX.Element {
  const ref = useRef<HTMLCanvasElement | null>(null);
  const dragAnchor = useRef<number | null>(null);
  const themeVersion = useThemeVersion();
  const [hover, setHover] = useState<number | null>(null);
  const [view, setView] = useState<BucketRange | null>(null);
  const [dragging, setDragging] = useState<BucketRange | null>(null);
  /* 图例点一下隐藏、再点一下显示：只是想看清楚某一条时，不必去参数下拉里
     改选择（那会真的把它从图上摘掉，想看回来还得再勾一次）。 */
  const [hiddenSeries, setHiddenSeries] = useState<ReadonlySet<number>>(() => new Set());
  const [hiddenEvents, setHiddenEvents] = useState<ReadonlySet<string>>(() => new Set());

  /* 曲线按 svids 的顺序取：图例、取色、绘制的次序都跟用户勾选的顺序一致 */
  const series = useMemo<PreviewSeries[]>(() => {
    const all = data.series ?? [];
    return svids
      .map((svid) => all.find((item) => item.svid === svid))
      .filter((item): item is PreviewSeries => item !== undefined);
  }, [data, svids]);

  /* 颜色按"在 series 里的位置"定：隐藏一条时其余曲线的颜色不能跟着换 */
  const colorIndexOf = useMemo(() => {
    const map = new Map<number, number>();
    series.forEach((item, index) => map.set(item.svid, index));
    return map;
  }, [series]);

  const visibleSeries = useMemo(
    () => series.filter((item) => !hiddenSeries.has(item.svid)),
    [series, hiddenSeries],
  );

  const events = useMemo<PreviewEventMark[]>(() => data.events ?? [], [data]);
  const total = series[0]?.avg.length ?? 0;

  const bounds = useMemo<BucketRange>(() => {
    if (total <= 1) {
      return { start: 0, end: Math.max(0, total - 1) };
    }
    if (view === null) {
      return { start: 0, end: total - 1 };
    }
    const start = Math.max(0, Math.min(total - 1, Math.round(view.start)));
    const end = Math.max(start + 1, Math.min(total - 1, Math.round(view.end)));
    return { start, end };
  }, [view, total]);
  const count = Math.max(1, bounds.end - bounds.start + 1);
  const viewFromMs = data.from + bounds.start * data.bucketMs;
  /* 刻度文案：相对模式从窗口起点算（右端 = 现在），绝对模式就是时刻本身 */
  const timeLabel = (atMs: number) => formatSimTime(relativeTime ? Math.max(0, atMs - data.from) : atMs);

  /** 事件 → 桶下标；缩放到子区间后窗口外的、图例里被关掉的都丢掉。 */
  const eventMarks = useMemo(() => {
    if (events.length === 0 || total === 0) {
      return [] as { bucket: number; event: PreviewEventMark; colorVar: string }[];
    }
    const colors = eventColorIndexes(events, eventGrouping);
    return events
      .filter((event) => !hiddenEvents.has(eventKey(event, eventGrouping)))
      .filter((event) => event.at >= viewFromMs && event.at <= viewFromMs + data.bucketMs * count)
      .map((event) => ({
        bucket: Math.min(count - 1, Math.max(0, Math.floor((event.at - viewFromMs) / data.bucketMs))),
        event,
        colorVar: eventColorVar(colors.get(eventKey(event, eventGrouping)) ?? 1),
      }));
  }, [events, eventGrouping, hiddenEvents, viewFromMs, data.bucketMs, count, total]);

  /** 图例：同一计划/CEID 只占一条，后面带次数。 */
  const legend = useMemo(() => {
    const groups = new Map<string, { key: string; label: string; color: string; count: number; firstAt: number }>();
    const colors = eventColorIndexes(events, eventGrouping);
    for (const event of events) {
      const key = eventKey(event, eventGrouping);
      const existing = groups.get(key);
      if (existing === undefined) {
        groups.set(key, {
          key,
          label: eventLegendLabel(event, eventGrouping),
          color: `var(${eventColorVar(colors.get(key) ?? 1)})`,
          count: 1,
          firstAt: event.at,
        });
      } else {
        existing.count += 1;
      }
    }
    return [...groups.values()].sort((left, right) => left.firstAt - right.firstAt);
  }, [events, eventGrouping]);

  const zoomBy = (factor: number) => {
    if (total <= 1) {
      return;
    }
    const nextCount = Math.max(2, Math.min(total, Math.round(count * factor)));
    const center = Math.round((bounds.start + bounds.end) / 2);
    let start = Math.round(center - nextCount / 2);
    start = Math.max(0, Math.min(total - nextCount, start));
    setView({ start, end: start + nextCount - 1 });
  };

  const resetView = () => {
    setView(null);
    setDragging(null);
  };

  useEffect(() => {
    const canvas = ref.current;
    if (canvas === null) {
      return;
    }
    const context = canvas.getContext('2d');
    if (context === null) {
      return;
    }

    /* 按显示尺寸 × 设备像素比设置画布，CSS 再缩回显示尺寸（高分屏不糊） */
    const ratio = Math.max(1, Math.min(3, window.devicePixelRatio || 1));
    const displayWidth = Math.max(1, Math.round(canvas.getBoundingClientRect().width || 760));
    const totalHeight = height + PAD_TOP + PAD_BOTTOM;
    const pixelWidth = Math.round(displayWidth * ratio);
    const pixelHeight = Math.round(totalHeight * ratio);
    if (canvas.width !== pixelWidth || canvas.height !== pixelHeight) {
      canvas.width = pixelWidth;
      canvas.height = pixelHeight;
    }
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    context.clearRect(0, 0, displayWidth, totalHeight);

    const plotWidth = Math.max(1, displayWidth - PAD_LEFT - PAD_RIGHT);
    const plotHeight = Math.max(1, height);
    const palette = readPalette(canvas);
    const styles = getComputedStyle(canvas);
    const colorOf = (name: string, fallback: string): string => {
      const value = styles.getPropertyValue(name).trim();
      return value === '' ? fallback : value;
    };

    /* --- 绘图区底色 + 网格 --- */
    context.fillStyle = palette.band;
    context.fillRect(PAD_LEFT, PAD_TOP, plotWidth, plotHeight);

    /* 图例把所有曲线都关掉了：画一句提示，别留一副 0..1 的假坐标轴 */
    if (visibleSeries.length === 0) {
      context.fillStyle = palette.cursor;
      context.font = `11px ${readFontFamily(canvas)}`;
      context.textAlign = 'center';
      context.textBaseline = 'middle';
      context.globalAlpha = 0.8;
      context.fillText(
        '曲线都被图例关掉了：点一下图例里的参数名就能显示回来',
        PAD_LEFT + plotWidth / 2,
        PAD_TOP + plotHeight / 2,
      );
      context.globalAlpha = 1;
      return;
    }

    const x = (index: number) => PAD_LEFT + (count <= 1 ? 0 : (index / (count - 1)) * plotWidth);

    /* --- Y 轴：所有可见参数共用一个值域（同一张图读的是"当时谁高谁低"） --- */
    let low = Number.POSITIVE_INFINITY;
    let high = Number.NEGATIVE_INFINITY;
    for (const item of visibleSeries) {
      for (let index = bounds.start; index <= bounds.end; index += 1) {
        low = Math.min(low, item.min[index]);
        high = Math.max(high, item.max[index]);
      }
    }
    if (!Number.isFinite(low) || !Number.isFinite(high)) {
      low = 0;
      high = 1;
    }
    if (!(high > low)) {
      const pad = Math.max(0.5, Math.abs(high) * 0.1);
      low -= pad;
      high += pad;
    }
    const padY = (high - low) * 0.08;
    low -= padY;
    high += padY;
    const y = (value: number) => PAD_TOP + plotHeight
      - ((value - low) / Math.max(1e-9, high - low)) * plotHeight;

    context.font = `10px ${readFontFamily(canvas)}`;
    /* 刻度位数跟着步长走：步长 1.47 时写 28.0 / 26.5，不要两位小数的噪声 */
    const stepY = (high - low) / 4;
    const axisDigits = stepY >= 10 ? 0 : stepY >= 1 ? 1 : stepY >= 0.1 ? 2 : 3;
    for (let line = 0; line <= 4; line += 1) {
      const ratioY = line / 4;
      const at = PAD_TOP + plotHeight - ratioY * plotHeight;
      context.globalAlpha = 0.6;
      context.strokeStyle = palette.grid;
      context.lineWidth = 1;
      context.beginPath();
      context.moveTo(PAD_LEFT, at);
      context.lineTo(PAD_LEFT + plotWidth, at);
      context.stroke();
      context.globalAlpha = 1;
      context.fillStyle = palette.axis;
      context.textAlign = 'right';
      context.textBaseline = 'middle';
      context.fillText((low + ratioY * (high - low)).toFixed(axisDigits), PAD_LEFT - 6, at);
    }

    /* --- X 轴：时间刻度（模拟时间，从窗口起点算） --- */
    const ticks = Math.max(2, Math.min(8, Math.floor(plotWidth / 96)));
    context.textAlign = 'center';
    context.textBaseline = 'top';
    for (let tick = 0; tick <= ticks; tick += 1) {
      const ratioX = tick / ticks;
      const at = PAD_LEFT + ratioX * plotWidth;
      context.globalAlpha = 0.6;
      context.strokeStyle = palette.grid;
      context.beginPath();
      context.moveTo(at, PAD_TOP);
      context.lineTo(at, PAD_TOP + plotHeight);
      context.stroke();
      context.globalAlpha = 1;
      context.fillStyle = palette.axis;
      context.fillText(timeLabel(viewFromMs + ratioX * data.bucketMs * Math.max(1, count - 1)), at, PAD_TOP + plotHeight + 5);
    }

    /* --- 坐标轴 --- */
    context.strokeStyle = palette.axis;
    context.lineWidth = 1;
    context.beginPath();
    context.moveTo(PAD_LEFT, PAD_TOP);
    context.lineTo(PAD_LEFT, PAD_TOP + plotHeight);
    context.lineTo(PAD_LEFT + plotWidth, PAD_TOP + plotHeight);
    context.stroke();

    /* --- 参数曲线：包络带 + 均值线，一条一个颜色（颜色按原始顺序，隐藏不改色） --- */
    visibleSeries.forEach((item) => {
      const color = colorOf(seriesColorVar(colorIndexOf.get(item.svid) ?? 0), palette.cursor);
      context.beginPath();
      for (let index = 0; index < count; index += 1) {
        const value = item.max[bounds.start + index];
        const px = x(index);
        const py = y(value);
        if (index === 0) {
          context.moveTo(px, py);
        } else {
          context.lineTo(px, py);
        }
      }
      for (let index = count - 1; index >= 0; index -= 1) {
        context.lineTo(x(index), y(item.min[bounds.start + index]));
      }
      context.closePath();
      context.globalAlpha = 0.12;
      context.fillStyle = color;
      context.fill();
      context.globalAlpha = 1;

      context.beginPath();
      for (let index = 0; index < count; index += 1) {
        const px = x(index);
        const py = y(item.avg[bounds.start + index]);
        if (index === 0) {
          context.moveTo(px, py);
        } else {
          context.lineTo(px, py);
        }
      }
      context.strokeStyle = color;
      context.lineWidth = 1.7;
      context.lineJoin = 'round';
      context.stroke();
    });

    /* --- 事件竖线：虚线 + 顶部小三角（颜色按计划/CEID） --- */
    for (const marker of eventMarks) {
      const atX = clamp(x(marker.bucket), PAD_LEFT, PAD_LEFT + plotWidth);
      const color = colorOf(marker.colorVar, palette.event);
      context.strokeStyle = color;
      context.fillStyle = color;
      context.globalAlpha = 0.9;
      context.setLineDash([3, 3]);
      context.lineWidth = 1;
      context.beginPath();
      context.moveTo(atX, PAD_TOP);
      context.lineTo(atX, PAD_TOP + plotHeight);
      context.stroke();
      context.setLineDash([]);
      context.beginPath();
      context.moveTo(atX - 3.5, PAD_TOP);
      context.lineTo(atX + 3.5, PAD_TOP);
      context.lineTo(atX, PAD_TOP + 6);
      context.closePath();
      context.fill();
      context.globalAlpha = 1;
    }

    /* --- 框选区间 --- */
    if (dragging !== null) {
      const from = clamp(x(Math.min(dragging.start, dragging.end)), PAD_LEFT, PAD_LEFT + plotWidth);
      const to = clamp(x(Math.max(dragging.start, dragging.end)), PAD_LEFT, PAD_LEFT + plotWidth);
      context.globalAlpha = 0.14;
      context.fillStyle = colorOf('--accent', '#0f766e');
      context.fillRect(from, PAD_TOP, Math.max(1, to - from), plotHeight);
      context.globalAlpha = 1;
    }

    /* --- 悬停游标 + 每条曲线的当前点 --- */
    if (hover !== null && hover >= 0 && hover < count) {
      const atX = x(hover);
      context.strokeStyle = palette.cursor;
      context.globalAlpha = 0.55;
      context.lineWidth = 1;
      context.beginPath();
      context.moveTo(atX, PAD_TOP);
      context.lineTo(atX, PAD_TOP + plotHeight);
      context.stroke();
      context.globalAlpha = 1;
      visibleSeries.forEach((item) => {
        context.fillStyle = colorOf(seriesColorVar(colorIndexOf.get(item.svid) ?? 0), palette.cursor);
        context.beginPath();
        context.arc(atX, y(item.avg[bounds.start + hover]), 3, 0, Math.PI * 2);
        context.fill();
      });
    }
  }, [visibleSeries, colorIndexOf, bounds, count, viewFromMs, data.bucketMs, data.from, relativeTime, height, hover, dragging, eventMarks, themeVersion]);

  const bucketAt = (event: ReactPointerEvent<HTMLCanvasElement>): number | null => {
    const canvas = ref.current;
    if (canvas === null || count === 0) {
      return null;
    }
    const rect = canvas.getBoundingClientRect();
    if (rect.width === 0) {
      return null;
    }
    const plotWidth = Math.max(1, rect.width - PAD_LEFT - PAD_RIGHT);
    const ratio = (event.clientX - rect.left - PAD_LEFT) / plotWidth;
    return Math.round(clamp(ratio, 0, 1) * (count - 1));
  };

  const hovered = hover === null || hover < 0 || hover >= count ? null : hover;

  return (
    <div className="event-curve">
      <div className="chart-toolbar">
        <button type="button" onClick={() => zoomBy(0.7)} title="放大时间轴">＋</button>
        <button type="button" onClick={() => zoomBy(1.4)} title="缩小时间轴">－</button>
        <button type="button" onClick={resetView} title="恢复整个时间窗">复位</button>
        <span className="hint-inline">拖动框选放大 · 双击复位 · 点图例可隐藏/显示</span>
        <span className="spacer" />
        <span className="hint-inline">横轴：时间　纵轴：参数值　竖虚线：事件发送时刻</span>
      </div>

      <canvas
        ref={ref}
        style={{ height: height + PAD_TOP + PAD_BOTTOM }}
        title="横轴是时间、纵轴是参数值；竖虚线是事件发送时刻"
        onPointerDown={(event) => {
          const index = bucketAt(event);
          if (index === null) {
            return;
          }
          event.currentTarget.setPointerCapture(event.pointerId);
          dragAnchor.current = index;
          setDragging({ start: index, end: index });
          setHover(index);
        }}
        onPointerMove={(event) => {
          const index = bucketAt(event);
          if (index === null) {
            return;
          }
          setHover(index);
          if (dragAnchor.current !== null) {
            setDragging({ start: dragAnchor.current, end: index });
          }
        }}
        onPointerUp={(event) => {
          const anchor = dragAnchor.current;
          const index = bucketAt(event);
          dragAnchor.current = null;
          setDragging(null);
          /* 拖动太短当误触，不缩放 */
          if (anchor !== null && index !== null && Math.abs(index - anchor) >= 3) {
            setView({
              start: bounds.start + Math.min(anchor, index),
              end: bounds.start + Math.max(anchor, index),
            });
          }
        }}
        onPointerCancel={() => {
          dragAnchor.current = null;
          setDragging(null);
        }}
        onPointerLeave={() => {
          if (dragAnchor.current === null) {
            setHover(null);
          }
        }}
        onDoubleClick={resetView}
      />

      <div className="event-curve-foot">
        <div className="event-curve-series">
          {series.map((item) => {
            const off = hiddenSeries.has(item.svid);
            return (
              <button
                type="button"
                className="event-curve-series-item"
                key={item.svid}
                data-off={off ? 'true' : undefined}
                aria-pressed={!off}
                title={off ? '这条曲线已隐藏，点一下显示' : '点一下隐藏这条曲线'}
                onClick={() => {
                  setHiddenSeries((previous) => {
                    const next = new Set(previous);
                    if (next.has(item.svid)) {
                      next.delete(item.svid);
                    } else {
                      next.add(item.svid);
                    }
                    return next;
                  });
                }}
              >
                <span
                  className="event-curve-swatch"
                  style={{ background: `var(${seriesColorVar(colorIndexOf.get(item.svid) ?? 0)})` }}
                  aria-hidden="true"
                />
                <span className="mono">{item.svid}</span>
                <span>{labels?.get(item.svid) ?? item.owner ?? ''}</span>
                {(item.unit ?? '') !== '' && <span className="hint-inline">({item.unit})</span>}
              </button>
            );
          })}
        </div>
        {hovered !== null && (
          <span className="mono event-curve-readout">
            {timeLabel(viewFromMs + hovered * data.bucketMs)}
            {visibleSeries.map((item) => (
              <span key={item.svid}>　{labels?.get(item.svid) ?? item.svid} {formatSimValue(item.avg[bounds.start + hovered])}</span>
            ))}
          </span>
        )}
      </div>

      {legend.length > 0 && (
        <div className="event-legend">
          <span className="event-legend-title">
            {eventLabel}（{events.length}）
          </span>
          {legend.slice(0, 12).map((group) => {
            const off = hiddenEvents.has(group.key);
            return (
              <button
                type="button"
                className="event-legend-item"
                key={group.key}
                data-off={off ? 'true' : undefined}
                aria-pressed={!off}
                title={off ? '这类事件已隐藏，点一下显示竖线' : '点一下隐藏这类事件的竖线'}
                onClick={() => {
                  setHiddenEvents((previous) => {
                    const next = new Set(previous);
                    if (next.has(group.key)) {
                      next.delete(group.key);
                    } else {
                      next.add(group.key);
                    }
                    return next;
                  });
                }}
              >
                <span className="event-swatch" style={{ background: group.color }} aria-hidden="true" />
                <span>{group.label}</span>
                {group.count > 1 && <span className="hint-inline">×{group.count}</span>}
              </button>
            );
          })}
          {legend.length > 12 && <span className="hint-inline">…还有 {legend.length - 12} 类</span>}
        </div>
      )}
    </div>
  );
}

function clamp(value: number, low: number, high: number): number {
  return Math.min(high, Math.max(low, value));
}
