import { useCallback, useEffect, useState } from 'react';
import { Icon } from '../../shared/ui/Icon';
import { AppMark } from '../../shared/ui/AppMark';
import { inDesktopShell, windowCommand } from '../../shared/lib/tauri';

export interface TitlebarProps {
  refreshing: boolean;
  onRefresh: () => void;
  onOpenPalette: () => void;
  onOpenConnection: () => void;
  /** 打开「通用设置」（主题 / 布局 / 字号 / 颜色 / 间隔 / 日志级别）。 */
  onOpenSettings: () => void;
  resolvedTheme: 'light' | 'dark';
  onToggleTheme: () => void;
  onShowHelp: () => void;
  /** 右侧实时消息监控抽屉是否展开。 */
  monitorOpen: boolean;
  /** 展开/隐藏消息监控。 */
  toggleMonitor: () => void;
}

/**
 * 顶部信息栏（自绘标题栏）。
 *
 * <p>它横跨整个窗口：左侧菜单、内容区、右侧消息监控都从它下方开始，
 * 所以第一行永远只有这一条。只保留两个信息：最左的应用图标、
 * 最右的一组纯图标按钮 + 窗口控制（最小化/最大化/关闭）。
 * 当前页面路径、按钮文字、日志级别、密度开关都不在这里——
 * 路径看页签，日志级别在「通用设置」里。</p>
 *
 * <p>窗口是无边框的（<code>decorations: false</code>），三个窗口按钮由界面自己画，
 * 和其余按钮排在同一行；浏览器里没有窗口可控制，这一组不渲染。</p>
 */
export function Titlebar({
  refreshing,
  onRefresh,
  onOpenPalette,
  onOpenConnection,
  onOpenSettings,
  resolvedTheme,
  onToggleTheme,
  onShowHelp,
  monitorOpen,
  toggleMonitor,
}: TitlebarProps): JSX.Element {
  return (
    <header className="titlebar" data-tauri-drag-region>
      {/* 最左：GUI 图标区，只有图标 */}
      <div className="titlebar-brand" data-tauri-drag-region>
        <AppMark size={22} />
      </div>

      <div className="titlebar-actions">
        <button
          type="button"
          className="icon-btn"
          onClick={onOpenSettings}
          title="通用设置：主题、布局、字号、主色、面板间隔、日志级别"
          aria-label="通用设置"
        >
          <Icon name="settings" size={16} />
        </button>

        <button
          type="button"
          className={`icon-btn monitor-toggle${monitorOpen ? ' active' : ''}`}
          onClick={toggleMonitor}
          aria-pressed={monitorOpen}
          title={monitorOpen ? '隐藏实时消息监控' : '展开实时消息监控（接收 / 发送按颜色区分）'}
          aria-label="消息监控"
        >
          <Icon name="messages" size={16} />
        </button>

        <button
          type="button"
          className="icon-btn"
          onClick={onRefresh}
          disabled={refreshing}
          title="立即刷新所有数据（R）"
          aria-label="刷新"
        >
          <Icon name="refresh" size={16} className={refreshing ? 'spin' : undefined} />
        </button>

        <button
          type="button"
          className="icon-btn"
          onClick={onOpenPalette}
          title="命令面板（Ctrl+K）"
          aria-label="命令面板"
        >
          <Icon name="search" size={16} />
        </button>

        <span className="titlebar-divider" aria-hidden="true" />

        <button
          type="button"
          className="icon-btn"
          onClick={onOpenConnection}
          title="设置后端地址（远程运行时后端）"
          aria-label="连接设置"
        >
          <Icon name="link" size={16} />
        </button>

        <button
          type="button"
          className="icon-btn"
          onClick={onToggleTheme}
          title={resolvedTheme === 'dark' ? '切换到浅色' : '切换到深色'}
          aria-label="切换主题"
        >
          <Icon name={resolvedTheme === 'dark' ? 'sun' : 'moon'} size={16} />
        </button>

        <button type="button" className="icon-btn" onClick={onShowHelp} title="快捷键（?）" aria-label="快捷键帮助">
          <Icon name="help" size={16} />
        </button>
      </div>

      <WindowControls />
    </header>
  );
}

/**
 * 最小化 / 最大化 / 关闭。
 *
 * <p>只在桌面壳里渲染——浏览器没有窗口可控制，画出来是死按钮。
 * 最大化状态跟窗口 resize 同步，图标在「最大化」和「还原」之间切换。</p>
 */
function WindowControls(): JSX.Element | null {
  const [maximized, setMaximized] = useState(false);

  const syncMaximized = useCallback(() => {
    void windowCommand('isMaximized')
      .then((value) => setMaximized(value === true))
      .catch(() => {
        /* 壳没给这个权限时保持默认图标，不影响其它按钮 */
      });
  }, []);

  useEffect(() => {
    if (!inDesktopShell) {
      return;
    }
    syncMaximized();
    window.addEventListener('resize', syncMaximized);
    return () => window.removeEventListener('resize', syncMaximized);
  }, [syncMaximized]);

  if (!inDesktopShell) {
    return null;
  }

  const run = (command: 'minimize' | 'toggleMaximize' | 'close'): void => {
    void windowCommand(command).then(() => {
      if (command === 'toggleMaximize') {
        syncMaximized();
      }
    }).catch(() => {
      /* 权限缺失时静默：按钮仍在，但不假装成功 */
    });
  };

  return (
    <div className="window-controls" role="group" aria-label="窗口控制">
      <button
        type="button"
        className="wc-btn"
        onClick={() => run('minimize')}
        title="最小化"
        aria-label="最小化"
      >
        <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true" focusable="false">
          <path d="M1 5h8" stroke="currentColor" strokeWidth="1.1" strokeLinecap="round" />
        </svg>
      </button>

      <button
        type="button"
        className="wc-btn"
        onClick={() => run('toggleMaximize')}
        title={maximized ? '还原' : '最大化'}
        aria-label={maximized ? '还原' : '最大化'}
      >
        {maximized ? (
          <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true" focusable="false">
            <rect x="1" y="2.6" width="6.4" height="6.4" rx="1" fill="none" stroke="currentColor" strokeWidth="1.1" />
            <path d="M3 2.6V1.4a.4.4 0 0 1 .4-.4h5.2a.4.4 0 0 1 .4.4v5.2a.4.4 0 0 1-.4.4H7.4" fill="none" stroke="currentColor" strokeWidth="1.1" />
          </svg>
        ) : (
          <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true" focusable="false">
            <rect x="1.4" y="1.4" width="7.2" height="7.2" rx="1.1" fill="none" stroke="currentColor" strokeWidth="1.1" />
          </svg>
        )}
      </button>

      <button
        type="button"
        className="wc-btn close"
        onClick={() => run('close')}
        title="关闭"
        aria-label="关闭窗口"
      >
        <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true" focusable="false">
          <path d="M1.8 1.8l6.4 6.4M8.2 1.8l-6.4 6.4" stroke="currentColor" strokeWidth="1.1" strokeLinecap="round" />
        </svg>
      </button>
    </div>
  );
}
