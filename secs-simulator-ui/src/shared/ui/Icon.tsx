import type { CSSProperties } from 'react';

/**
 * 内联 SVG 图标库。
 *
 * <p>全部手绘 24×24 线性图标（Lucide 风格：圆角线帽、1.8px 描边），
 * 不引入任何外部依赖，离线打包也能用。
 * 颜色继承 currentColor，尺寸统一走 size 属性。</p>
 */

export type IconName =
  | 'overview'
  | 'sessions'
  | 'messages'
  | 'hostsim'
  | 'audit'
  | 'config'
  | 'search'
  | 'sun'
  | 'moon'
  | 'refresh'
  | 'collapse'
  | 'expand'
  | 'help'
  | 'link'
  | 'density';

const ICONS: Record<IconName, JSX.Element> = {
  /* 总览：仪表盘四格 */
  overview: (
    <>
      <rect x="3" y="3" width="7.2" height="9" rx="1.6" />
      <rect x="13.8" y="3" width="7.2" height="5" rx="1.6" />
      <rect x="13.8" y="12" width="7.2" height="9" rx="1.6" />
      <rect x="3" y="16" width="7.2" height="5" rx="1.6" />
    </>
  ),
  /* 会话：插头（HSMS 连接） */
  sessions: (
    <>
      <path d="M9 7V3.5M15 7V3.5" />
      <path d="M7 7h10v3.5a5 5 0 0 1-10 0Z" />
      <path d="M12 15.5V20" />
    </>
  ),
  /* 报文：双向箭头 */
  messages: (
    <>
      <path d="M17 4l3 3-3 3" />
      <path d="M20 7H8" />
      <path d="M7 20l-3-3 3-3" />
      <path d="M4 17h16" />
    </>
  ),
  /* 主机模拟：监视器 */
  hostsim: (
    <>
      <rect x="3" y="4" width="18" height="12" rx="2" />
      <path d="M9 20h6" />
      <path d="M12 16v4" />
    </>
  ),
  /* 审计：带勾清单 */
  audit: (
    <>
      <rect x="5" y="4" width="14" height="17" rx="2" />
      <path d="M9 4.2A2 2 0 0 1 11 2.5h2a2 2 0 0 1 2 1.7" />
      <path d="M9 12.5l2 2 4-4.5" />
    </>
  ),
  /* 配置：滑杆 */
  config: (
    <>
      <path d="M4 8h9M19 8h1" />
      <circle cx="16" cy="8" r="2.3" />
      <path d="M4 16h3M11 16h9" />
      <circle cx="8" cy="16" r="2.3" />
    </>
  ),
  search: (
    <>
      <circle cx="11" cy="11" r="7" />
      <path d="m20 20-3.6-3.6" />
    </>
  ),
  sun: (
    <>
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2.5V5M12 19v2.5M4.8 4.8l1.8 1.8M17.4 17.4l1.8 1.8M2.5 12H5M19 12h2.5M4.8 19.2l1.8-1.8M17.4 6.6l1.8-1.8" />
    </>
  ),
  moon: <path d="M20 13.6A8 8 0 1 1 10.4 4a6.6 6.6 0 0 0 9.6 9.6Z" />,
  refresh: (
    <>
      <path d="M20.5 12a8.5 8.5 0 1 1-2.5-6" />
      <path d="M20.5 3.5V8H16" />
    </>
  ),
  collapse: (
    <>
      <rect x="3" y="4" width="18" height="16" rx="2" />
      <path d="M9.5 4v16" />
      <path d="m14.5 10-2 2 2 2" />
    </>
  ),
  expand: (
    <>
      <rect x="3" y="4" width="18" height="16" rx="2" />
      <path d="M9.5 4v16" />
      <path d="m12.5 10 2 2-2 2" />
    </>
  ),
  help: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M9.6 9.4a2.4 2.4 0 1 1 3.4 2.2c-.8.4-1.1 1-1.1 1.8" />
      <path d="M12 16.8v.2" />
    </>
  ),
  link: (
    <>
      <path d="M10 14.2a4.8 4.8 0 0 0 6.8 0l2.6-2.6a4.8 4.8 0 0 0-6.8-6.8l-1.3 1.3" />
      <path d="M14 9.8a4.8 4.8 0 0 0-6.8 0l-2.6 2.6a4.8 4.8 0 0 0 6.8 6.8l1.3-1.3" />
    </>
  ),
  density: <path d="M4 6.5h16M4 12h16M4 17.5h16" />,
};

export interface IconProps {
  name: IconName;
  /** 像素尺寸，默认 16；导航里常用 18。 */
  size?: number;
  className?: string;
  style?: CSSProperties;
}

/**
 * 统一的图标出口。
 *
 * <p>用法：`<Icon name="sessions" />`。
 * 图标一律 aria-hidden——语义由旁边的文字承担，
 * 纯图标按钮自己带 aria-label。</p>
 */
export function Icon({ name, size = 16, className, style }: IconProps): JSX.Element {
  return (
    <svg
      className={className === undefined ? 'icon' : `icon ${className}`}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      style={style}
    >
      {ICONS[name]}
    </svg>
  );
}
