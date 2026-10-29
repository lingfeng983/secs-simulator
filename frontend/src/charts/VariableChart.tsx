import { useEffect, useMemo, useRef, useState } from 'react';
import type { PointerEvent as ReactPointerEvent } from 'react';
import type { PreviewEventMark, PreviewSeries } from '../api';
import { formatValue } from '../lib/format';
import { eventColorIndexes, eventColorVar, eventKey } from '../lib/eventColors';
import { useThemeVersion } from '../state/useThemeVersion';

/**
 * 单变量曲线图（带坐标轴）。
 *
 * <p>与 {@link import('./Sparkline').Sparkline} 的区别：这里只画一个变量，
 * 但把坐标轴画全了——左边 Y 轴是数值、右边 X 轴是时间。
 * 现场看图最常问的两句是"这个点是多少"和"这是什么时候"，
 * 没有刻度就只能靠猜。</p>
 *
 * <p>曲线本身是 min/max 包络 + avg 线：包络是每个桶里采样点的上下界，
 * avg 是桶内平均值。抖动大的时候包络会鼓起来，一眼能看出"这个点不稳"。</p>
 */

export interface VariableChartProps {
  /** 要画的变量。 */
  series: PreviewSeries;
  /** 窗口起点（引擎时钟，毫秒）。 */
  fromMs: number;
  /**
   * 引擎时钟到墙钟的偏移（毫秒）。
   *
   * <p>后端预览用的是进程 uptime（从启动起算），直接 `new Date()` 会显示成 1970 年。
   * 设备页传 `Date.now() - health.uptimeMs`；主机采集页传的是 epoch，保持 0 即可。</p>
   */
  timeOriginMs?: number;
  /** 每桶宽度（毫秒）。 */
  bucketMs: number;
  /** 窗口内的事件标记：在对应时刻画竖线并标注。 */
  events?: PreviewEventMark[];
  /** 画布高度（px，不含坐标轴留白）。 */
  height?: number;
  /** 事件竖线的标题前缀。 */
  eventLabel?: string;
  /** 缩放回调。 */
  onZoom?: (zoom: { start: number; end: number } | null) => void;
}

interface Palette {
  accent: string;
  grid: string;
  axis: string;
  cursor: string;
  event: string;
  band: string;
}

function readPalette(canvas: HTMLCanvasElement): Palette {
  const styles = getComputedStyle(canvas);
  const read = (name: string, fallback: string): string => {
    const value = styles.getPropertyValue(name).trim();
    return value === '' ? fallback : value;
  };
  return {
    accent: read('--accent', '#0f766e'),
    grid: read('--line', '#dde4ed'),
    axis: read('--line-strong', '#b9c4d2'),
    cursor: read('--muted', '#5f6f85'),
    event: read('--warn', '#b45309'),
    band: read('--accent-soft', 'rgba(15,118,110,0.08)'),
  };
}

/** canvas 的 fillText 只认具体字体名；取不到 CSS 变量时的兜底字体栈。 */
const FALLBACK_MONO_FONT = '"JetBrains Mono", "Cascadia Mono", "SF Mono", Consolas, monospace';

/**
 * 画布刻度用的字体。
 *
 * <p>不能直接写 '10px var(--font-mono)'：canvas 不解析 CSS 变量，浏览器会
 * 静默丢掉整条 font 声明、退回默认字体。所以这里读计算后的字体栈。</p>
 */
function readFontFamily(canvas: HTMLCanvasElement): string {
  const styles = getComputedStyle(canvas);
  const mono = styles.getPropertyValue('--font-mono').trim();
  if (mono !== '') {
    return mono;
  }
  return styles.fontFamily.trim() || FALLBACK_MONO_FONT;
}

/** 坐标轴留白：左边放 Y 轴刻度，底下放 X 轴刻度。 */
const PAD_LEFT = 52;
const PAD_RIGHT = 12;
const PAD_TOP = 10;
const PAD_BOTTOM = 22;

