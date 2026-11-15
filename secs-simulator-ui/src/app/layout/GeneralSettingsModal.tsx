import type { CSSProperties } from 'react';
import { Modal } from '../../shared/ui/Modal';
import {
  ACCENT_PRESETS,
  DEFAULT_PREFERENCES,
  FONT_SIZE_RANGE,
  MONITOR_W_RANGE,
  MONITOR_WINDOW_RANGE,
  PANEL_GAP_RANGE,
  PANEL_PAD_RANGE,
  SIDEBAR_W_RANGE,
  clampBounds,
  clampSidebarWidth,
  type AccentPreset,
  type PageLayout,
  type Preferences,
  type ThemePreference,
} from '../../state/usePreferences';

export interface GeneralSettingsModalProps {
  open: boolean;
  preferences: Preferences;
  onChange: (patch: Partial<Preferences>) => void;
  onClose: () => void;
  /** 后端日志级别：原来在标题栏，现在收进这里。 */
  logLevel: string;
  onLogLevel: (level: string) => void;
}

/**
 * 通用设置：主题、布局、字号、主色、面板间距、宽度上下限、消息回看时长、日志级别。
 *
 * <p>改动即时生效并跨刷新保留；「恢复默认」只重置这里列出的展示项，
 * 不动侧栏折叠状态、当前页面、报文窗口这些使用状态。</p>
 */
