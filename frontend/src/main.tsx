import React from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { setRuntimeApiBase } from './api';
import { inDesktopShell, tauriInvoke, type ShellConnection } from './lib/tauri';
import 'uplot/dist/uPlot.min.css';
import './App.css';

const container = document.getElementById('root');

/**
 * 桌面壳内先取后端地址再渲染：本机模式由壳拉起 secs-sim.exe 后给出地址，
 * 远程模式直接给配置的服务端地址。取不到（本机启动失败）也照常渲染，
 * 界面会显示「后端不可达」，并可在右上角「连接」里切换远程。
 */
async function boot(): Promise<void> {
  if (container === null) {
    throw new Error('#root not found');
  }
  if (inDesktopShell) {
    try {
      const info = await tauriInvoke<ShellConnection>('connection_info');
      setRuntimeApiBase(info.url);
    } catch {
      /* 后端未就绪：保持未注入，同源兜底 */
    }
  }
  createRoot(container).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>,
  );
  /* 首屏占位只在等待时存在:渲染完成就撤掉,避免遮住真实界面 */
  document.getElementById('boot')?.remove();
}

void boot();
