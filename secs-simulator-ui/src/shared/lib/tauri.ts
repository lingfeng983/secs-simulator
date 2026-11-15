/**
 * 与 Tauri 桌面壳（Windows 安装程序）的桥接。
 *
 * <p>同一份前端有三种运行环境：桌面壳内（window.__TAURI__ 存在，页面来自壳内置资源）、
 * 浏览器直开后端托管页（无桥）。桥存在时后端地址由壳决定（本机拉起的进程或远程服务端），
 * 连接切换走 IPC；桥不存在时保持浏览器行为（localStorage 覆盖 API base）。</p>
 */

interface TauriGlobal {
  core: {
    invoke: (cmd: string, args?: Record<string, unknown>) => Promise<unknown>;
  };
}

/** 壳内部元数据：只用到当前窗口 label（自绘标题栏的窗口命令要按 label 发）。 */
interface TauriInternals {
  metadata?: { currentWindow?: { label?: string } };
}

const tauri = (window as { __TAURI__?: TauriGlobal }).__TAURI__;

/** 是否运行在桌面壳里。 */
export const inDesktopShell: boolean = tauri !== undefined;

/** 壳内连接信息（connection_info 命令的返回）。 */
export interface ShellConnection {
  mode: 'local' | 'remote';
  url: string;
}

/** 调壳命令；不在壳内时恒拒绝（调用方按浏览器行为回退）。 */
export function tauriInvoke<T>(cmd: string, args?: Record<string, unknown>): Promise<T> {
  if (tauri === undefined) {
    return Promise.reject(new Error('not in desktop shell'));
  }
  return tauri.core.invoke(cmd, args) as Promise<T>;
}

/** 当前窗口 label：窗口命令都得带上它，读不到时退回配置里的 main。 */
function currentWindowLabel(): string {
  const internals = (window as { __TAURI_INTERNALS__?: TauriInternals }).__TAURI_INTERNALS__;
  return internals?.metadata?.currentWindow?.label ?? 'main';
}

/**
 * 自绘标题栏的窗口动作。
 *
 * <p>窗口是无边框的（<code>decorations: false</code>），最小化 / 最大化 / 关闭
 * 全由界面按钮发起；浏览器环境没有这些命令，调用方只按 <code>inDesktopShell</code>
 * 决定要不要渲染按钮。</p>
 */
export type WindowCommand = 'minimize' | 'toggleMaximize' | 'close' | 'isMaximized';

const WINDOW_COMMANDS: Record<WindowCommand, string> = {
  minimize: 'plugin:window|minimize',
  toggleMaximize: 'plugin:window|toggle_maximize',
  close: 'plugin:window|close',
  isMaximized: 'plugin:window|is_maximized',
};

/** 执行窗口动作；最大化状态查询返回布尔，其余返回 undefined。 */
export function windowCommand(command: WindowCommand): Promise<unknown> {
  return tauriInvoke(WINDOW_COMMANDS[command], { label: currentWindowLabel() });
}
