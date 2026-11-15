import { useMemo, useRef, useState } from 'react';
import type { PointerEvent as ReactPointerEvent } from 'react';
import { formatSimTime, formatSimValue, type SimSample } from '../editors/paramTemplateSimulation';

/**
 * 参数模板预览用的「参数随时间变化」曲线。
 *
 * <p>用 SVG 而非 canvas：弹窗里没有高频刷新，SVG 的坐标轴文字能直接用
 * CSS 变量跟随主题，缩放时也不会糊。横轴是模拟时长、纵轴是最终输出值；
 * 悬停给读数，播放游标由父组件按时间推进。</p>
 */

const VIEW_W = 760;
const VIEW_H = 280;
const PAD = { left: 64, right: 18, top: 18, bottom: 30 };
const PLOT_W = VIEW_W - PAD.left - PAD.right;
const PLOT_H = VIEW_H - PAD.top - PAD.bottom;

export interface ParamCurveChartProps {
  samples: SimSample[];
  durationMs: number;
  /** 播放游标时刻；null = 不显示。 */
  cursorMs: number | null;
  /** 悬停时把当前采样点交给父组件展示读数；离开时传 null。 */
  onReadout?: (sample: SimSample | null) => void;
}

/** 二分找最接近 tMs 的采样点；播放时每帧调用，别用线性扫描。 */
export function nearestSample(samples: SimSample[], tMs: number): SimSample | null {
  if (samples.length === 0) {
    return null;
  }
  let lo = 0;
  let hi = samples.length - 1;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (samples[mid].tMs < tMs) {
      lo = mid + 1;
    } else {
      hi = mid;
    }
  }
  const next = samples[lo];
  const prev = samples[Math.max(0, lo - 1)];
  return Math.abs(prev.tMs - tMs) < Math.abs(next.tMs - tMs) ? prev : next;
}

