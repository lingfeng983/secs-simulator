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
