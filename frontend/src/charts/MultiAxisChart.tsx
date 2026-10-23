import { useEffect, useRef } from 'react';
import uPlot from 'uplot';
import type { PreviewSeries } from '../api';

/** 多轴配色；与曲线预览里的主色一致。 */
export const MULTI_AXIS_COLORS = [
  '#4493f8',
  '#f0883e',
  '#3fb950',
  '#db61a2',
  '#a371f7',
  '#d29922',
  '#39c5cf',
  '#ff7b72',
];

export interface MultiAxisChartProps {
  series: PreviewSeries[];
  fromMs: number;
  bucketMs: number;
}

/**
 * 多轴：同一张图里按单位分配 y 轴，左右交替排列；只画 avg。
 *
 * <p>单位相同的 SVID 共用一条 y 轴，这样"温度 80℃"和"压力 0.1 Torr"
 * 不会因为量纲差太多而把彼此压成一条直线。</p>
 */
export function MultiAxisChart({ series, fromMs, bucketMs }: MultiAxisChartProps): JSX.Element {
  const hostRef = useRef<HTMLDivElement | null>(null);
  const plotRef = useRef<uPlot | null>(null);

  useEffect(() => {
    const host = hostRef.current;
    if (host === null || series.length === 0) {
      return;
    }

    const unitOf = (item: PreviewSeries) => (item.unit ?? '').trim() || '无单位';
    const units = [...new Set(series.map(unitOf))];
    const unitIndex = new Map(units.map((unit, index) => [unit, index]));
    const x = series[0].avg.map((_, index) => (fromMs + index * bucketMs) / 1000);
    const data = [x, ...series.map((item) => item.avg)] as uPlot.AlignedData;

    const scales: uPlot.Scales = { x: { time: false } };
    for (let i = 0; i < units.length; i += 1) {
      scales[`y${i}`] = {};
    }

    const axes: uPlot.Axis[] = [
      { stroke: '#8b949e', grid: { stroke: 'rgba(128, 128, 128, 0.18)' } },
      ...units.map((unit, index) => ({
        scale: `y${index}`,
        side: index % 2 === 0 ? (3 as const) : (1 as const),
        label: unit,
        stroke: MULTI_AXIS_COLORS[index % MULTI_AXIS_COLORS.length],
        grid: { show: index === 0, stroke: 'rgba(128, 128, 128, 0.14)' },
      })),
    ];

    const seriesOptions: uPlot.Series[] = [
      { label: 't(s)' },
      ...series.map((item, index) => ({
        label: `${item.svid} ${unitOf(item)}`,
        stroke: MULTI_AXIS_COLORS[index % MULTI_AXIS_COLORS.length],
        width: 1.5,
        scale: `y${unitIndex.get(unitOf(item)) ?? 0}`,
        points: { show: false },
      })),
    ];

    const plot = new uPlot(
      {
        width: Math.max(320, host.clientWidth || 900),
        height: 300,
        padding: [14, 14, 4, 4],
        cursor: { drag: { x: true, y: false, setScale: true }, points: { show: false } },
        legend: { show: true, live: true },
        scales,
        series: seriesOptions,
        axes,
      },
      data,
      host,
    );
    plotRef.current = plot;

    const resize = () => {
      const width = host.clientWidth;
      if (width > 0 && plotRef.current !== null) {
        plotRef.current.setSize({ width, height: 300 });
      }
    };
    resize();

    let observer: ResizeObserver | null = null;
    if (typeof ResizeObserver !== 'undefined') {
      observer = new ResizeObserver(resize);
      observer.observe(host);
    }

    return () => {
      observer?.disconnect();
      plot.destroy();
      plotRef.current = null;
    };
  }, [series, fromMs, bucketMs]);

  return (
    <div className="multi-axis-block">
      <div className="uplot-title">多轴（按单位分配 y 轴，avg 曲线）</div>
      <div className="multi-axis-host" ref={hostRef} />
    </div>
  );
}
