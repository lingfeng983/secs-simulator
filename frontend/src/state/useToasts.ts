import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

/** 提示的种类。 */
export type ToastKind = 'success' | 'error' | 'info' | 'warn';

/** 一条提示。 */
export interface Toast {
  id: number;
  kind: ToastKind;
  title: string;
  /** 补充说明，比如错误详情。 */
  detail?: string;
  at: number;
}

/** 提示的默认停留时间（毫秒）。错误停久一点，用户要读。 */
const TIMEOUT: Record<ToastKind, number> = {
  success: 2600,
  info: 3200,
  warn: 5000,
  error: 8000,
};

/** 同时最多显示几条；再多就顶掉最旧的。 */
const MAX_VISIBLE = 4;

/** 通知接口：面板只管喊一声，呈现细节由宿主统一管。 */
export interface Notifier {
  toasts: Toast[];
  /** 弹一条提示。 */
  notify: (kind: ToastKind, title: string, detail?: string) => void;
  success: (title: string, detail?: string) => void;
  error: (title: string, detail?: string) => void;
  info: (title: string, detail?: string) => void;
  warn: (title: string, detail?: string) => void;
  /** 关掉一条。 */
  dismiss: (id: number) => void;
  /** 全部关掉。 */
  dismissAll: () => void;
}

/**
 * 轻量通知中心。
 *
 * <p>存在的原因很实际：以前所有操作结果都写进页面最底部的日志，
 * 用户点了"上报"之后，什么反馈都没有——要么滚到最底下才看得到，
 * 要么干脆不知道成没成。提示条把反馈送到眼睛所在的位置。</p>
 */
export function useToasts(): Notifier {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(1);
  const timers = useRef(new Map<number, number>());

  const dismiss = useCallback((id: number) => {
    setToasts((previous) => previous.filter((toast) => toast.id !== id));
    const timer = timers.current.get(id);
    if (timer !== undefined) {
      window.clearTimeout(timer);
      timers.current.delete(id);
    }
  }, []);

  const notify = useCallback(
    (kind: ToastKind, title: string, detail?: string) => {
      const id = nextId.current;
      nextId.current += 1;
      setToasts((previous) => {
        const next = [...previous, { id, kind, title, detail, at: Date.now() }];
        return next.length > MAX_VISIBLE ? next.slice(next.length - MAX_VISIBLE) : next;
      });
      const timer = window.setTimeout(() => dismiss(id), TIMEOUT[kind]);
      timers.current.set(id, timer);
    },
    [dismiss],
  );

  const dismissAll = useCallback(() => {
    setToasts([]);
    for (const timer of timers.current.values()) {
      window.clearTimeout(timer);
    }
    timers.current.clear();
  }, []);

  /* 组件卸载时把定时器清干净：留着会对着已卸载的组件 setState */
  useEffect(() => {
    const pending = timers.current;
    return () => {
      for (const timer of pending.values()) {
        window.clearTimeout(timer);
      }
      pending.clear();
    };
  }, []);

  return useMemo<Notifier>(
    () => ({
      toasts,
      notify,
      success: (title, detail) => notify('success', title, detail),
      error: (title, detail) => notify('error', title, detail),
      info: (title, detail) => notify('info', title, detail),
      warn: (title, detail) => notify('warn', title, detail),
      dismiss,
      dismissAll,
    }),
    [toasts, notify, dismiss, dismissAll],
  );
}
