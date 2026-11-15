/**
 * 应用标记（GUI 图标）。
 *
 * <p>自绘标题栏最左侧的图标区：圆角方块底 + 五根高低不一的信号柱，
 * 一眼能认出是「设备通信模拟器」。用 SVG 而不是位图，
 * 深浅主题、任意缩放都清晰，也不额外增加打包资源。</p>
 */
export interface AppMarkProps {
  /** 像素边长，默认 22。 */
  size?: number;
  className?: string;
}

export function AppMark({ size = 22, className }: AppMarkProps): JSX.Element {
  return (
    <svg
      className={className === undefined ? 'app-mark' : `app-mark ${className}`}
      width={size}
      height={size}
      viewBox="0 0 32 32"
      role="img"
      aria-label="SECS Simulator"
      focusable="false"
    >
      <defs>
        <linearGradient id="app-mark-fill" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stopColor="var(--accent-bright)" />
          <stop offset="100%" stopColor="var(--accent)" />
        </linearGradient>
      </defs>
      <rect x="1" y="1" width="30" height="30" rx="8.5" fill="url(#app-mark-fill)" />
      <g stroke="#fff" strokeWidth="2.4" strokeLinecap="round" opacity="0.95">
        <path d="M8 20.5v-4.5" />
        <path d="M12 22.5v-9" />
        <path d="M16 20v-6.5" />
        <path d="M20 22v-11" />
        <path d="M24 19v-3.5" />
      </g>
    </svg>
  );
}
