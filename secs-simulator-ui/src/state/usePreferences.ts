import { useCallback, useEffect, useMemo, useState } from 'react';
import { usePersistentState } from './usePersistentState';


/** 主题偏好：跟随系统 / 强制浅色 / 强制深色。 */
export type ThemePreference = 'light' | 'dark' | 'system';

/** 表格密度。 */
export type Density = 'comfortable' | 'compact';

/** 内容区整体布局：铺满 / 居中限宽。 */
export type PageLayout = 'wide' | 'centered';

/** 主色预设：青（默认）/ 蓝 / 靛 / 紫 / 品红 / 橙 / 翡翠 / 石墨。 */
export type AccentPreset =
  | 'teal' | 'blue' | 'indigo' | 'violet' | 'rose' | 'amber' | 'emerald' | 'slate';

/** 通用设置里可调节的数值范围。 */
export const FONT_SIZE_RANGE = { min: 12, max: 18, step: 1 } as const;
export const PANEL_GAP_RANGE = { min: 6, max: 28, step: 2 } as const;
export const PANEL_PAD_RANGE = { min: 10, max: 30, step: 2 } as const;
/** 左侧导航宽度：拖动与设置项共用的绝对边界。 */
export const SIDEBAR_W_RANGE = { min: 160, max: 480, step: 4 } as const;
/** 右侧消息监控宽度：拖动与设置项共用的绝对边界。 */
export const MONITOR_W_RANGE = { min: 240, max: 900, step: 10 } as const;
/** 消息监控默认回看时长（分钟）。 */
export const MONITOR_WINDOW_RANGE = { min: 1, max: 1440, step: 1 } as const;

export const ACCENT_PRESETS: readonly { id: AccentPreset; label: string; color: string }[] = [
  { id: 'teal', label: '信号青', color: '#0f766e' },
  { id: 'blue', label: '工业蓝', color: '#2563eb' },
  { id: 'indigo', label: '靛蓝', color: '#4f46e5' },
  { id: 'violet', label: '深紫', color: '#7c3aed' },
  { id: 'rose', label: '品红', color: '#be123c' },
  { id: 'amber', label: '琥珀橙', color: '#b45309' },
  { id: 'emerald', label: '翡翠绿', color: '#047857' },
  { id: 'slate', label: '石墨灰', color: '#475569' },
];

const STORAGE_KEY = 'secs-sim-ui:preferences';

/** 面板级偏好。 */
/** 左侧导航宽度的默认值与兜底边界（真实边界由偏好里的 sidebarMinW/MaxW 决定）。 */
export const SIDEBAR_MIN_W = SIDEBAR_W_RANGE.min;
export const SIDEBAR_MAX_W = SIDEBAR_W_RANGE.max;
export const SIDEBAR_DEFAULT_W = 248;

export interface Preferences {
  theme: ThemePreference;
  density: Density;
  /** 上次停留的面板。 */
  activeTab: string;
  /** 左侧导航是否折叠为纯图标栏。 */
  sidebarCollapsed: boolean;
  /** 左侧导航展开时的宽度（px）。 */
  sidebarWidth: number;
  /** 报文面板是否自动滚动到底。 */
  autoscroll: boolean;
  /** 报文面板是否暂停接收（只冻结显示，不影响服务端）。 */
  pauseStream: boolean;
  /** 预览窗口（毫秒）。 */
  windowMs: number;
  /** 基础字号（px）。写进 --font，并按比例缩放根字号。 */
  fontSize: number;
  /** 内容区整体布局。 */
  layout: PageLayout;
  /** 主色预设。 */
  accent: AccentPreset;
  /** 面板之间的间隔（px）。 */
  panelGap: number;
  /** 面板内边距（px）。 */
  panelPad: number;
  /** 消息监控默认回看多少分钟以内的报文。 */
  monitorWindowMin: number;
  /** 消息监控宽度的下界 / 上界（px）。 */
  monitorMinW: number;
  monitorMaxW: number;
  /** 左侧导航宽度的下界 / 上界（px）。 */
  sidebarMinW: number;
  sidebarMaxW: number;
}

