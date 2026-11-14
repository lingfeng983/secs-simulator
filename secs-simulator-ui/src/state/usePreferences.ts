import { useCallback, useEffect, useMemo, useState } from 'react';
import { usePersistentState } from './usePersistentState';


/** 主题偏好：跟随系统 / 强制浅色 / 强制深色。 */
export type ThemePreference = 'light' | 'dark' | 'system';

/** 表格密度。 */
export type Density = 'comfortable' | 'compact';

const STORAGE_KEY = 'secs-sim-ui:preferences';

/** 面板级偏好。 */
/** 左侧导航宽度：可拖动调整，限制在这个区间内。 */
export const SIDEBAR_MIN_W = 180;
export const SIDEBAR_MAX_W = 420;
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
  /** 预览图表模式。 */
  chartMode: 'canvas' | 'uplot' | 'multi';
  /** 预览窗口（毫秒）。 */
  windowMs: number;
}

export const DEFAULT_PREFERENCES: Preferences = {
  theme: 'system',
  density: 'comfortable',
  activeTab: 'workbench',
  sidebarCollapsed: false,
  sidebarWidth: SIDEBAR_DEFAULT_W,
  autoscroll: true,
  pauseStream: false,
  chartMode: 'canvas',
  windowMs: 60_000,
};

/** 把外部来源（localStorage / 旧版本）的宽度收进合法区间。 */
export function clampSidebarWidth(value: number): number {
  if (!Number.isFinite(value)) {
    return SIDEBAR_DEFAULT_W;
  }
  return Math.min(SIDEBAR_MAX_W, Math.max(SIDEBAR_MIN_W, Math.round(value)));
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
 * 界面偏好：主题、密度、当前面板、图表模式……这些都要跨刷新保留。
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
    () => ({
      ...DEFAULT_PREFERENCES,
      ...stored,
      /* 宽度可能被手工改过或来自旧版本：越界值会把侧栏撑爆/压没 */
      sidebarWidth: clampSidebarWidth(stored.sidebarWidth ?? DEFAULT_PREFERENCES.sidebarWidth),
    }),
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

  return { preferences, update, resolvedTheme };
}