export function VariableChart({
  series,
  fromMs,
  timeOriginMs = 0,
  bucketMs,
  events = [],
  height = 180,
  eventLabel = '事件',
  onZoom,
}: VariableChartProps): JSX.Element {
  const ref = useRef<HTMLCanvasElement | null>(null);
  const themeVersion = useThemeVersion();
  const dragAnchor = useRef<number | null>(null);
  const [hover, setHover] = useState<number | null>(null);
  /* 视图缩放：view 是可见桶区间，yZoom 是纵向放大倍数 */
  const [view, setView] = useState<{ start: number; end: number } | null>(null);
  const [yZoom, setYZoom] = useState(1);

  const total = series.avg.length;
  const bounds = useMemo(() => {
    if (total <= 0) {
      return { start: 0, end: 0 };
    }
    if (view === null) {
      return { start: 0, end: total - 1 };
    }
    const start = Math.max(0, Math.min(total - 1, Math.round(view.start)));
    const end = Math.max(start + 1, Math.min(total - 1, Math.round(view.end)));
    return { start, end };
  }, [view, total]);
  const count = Math.max(1, bounds.end - bounds.start + 1);
  /* 只把可见区间交给绘图/读数，缩放不用重新请求后端 */
  const visSeries = useMemo<PreviewSeries>(() => ({
    ...series,
    min: series.min.slice(bounds.start, bounds.end + 1),
    max: series.max.slice(bounds.start, bounds.end + 1),
    avg: series.avg.slice(bounds.start, bounds.end + 1),
  }), [series, bounds]);
  const viewFromMs = fromMs + bounds.start * bucketMs;

  /** 放大/缩小/复位/纵向缩放都收在这里，按钮与滚轮共用。 */
  const zoomBy = (factor: number, anchorBucket: number | null = null) => {
    if (total <= 1) {
      return;
    }
    const center = anchorBucket ?? Math.round((bounds.start + bounds.end) / 2);
    const nextCount = Math.max(2, Math.min(total, Math.round(count * factor)));
    let start = Math.round(center - nextCount / 2);
    start = Math.max(0, Math.min(total - nextCount, start));
    setView({ start, end: start + nextCount - 1 });
  };

  const resetView = () => {
    setView(null);
    setYZoom(1);
    onZoom?.(null);
  };

  useEffect(() => {
    const canvas = ref.current;
    if (canvas === null) {
      return;
    }
    const onWheel = (event: WheelEvent) => {
      event.preventDefault();
      if (total <= 1) {
        return;
      }
      if (event.shiftKey) {
        /* Shift + 滚轮：平移时间轴（不放缩） */
        const step = Math.max(1, Math.round(count * 0.2)) * (event.deltaY < 0 ? -1 : 1);
        const start = Math.max(0, Math.min(total - count, bounds.start + step));
        setView({ start, end: start + count - 1 });
        return;
      }
      if (event.ctrlKey) {
        /* Ctrl + 滚轮：纵向放大/缩小（以当前值域中心为锚点） */
        setYZoom((previous) => {
          const next = previous * (event.deltaY < 0 ? 1.2 : 1 / 1.2);
          return Math.max(0.2, Math.min(40, next));
        });
        return;
      }
      const rect = canvas.getBoundingClientRect();
      const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left - PAD_LEFT) / Math.max(1, rect.width - PAD_LEFT - PAD_RIGHT)));
      const anchor = bounds.start + Math.round(ratio * (count - 1));
      zoomBy(event.deltaY < 0 ? 0.8 : 1.25, anchor);
    };
    canvas.addEventListener('wheel', onWheel, { passive: false });
    return () => canvas.removeEventListener('wheel', onWheel);
    // zoomBy 依赖当前 bounds/count，重新绑定才能拿到最新值
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bounds, count, total]);

  /** 事件 → 桶下标；窗口外的丢掉。 */
  const eventColors = useMemo(() => eventColorIndexes(events), [events]);
  const eventMarks = useMemo(() => {
    if (events.length === 0 || count === 0) {
      return [] as { bucket: number; ceid: number; planId?: string; at: number; colorVar: string }[];
    }
    return events
      .filter((event) => event.at >= viewFromMs && event.at <= viewFromMs + bucketMs * count)
      .map((event) => ({
        bucket: Math.min(count - 1, Math.max(0, Math.floor((event.at - viewFromMs) / bucketMs))),
        ceid: event.ceid,
        planId: event.planId,
        at: event.at,
        colorVar: eventColorVar(eventColors.get(eventKey(event)) ?? 1),
      }));
  }, [events, eventColors, viewFromMs, bucketMs, count]);

  /** Y 轴范围：按可见 min/max 包络取，再叠加纵向缩放。 */
  const range = useMemo(() => {
    if (count === 0) {
      return { low: 0, high: 1 };
    }
    let low = Number.POSITIVE_INFINITY;
    let high = Number.NEGATIVE_INFINITY;
    for (let index = 0; index < count; index += 1) {
      low = Math.min(low, visSeries.min[index]);
      high = Math.max(high, visSeries.max[index]);
    }
    if (!Number.isFinite(low) || !Number.isFinite(high)) {
      return { low: 0, high: 1 };
    }
    if (!(high > low)) {
      /* 全平的曲线：给一个对称的小区间，免得除零 */
      const pad = Math.max(1, Math.abs(high) * 0.1);
      low = high - pad;
      high = high + pad;
    }
    const pad = (high - low) * 0.08;
    low -= pad;
    high += pad;
    const mid = (low + high) / 2;
    const half = (high - low) / 2 / yZoom;
    return { low: mid - half, high: mid + half };
  }, [visSeries, count, yZoom]);

  useEffect(() => {
    const canvas = ref.current;
    if (canvas === null) {
      return;
    }
    const context = canvas.getContext('2d');
    if (context === null) {
      return;
    }

    const rect = canvas.getBoundingClientRect();
    const ratio = Math.max(1, Math.min(3, window.devicePixelRatio || 1));
    const width = Math.max(1, Math.round(rect.width || 720));
    const totalHeight = height + PAD_TOP + PAD_BOTTOM;
    const pixelWidth = Math.round(width * ratio);
    const pixelHeight = Math.round(totalHeight * ratio);
    if (canvas.width !== pixelWidth || canvas.height !== pixelHeight) {
      canvas.width = pixelWidth;
      canvas.height = pixelHeight;
    }
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    context.clearRect(0, 0, width, totalHeight);

    const plotWidth = Math.max(1, width - PAD_LEFT - PAD_RIGHT);
    const plotHeight = Math.max(1, height);
    const x = (index: number) => PAD_LEFT + (count <= 1 ? 0 : (index / (count - 1)) * plotWidth);
    const y = (value: number) => PAD_TOP + plotHeight
      - ((value - range.low) / Math.max(1e-9, range.high - range.low)) * plotHeight;

    const palette = readPalette(canvas);

    /* --- 绘图区底色 --- */
    context.fillStyle = palette.band;
    context.fillRect(PAD_LEFT, PAD_TOP, plotWidth, plotHeight);

    /* --- 横向网格线 + 左侧 Y 轴刻度 --- */
    context.font = `10px ${readFontFamily(canvas)}`;
    context.textAlign = 'right';
    context.textBaseline = 'middle';
    for (let line = 0; line <= 4; line += 1) {
      const ratioY = line / 4;
      const at = PAD_TOP + plotHeight - ratioY * plotHeight;
      const value = range.low + ratioY * (range.high - range.low);
      context.globalAlpha = 0.6;
      context.strokeStyle = palette.grid;
      context.lineWidth = 1;
      context.beginPath();
      context.moveTo(PAD_LEFT, at);
      context.lineTo(PAD_LEFT + plotWidth, at);
      context.stroke();
      context.globalAlpha = 1;
      context.fillStyle = palette.axis;
      context.fillText(formatValue(value), PAD_LEFT - 6, at);
    }

    /* --- 底部 X 轴刻度（时间） --- */
    context.textAlign = 'center';
    context.textBaseline = 'top';
    const ticks = Math.min(6, Math.max(2, Math.floor(plotWidth / 90)));
    for (let tick = 0; tick <= ticks; tick += 1) {
      const ratioX = tick / ticks;
      const at = PAD_LEFT + ratioX * plotWidth;
      const atMs = viewFromMs + ratioX * bucketMs * Math.max(0, count - 1);
      context.globalAlpha = 0.6;
      context.strokeStyle = palette.grid;
      context.beginPath();
      context.moveTo(at, PAD_TOP);
      context.lineTo(at, PAD_TOP + plotHeight);
      context.stroke();
      context.globalAlpha = 1;
      context.fillStyle = palette.axis;
      context.fillText(formatClock(atMs + timeOriginMs), at, PAD_TOP + plotHeight + 5);
    }

    /* --- 坐标轴线 --- */
    context.strokeStyle = palette.axis;
    context.lineWidth = 1;
    context.beginPath();
    context.moveTo(PAD_LEFT, PAD_TOP);
    context.lineTo(PAD_LEFT, PAD_TOP + plotHeight);
    context.lineTo(PAD_LEFT + plotWidth, PAD_TOP + plotHeight);
    context.stroke();

    if (count === 0) {
      return;
    }

    /* --- min/max 包络 --- */
    context.beginPath();
    for (let index = 0; index < count; index += 1) {
      const px = x(index);
      const py = y(visSeries.max[index]);
      if (index === 0) {
        context.moveTo(px, py);
      } else {
        context.lineTo(px, py);
      }
    }
    for (let index = count - 1; index >= 0; index -= 1) {
      context.lineTo(x(index), y(visSeries.min[index]));
    }
    context.closePath();
    context.globalAlpha = 0.18;
    context.fillStyle = palette.accent;
    context.fill();
    context.globalAlpha = 1;

    /* --- avg 线 --- */
    context.beginPath();
    for (let index = 0; index < count; index += 1) {
      const px = x(index);
      const py = y(visSeries.avg[index]);
      if (index === 0) {
        context.moveTo(px, py);
      } else {
        context.lineTo(px, py);
      }
    }
    context.strokeStyle = palette.accent;
    context.lineWidth = 1.6;
    context.lineJoin = 'round';
    context.stroke();

    /* --- 事件竖线 + 顶部标注 ---
       事件密集时标签会叠成一团：只有"离上一个标签够远"的才写文字，
       其余只留竖线与小三角——竖线的位置本身已经是信息。 */
    context.font = `10px ${readFontFamily(canvas)}`;
    /* 标签分两行错开放：同一行相邻标签至少隔 LABEL_GAP 像素，
       放不下的就不写文字（竖线本身已经是信息，完整清单在图下方）。 */
    const LABEL_GAP = 96;
    const labelRows = [PAD_TOP + 7, PAD_TOP + 19];
    const lastLabelX = [-Infinity, -Infinity];
    const styleCache = getComputedStyle(canvas);
    const colorOf = (name: string): string => {
      const value = styleCache.getPropertyValue(name).trim();
      return value === '' ? palette.event : value;
    };
    for (const marker of eventMarks) {
      const atX = x(marker.bucket);
      const color = colorOf(marker.colorVar);
      context.strokeStyle = color;
      context.globalAlpha = 0.9;
      context.setLineDash([3, 3]);
      context.lineWidth = 1;
      context.beginPath();
      context.moveTo(atX, PAD_TOP);
      context.lineTo(atX, PAD_TOP + plotHeight);
      context.stroke();
      context.setLineDash([]);

      /* 顶部小三角：一眼看出"事件在这里" */
      context.fillStyle = color;
      context.beginPath();
      context.moveTo(atX - 3.5, PAD_TOP);
      context.lineTo(atX + 3.5, PAD_TOP);
      context.lineTo(atX, PAD_TOP + 6);
      context.closePath();
      context.fill();

      /* 两行里挑一行放得下的 */
      const label = `${eventLabel} ${marker.planId !== undefined && marker.planId !== '' ? marker.planId : `CEID ${marker.ceid}`}`;
      const textWidth = context.measureText(label).width;
      let row = -1;
      for (let candidate = 0; candidate < labelRows.length; candidate += 1) {
        if (atX - lastLabelX[candidate] >= LABEL_GAP) {
          row = candidate;
          break;
        }
      }
      if (row === -1) {
        continue;
      }
      lastLabelX[row] = atX;

      const flip = atX + textWidth + 6 > PAD_LEFT + plotWidth;
      const textX = flip ? atX - textWidth - 4 : atX + 4;
      context.textAlign = 'left';
      context.textBaseline = 'top';
      context.globalAlpha = 0.95;
      context.fillStyle = color;
      context.fillText(label, textX, labelRows[row]);
      context.globalAlpha = 1;
    }

    /* --- 悬停读数 --- */
    if (hover !== null && hover >= 0 && hover < count) {
      const atX = x(hover);
      context.strokeStyle = palette.cursor;
      context.globalAlpha = 0.6;
      context.lineWidth = 1;
      context.beginPath();
      context.moveTo(atX, PAD_TOP);
      context.lineTo(atX, PAD_TOP + plotHeight);
      context.stroke();
      context.globalAlpha = 1;

      context.fillStyle = palette.accent;
      context.beginPath();
      context.arc(atX, y(visSeries.avg[hover]), 3, 0, Math.PI * 2);
      context.fill();
    }
  }, [visSeries, count, viewFromMs, timeOriginMs, bucketMs, height, hover, eventMarks, range, themeVersion]);

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
    return Math.round(Math.min(1, Math.max(0, ratio)) * (count - 1));
  };

  const hovered = hover === null || hover < 0 || hover >= count ? null : hover;

  return (
    <div className="variable-chart">
      <div className="chart-toolbar">
        <button type="button" onClick={() => zoomBy(0.7)} title="放大时间轴">＋</button>
        <button type="button" onClick={() => zoomBy(1.4)} title="缩小时间轴">－</button>
        <button type="button" onClick={resetView} title="恢复全窗口与默认纵向比例">复位</button>
        <span className="hint-inline">
          拖动框选放大 · 滚轮缩放时间 · Shift+滚轮平移 · Ctrl+滚轮纵向缩放 · 双击复位
        </span>
      </div>
      <canvas
        ref={ref}
        style={{ height: height + PAD_TOP + PAD_BOTTOM }}
        title="拖动框选放大；滚轮缩放时间；Shift+滚轮平移；Ctrl+滚轮纵向缩放；双击复位"
        onPointerDown={(event) => {
          const index = bucketAt(event);
          if (index === null) {
            return;
          }
          event.currentTarget.setPointerCapture(event.pointerId);
          dragAnchor.current = index;
          setHover(index);
        }}
        onPointerMove={(event) => {
          const index = bucketAt(event);
          if (index !== null) {
            setHover(index);
          }
        }}
        onPointerUp={(event) => {
          const anchor = dragAnchor.current;
          const index = bucketAt(event);
          dragAnchor.current = null;
          if (anchor !== null && index !== null && Math.abs(index - anchor) >= 3) {
            const start = bounds.start + Math.min(anchor, index);
            const end = bounds.start + Math.max(anchor, index);
            setView({ start, end });
            onZoom?.({ start, end });
          }
        }}
        onPointerLeave={() => {
          if (dragAnchor.current === null) {
            setHover(null);
          }
        }}
        onDoubleClick={resetView}
      />
      <div className="variable-chart-foot">
        <span className="hint-inline">
          Y 轴：数值（阴影带 = 桶内 min/max 包络，实线 = 桶内均值 avg）
        </span>
        <span className="hint-inline">X 轴：时间</span>
        {hovered !== null && (
          <span className="mono variable-chart-readout">
            {formatClock(viewFromMs + hovered * bucketMs + timeOriginMs)}　min {formatValue(visSeries.min[hovered])}　max {formatValue(visSeries.max[hovered])}　avg {formatValue(visSeries.avg[hovered])}
          </span>
        )}
      </div>
      {eventMarks.length > 0 && (
        <details className="variable-chart-events">
          <summary>事件清单（当前视图 {eventMarks.length} 个）</summary>
          <ol>
            {eventMarks.map((event, index) => (
              <li key={`${event.at}-${index}`}>
                <span className="mono">{formatClock(event.at + timeOriginMs)}</span>
                <span>CEID {event.ceid}</span>
                {event.planId !== undefined && event.planId !== '' && (
                  <span className="hint-inline">{event.planId}</span>
                )}
              </li>
            ))}
          </ol>
        </details>
      )}
    </div>
  );
}

/** 时间刻度：HH:MM:SS。 */
function formatClock(atMs: number): string {
  const date = new Date(atMs);
  const hh = String(date.getHours()).padStart(2, '0');
  const mm = String(date.getMinutes()).padStart(2, '0');
  const ss = String(date.getSeconds()).padStart(2, '0');
  return `${hh}:${mm}:${ss}`;
}
