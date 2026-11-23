import React from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { setRuntimeApiBase, setRuntimeApiFailure } from '../api';
import { inDesktopShell, tauriInvoke, type ShellConnection } from '../shared/lib/tauri';
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
    } catch (error) {
      /* 不能退回同源：壳里的同源是内置前端资源，/api/* 会落到 index.html 上，
         界面只会报"200 返回的不是 JSON"，把真正的原因（后端起不来）盖掉了。
         记下失败原因，让请求直接带着它失败，横幅上就是能照着排查的话。 */
      setRuntimeApiFailure(
        `本机后端没有启动（${String(error)}）。可在托盘退出后重开，或在「修改地址」里改连远程服务端`,
      );
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