export function GeneralSettingsModal({
  open,
  preferences,
  onChange,
  onClose,
  logLevel,
  onLogLevel,
}: GeneralSettingsModalProps): JSX.Element | null {
  const restore = () => {
    onChange({
      theme: DEFAULT_PREFERENCES.theme,
      density: DEFAULT_PREFERENCES.density,
      layout: DEFAULT_PREFERENCES.layout,
      fontSize: DEFAULT_PREFERENCES.fontSize,
      accent: DEFAULT_PREFERENCES.accent,
      panelGap: DEFAULT_PREFERENCES.panelGap,
      panelPad: DEFAULT_PREFERENCES.panelPad,
      sidebarWidth: DEFAULT_PREFERENCES.sidebarWidth,
      sidebarMinW: DEFAULT_PREFERENCES.sidebarMinW,
      sidebarMaxW: DEFAULT_PREFERENCES.sidebarMaxW,
      monitorMinW: DEFAULT_PREFERENCES.monitorMinW,
      monitorMaxW: DEFAULT_PREFERENCES.monitorMaxW,
      monitorWindowMin: DEFAULT_PREFERENCES.monitorWindowMin,
    });
  };

  /* 上下界成对改：谁被拖过头就把另一个顶开，永远保持 min < max */
  const setSidebarBounds = (min: number, max: number) => {
    const bounds = clampBounds(min, max, SIDEBAR_W_RANGE);
    onChange({
      sidebarMinW: bounds.min,
      sidebarMaxW: bounds.max,
      sidebarWidth: clampSidebarWidth(preferences.sidebarWidth, bounds.min, bounds.max),
    });
  };

  const setMonitorBounds = (min: number, max: number) => {
    const bounds = clampBounds(min, max, MONITOR_W_RANGE);
    onChange({ monitorMinW: bounds.min, monitorMaxW: bounds.max });
  };

  return (
    <Modal
      open={open}
      title="通用设置"
      width="min(720px, 94vw)"
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={restore}>恢复默认</button>
          <button type="button" className="primary" onClick={onClose}>完成</button>
        </>
      }
    >
      <div className="settings-grid">
        <label className="field">
          主题
          <select
            value={preferences.theme}
            aria-label="主题"
            onChange={(event) => onChange({ theme: event.target.value as ThemePreference })}
          >
            <option value="light">浅色</option>
            <option value="dark">深色</option>
            <option value="system">跟随系统</option>
          </select>
        </label>
        <label className="field">
          表格密度
          <select
            value={preferences.density}
            aria-label="表格密度"
            onChange={(event) => onChange({ density: event.target.value as Preferences['density'] })}
          >
            <option value="comfortable">宽松</option>
            <option value="compact">紧凑</option>
          </select>
        </label>
        <label className="field">
          整体布局
          <select
            value={preferences.layout}
            aria-label="整体布局"
            onChange={(event) => onChange({ layout: event.target.value as PageLayout })}
          >
            <option value="wide">铺满宽度</option>
            <option value="centered">居中限宽</option>
          </select>
        </label>
        <label className="field">
          主色
          <select
            value={preferences.accent}
            aria-label="主色"
            onChange={(event) => onChange({ accent: event.target.value as AccentPreset })}
          >
            {ACCENT_PRESETS.map((item) => (
              <option key={item.id} value={item.id}>{item.label}</option>
            ))}
          </select>
        </label>
      </div>

      <div className="settings-swatches" role="group" aria-label="快捷配色">
        {ACCENT_PRESETS.map((item) => (
          <button
            key={item.id}
            type="button"
            className={`settings-swatch${preferences.accent === item.id ? ' active' : ''}`}
            style={{ '--swatch': item.color } as CSSProperties}
            aria-label={`配色：${item.label}`}
            aria-pressed={preferences.accent === item.id}
            title={item.label}
            onClick={() => onChange({ accent: item.id })}
          >
            <span />
          </button>
        ))}
      </div>

      <h3 className="settings-section">文字与间距</h3>
      <div className="settings-sliders">
        <label className="settings-slider">
          <span className="settings-slider-head">
            <span>字体大小</span><span className="mono">{preferences.fontSize} px</span>
          </span>
          <input
            type="range"
            min={FONT_SIZE_RANGE.min}
            max={FONT_SIZE_RANGE.max}
            step={FONT_SIZE_RANGE.step}
            value={preferences.fontSize}
            aria-label="字体大小"
            onChange={(event) => onChange({ fontSize: Number(event.target.value) })}
          />
        </label>
        <label className="settings-slider">
          <span className="settings-slider-head">
            <span>面板间隔</span><span className="mono">{preferences.panelGap} px</span>
          </span>
          <input
            type="range"
            min={PANEL_GAP_RANGE.min}
            max={PANEL_GAP_RANGE.max}
            step={PANEL_GAP_RANGE.step}
            value={preferences.panelGap}
            aria-label="面板间隔"
            onChange={(event) => onChange({ panelGap: Number(event.target.value) })}
          />
        </label>
        <label className="settings-slider">
          <span className="settings-slider-head">
            <span>面板内边距</span><span className="mono">{preferences.panelPad} px</span>
          </span>
          <input
            type="range"
            min={PANEL_PAD_RANGE.min}
            max={PANEL_PAD_RANGE.max}
            step={PANEL_PAD_RANGE.step}
            value={preferences.panelPad}
            aria-label="面板内边距"
            onChange={(event) => onChange({ panelPad: Number(event.target.value) })}
          />
        </label>
        <label className="settings-slider">
          <span className="settings-slider-head">
            <span>导航宽度</span><span className="mono">{preferences.sidebarWidth} px</span>
          </span>
          <input
            type="range"
            min={preferences.sidebarMinW}
            max={preferences.sidebarMaxW}
            step={SIDEBAR_W_RANGE.step}
            value={preferences.sidebarWidth}
            aria-label="导航宽度"
            onChange={(event) => onChange({
              sidebarWidth: clampSidebarWidth(Number(event.target.value), preferences.sidebarMinW, preferences.sidebarMaxW),
            })}
          />
        </label>
      </div>

      <h3 className="settings-section">宽度上下限</h3>
      <div className="settings-bounds">
        <div className="settings-bound">
          <span className="settings-bound-title">左侧菜单</span>
          <label className="settings-bound-field">
            最小
            <input
              type="number"
              min={SIDEBAR_W_RANGE.min}
              max={SIDEBAR_W_RANGE.max}
              step={SIDEBAR_W_RANGE.step}
              value={preferences.sidebarMinW}
              aria-label="左侧菜单最小宽度"
              onChange={(event) => setSidebarBounds(Number(event.target.value), preferences.sidebarMaxW)}
            />
          </label>
          <label className="settings-bound-field">
            最大
            <input
              type="number"
              min={SIDEBAR_W_RANGE.min}
              max={SIDEBAR_W_RANGE.max}
              step={SIDEBAR_W_RANGE.step}
              value={preferences.sidebarMaxW}
              aria-label="左侧菜单最大宽度"
              onChange={(event) => setSidebarBounds(preferences.sidebarMinW, Number(event.target.value))}
            />
          </label>
        </div>
        <div className="settings-bound">
          <span className="settings-bound-title">消息监控</span>
          <label className="settings-bound-field">
            最小
            <input
              type="number"
              min={MONITOR_W_RANGE.min}
              max={MONITOR_W_RANGE.max}
              step={MONITOR_W_RANGE.step}
              value={preferences.monitorMinW}
              aria-label="消息监控最小宽度"
              onChange={(event) => setMonitorBounds(Number(event.target.value), preferences.monitorMaxW)}
            />
          </label>
          <label className="settings-bound-field">
            最大
            <input
              type="number"
              min={MONITOR_W_RANGE.min}
              max={MONITOR_W_RANGE.max}
              step={MONITOR_W_RANGE.step}
              value={preferences.monitorMaxW}
              aria-label="消息监控最大宽度"
              onChange={(event) => setMonitorBounds(preferences.monitorMinW, Number(event.target.value))}
            />
          </label>
        </div>
      </div>

      <h3 className="settings-section">消息监控</h3>
      <div className="settings-grid">
        <label className="field">
          默认回看时长
          <select
            value={preferences.monitorWindowMin}
            aria-label="消息监控默认回看时长"
            onChange={(event) => onChange({ monitorWindowMin: Number(event.target.value) })}
          >
            <option value={5}>最近 5 分钟</option>
            <option value={15}>最近 15 分钟</option>
            <option value={30}>最近 30 分钟</option>
            <option value={60}>最近 1 小时</option>
            <option value={180}>最近 3 小时</option>
            <option value={480}>最近 8 小时</option>
            <option value={1440}>最近 24 小时</option>
          </select>
        </label>
        <label className="field">
          后端日志级别
          <select
            value={logLevel}
            aria-label="后端日志级别"
            onChange={(event) => onLogLevel(event.target.value)}
          >
            <option value="error">error</option>
            <option value="info">info</option>
            <option value="debug">debug</option>
          </select>
        </label>
      </div>

      <p className="hint">
        调整会立即生效，并保存在当前浏览器里；换浏览器或清除浏览器数据后回到默认值。
        消息监控只回看所选时长内的报文（{MONITOR_WINDOW_RANGE.min}～{MONITOR_WINDOW_RANGE.max} 分钟）。
      </p>
    </Modal>
  );
}
