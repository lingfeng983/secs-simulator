import { useEffect, useRef } from 'react';

/**
 * 组件级轮询定时器（N6）。
 *
 * <p>相对裸 setInterval 的三点改善：
 * 1) callback 走 ref——回调身份变化不会重启定时器、不丢节拍；
 * 2) 页面不可见（document.hidden）时跳过执行，后台标签页不再空转打后端；
 * 3) visibilitychange 恢复可见时立即补跑一次，回页即见新数据。
 * 卸载/enabled=false 时清理定时器与监听。</p>
 *
 * @param callback 每个节拍执行的动作（通常是 void load()）
 * @param intervalMs 轮询间隔（毫秒）
 * @param enabled 为 false 时完全不建定时器（如只读模式、未开启自动刷新）
 */
export function useVisibilityInterval(callback: () => void, intervalMs: number, enabled = true): void {
  const callbackRef = useRef(callback);
  callbackRef.current = callback;

  useEffect(() => {
    if (!enabled) {
      return;
    }
    const timer = window.setInterval(() => {
      if (document.hidden) {
        return;
      }
      callbackRef.current();
    }, intervalMs);
    const onVisibility = () => {
      if (!document.hidden) {
        callbackRef.current();
      }
    };
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [intervalMs, enabled]);
}