export function ParamCurveChart({
  samples,
  durationMs,
  cursorMs,
  onReadout,
}: ParamCurveChartProps): JSX.Element {
  const svgRef = useRef<SVGSVGElement | null>(null);
  const [hover, setHover] = useState<SimSample | null>(null);

  const model = useMemo(() => {
    const values = samples.map((sample) => sample.value);
    let min = values.length === 0 ? 0 : Math.min(...values);
    let max = values.length === 0 ? 1 : Math.max(...values);
    if (!Number.isFinite(min) || !Number.isFinite(max)) {
      min = 0;
      max = 1;
    }
    if (max - min < 1e-9) {
      const pad = Math.max(1, Math.abs(max) * 0.08);
      min -= pad;
      max += pad;
    } else {
      const pad = (max - min) * 0.1;
      min -= pad;
      max += pad;
    }
    const x = (tMs: number) => PAD.left + (tMs / durationMs) * PLOT_W;
    const y = (value: number) => PAD.top + (1 - (value - min) / (max - min)) * PLOT_H;
    const line = samples
      .map((sample, index) => `${index === 0 ? 'M' : 'L'}${x(sample.tMs).toFixed(2)},${y(sample.value).toFixed(2)}`)
      .join(' ');
    const baseline = PAD.top + PLOT_H;
    const area = samples.length === 0
      ? ''
      : `${line} L${x(samples[samples.length - 1].tMs).toFixed(2)},${baseline} L${x(samples[0].tMs).toFixed(2)},${baseline} Z`;
    const yTicks = Array.from({ length: 5 }, (_, index) => max - ((max - min) * index) / 4);
    const xTicks = Array.from({ length: 6 }, (_, index) => (durationMs * index) / 5);
    return { x, y, line, area, baseline, yTicks, xTicks };
  }, [samples, durationMs]);

  const active = hover ?? (cursorMs === null ? null : nearestSample(samples, cursorMs));
  const cursorSample = cursorMs === null ? null : nearestSample(samples, cursorMs);

  const onMove = (event: ReactPointerEvent<SVGSVGElement>) => {
    const svg = svgRef.current;
    if (svg === null || samples.length === 0) {
      return;
    }
    const rect = svg.getBoundingClientRect();
    if (rect.width === 0) {
      return;
    }
    /* width:100% + height:auto，rect 与 viewBox 同比例，线性换算即可。 */
    const viewX = ((event.clientX - rect.left) / rect.width) * VIEW_W;
    const tMs = Math.max(0, Math.min(durationMs,
      ((viewX - PAD.left) / PLOT_W) * durationMs));
    const sample = nearestSample(samples, tMs);
    setHover(sample);
    onReadout?.(sample);
  };

  const onLeave = () => {
    setHover(null);
    onReadout?.(null);
  };

  return (
    <svg
      ref={svgRef}
      className="param-curve-chart"
      viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
      role="img"
      aria-label="参数随时间变化的模拟曲线"
      onPointerMove={onMove}
      onPointerLeave={onLeave}
    >
      <defs>
        <linearGradient id="param-curve-fill" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" style={{ stopColor: 'var(--accent)', stopOpacity: 0.22 }} />
          <stop offset="100%" style={{ stopColor: 'var(--accent)', stopOpacity: 0.01 }} />
        </linearGradient>
      </defs>

      <rect
        className="param-curve-plot-bg"
        x={PAD.left}
        y={PAD.top}
        width={PLOT_W}
        height={PLOT_H}
        rx="4"
      />

      {model.yTicks.map((value, index) => (
        <g key={`y${index}`}>
          <line className="param-curve-grid" x1={PAD.left} x2={PAD.left + PLOT_W}
            y1={model.y(value)} y2={model.y(value)} />
          <text className="param-curve-tick" x={PAD.left - 8} y={model.y(value) + 4} textAnchor="end">
            {formatSimValue(value)}
          </text>
        </g>
      ))}
      {model.xTicks.map((tMs, index) => (
        <g key={`x${index}`}>
          <line className="param-curve-grid param-curve-grid-v" x1={model.x(tMs)} x2={model.x(tMs)}
            y1={PAD.top} y2={PAD.top + PLOT_H} />
          <text className="param-curve-tick" x={model.x(tMs)} y={VIEW_H - 8} textAnchor="middle">
            {formatSimTime(tMs)}
          </text>
        </g>
      ))}
      <line className="param-curve-axis" x1={PAD.left} x2={PAD.left + PLOT_W}
        y1={model.baseline} y2={model.baseline} />
      {/* 不画「时间」轴名：右下角会跟最后一个刻度叠在一起，
          而弹窗说明里已经写明横轴时间、纵轴输出值。 */}

      {model.area !== '' && <path className="param-curve-area" d={model.area} />}
      {model.line !== '' && <path className="param-curve-line" d={model.line} />}

      {cursorSample !== null && (
        <g className="param-curve-cursor-group">
          <line className="param-curve-cursor" x1={model.x(cursorSample.tMs)} x2={model.x(cursorSample.tMs)}
            y1={PAD.top} y2={model.baseline} />
          <circle className="param-curve-cursor-dot" cx={model.x(cursorSample.tMs)}
            cy={model.y(cursorSample.value)} r="5" />
        </g>
      )}

      {active !== null && (
        <g className="param-curve-hover-group">
          <line className="param-curve-hover-line" x1={model.x(active.tMs)} x2={model.x(active.tMs)}
            y1={PAD.top} y2={model.baseline} />
          <circle className="param-curve-hover-dot" cx={model.x(active.tMs)}
            cy={model.y(active.value)} r="4" />
          <g transform={`translate(${Math.max(PAD.left + 4, Math.min(PAD.left + PLOT_W - 132, model.x(active.tMs) + 10))},${Math.max(PAD.top + 4, Math.min(PAD.top + PLOT_H - 48, model.y(active.value) - 48))})`}>
            <rect className="param-curve-tip-bg" width="126" height="40" rx="5" />
            <text className="param-curve-tip-time" x="8" y="16">{formatSimTime(active.tMs)}</text>
            <text className="param-curve-tip-value" x="8" y="32">{formatSimValue(active.value)}</text>
          </g>
        </g>
      )}

      {samples.length === 0 && (
        <text className="param-curve-empty" x={VIEW_W / 2} y={VIEW_H / 2} textAnchor="middle">
          模板体没有可求值的曲线
        </text>
      )}
    </svg>
  );
}