export const DEFAULT_PREFERENCES: Preferences = {
  theme: 'system',
  density: 'comfortable',
  activeTab: 'workbench',
  sidebarCollapsed: false,
  sidebarWidth: SIDEBAR_DEFAULT_W,
  autoscroll: true,
  pauseStream: false,
  windowMs: 60_000,
  fontSize: 13,
  layout: 'wide',
  accent: 'teal',
  panelGap: 14,
  panelPad: 18,
  monitorWindowMin: 60,
  monitorMinW: 300,
  monitorMaxW: 720,
  sidebarMinW: 180,
  sidebarMaxW: 420,
};

/** 把外部来源（localStorage / 旧版本）的宽度收进合法区间。 */
export function clampSidebarWidth(
  value: number,
  min: number = SIDEBAR_MIN_W,
  max: number = SIDEBAR_MAX_W,
): number {
  if (!Number.isFinite(value)) {
    return SIDEBAR_DEFAULT_W;
  }
  return Math.min(max, Math.max(min, Math.round(value)));
}

/**
 * 上下界成对出现：下界必须严格小于上界，否则拖动会被夹成 0 宽。
 *
 * <p>用户可能把「最小」拉到比「最大」还大，这里统一收口：
 * 下界越界就顶到上界之下，上界越界就抬到下界之上。</p>
 */
export function clampBounds(
  min: number,
  max: number,
  range: { min: number; max: number; step: number },
  gap: number = 40,
): { min: number; max: number } {
  const safeMin = clampRange(min, range, range.min);
  const safeMax = clampRange(max, range, range.max);
  if (safeMax - safeMin >= gap) {
    return { min: safeMin, max: safeMax };
  }
  return {
    min: Math.max(range.min, Math.min(safeMin, range.max - gap)),
    max: Math.min(range.max, Math.max(safeMax, range.min + gap)),
  };
}

/** 数值偏好统一收口：旧数据缺字段、越界或手输脏值都不会把布局撑坏。 */
export function clampRange(value: number, range: { min: number; max: number }, fallback: number): number {
  if (!Number.isFinite(value)) {
    return fallback;
  }
  return Math.min(range.max, Math.max(range.min, Math.round(value)));
}

/** 系统当前是不是深色。 */
function systemPrefersDark(): boolean {
  return typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-color-scheme: dark)').matches;
}

/**
 * 把"跟随系统"解析成实际要用的主题。
 *
 * <p>系统主题是活的：用户晚上把系统切成深色，页面应该跟着变，
 * 不该要求刷新。</p>
 *
 * @param preference 用户选择
 * @param systemDark 系统当前是不是深色；不给就现场问一次
 */
export function resolveTheme(
  preference: ThemePreference,
  systemDark: boolean = systemPrefersDark(),
): 'light' | 'dark' {
  if (preference === 'system') {
    return systemDark ? 'dark' : 'light';
  }
  return preference;
}

/**
 * 界面偏好：主题、密度、当前面板、预览时间窗……这些都要跨刷新保留。
 *
 * <p>没有它的话，用户每次刷新页面都要重新选一遍面板、
 * 重新拉一次时间窗——这种"每次都要重做"的摩擦最消磨耐心。</p>
 */
export function usePreferences(): {
  preferences: Preferences;
  update: (patch: Partial<Preferences>) => void;
  /** 实际生效的主题（已解析掉"跟随系统"）。 */
  resolvedTheme: 'light' | 'dark';
} {
  const [stored, setStored] = usePersistentState<Preferences>(STORAGE_KEY, DEFAULT_PREFERENCES);

  /* 老版本存下的键可能缺字段，逐个补齐，别让一个 undefined 把界面搞崩 */
  const preferences = useMemo<Preferences>(
    () => {
      /* 上下界先互相收口，再拿收口后的边界去夹当前宽度：
         否则「最小 500 / 最大 300」这种输入会让侧栏直接消失 */
      const sidebarBounds = clampBounds(
        stored.sidebarMinW ?? DEFAULT_PREFERENCES.sidebarMinW,
        stored.sidebarMaxW ?? DEFAULT_PREFERENCES.sidebarMaxW,
        SIDEBAR_W_RANGE,
      );
      const monitorBounds = clampBounds(
        stored.monitorMinW ?? DEFAULT_PREFERENCES.monitorMinW,
        stored.monitorMaxW ?? DEFAULT_PREFERENCES.monitorMaxW,
        MONITOR_W_RANGE,
      );
      return {
        ...DEFAULT_PREFERENCES,
        ...stored,
        sidebarMinW: sidebarBounds.min,
        sidebarMaxW: sidebarBounds.max,
        monitorMinW: monitorBounds.min,
        monitorMaxW: monitorBounds.max,
        monitorWindowMin: clampRange(
          stored.monitorWindowMin ?? DEFAULT_PREFERENCES.monitorWindowMin,
          MONITOR_WINDOW_RANGE,
          DEFAULT_PREFERENCES.monitorWindowMin,
        ),
        /* 宽度可能被手工改过或来自旧版本：越界值会把侧栏撑爆/压没 */
        sidebarWidth: clampSidebarWidth(
          stored.sidebarWidth ?? DEFAULT_PREFERENCES.sidebarWidth,
          sidebarBounds.min,
          sidebarBounds.max,
        ),
        fontSize: clampRange(stored.fontSize ?? DEFAULT_PREFERENCES.fontSize, FONT_SIZE_RANGE, DEFAULT_PREFERENCES.fontSize),
        panelGap: clampRange(stored.panelGap ?? DEFAULT_PREFERENCES.panelGap, PANEL_GAP_RANGE, DEFAULT_PREFERENCES.panelGap),
        panelPad: clampRange(stored.panelPad ?? DEFAULT_PREFERENCES.panelPad, PANEL_PAD_RANGE, DEFAULT_PREFERENCES.panelPad),
      };
    },
    [stored],
  );

  const update = useCallback(
    (patch: Partial<Preferences>) => {
      setStored((previous) => ({ ...DEFAULT_PREFERENCES, ...previous, ...patch }));
    },
    [setStored],
  );

  const [systemDark, setSystemDark] = useState(() => systemPrefersDark());

  useEffect(() => {
    if (typeof window.matchMedia !== 'function') {
      return;
    }
    const query = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = (event: MediaQueryListEvent) => setSystemDark(event.matches);
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, []);

  const resolvedTheme = resolveTheme(preferences.theme, systemDark);

  /* 把主题写到 <html> 上，CSS 靠这个属性切变量 */
  useEffect(() => {
    document.documentElement.dataset.theme = resolvedTheme;
    document.documentElement.style.colorScheme = resolvedTheme;
  }, [resolvedTheme]);

  /* 密度写到 <html>，表格行高等跟着变 */
  useEffect(() => {
    document.documentElement.dataset.density = preferences.density;
  }, [preferences.density]);

  /* 通用设置：布局 / 主色 / 字号 / 面板间隔都落到 <html>，组件只认语义变量。
     数值由 JS 收口后写内联变量，紧凑密度在计算结果里扣掉固定的 4px。
     字号同时写两处：根字号（rem 类字号跟着变）与 --font-scale
     （CSS 里写死 px 的字号统一乘这个系数，见 styles/base.css 的说明）。 */
  useEffect(() => {
    const root = document.documentElement;
    const compact = preferences.density === 'compact' ? 4 : 0;
    root.dataset.layout = preferences.layout;
    root.dataset.accent = preferences.accent;
    root.style.fontSize = `${16 * (preferences.fontSize / DEFAULT_PREFERENCES.fontSize)}px`;
    root.style.setProperty('--font-scale', String(preferences.fontSize / DEFAULT_PREFERENCES.fontSize));
    root.style.setProperty('--gap', `${Math.max(4, preferences.panelGap - compact)}px`);
    root.style.setProperty('--panel-pad', `${Math.max(8, preferences.panelPad - compact)}px`);
  }, [preferences.layout, preferences.accent, preferences.fontSize, preferences.panelGap, preferences.panelPad, preferences.density]);

  return { preferences, update, resolvedTheme };
}
