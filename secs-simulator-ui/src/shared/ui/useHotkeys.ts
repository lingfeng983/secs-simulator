import { useEffect, useRef } from 'react';

/** 一条快捷键绑定。 */
export interface HotkeyBinding {
  /**
   * 组合键，写法：`mod+k`、`shift+/`、`g`。
   *
   * <p>`mod` 在 Windows/Linux 是 Ctrl，在 macOS 是 Cmd。</p>
   */
  combo: string;
  /** 说明文字，显示在快捷键表里。 */
  description: string;
  handler: (event: KeyboardEvent) => void;
  /** 在输入框里也生效；默认 false（输入时不劫持按键）。 */
  allowInInput?: boolean;
}

function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false;
  }
  const tag = target.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable;
}

/** 把 `mod+shift+k` 拆成修饰键集合与主键。 */
function parseCombo(combo: string): { modifiers: Set<string>; key: string } {
  const parts = combo.toLowerCase().split('+');
  const key = parts.pop() ?? '';
  const modifiers = new Set(parts);
  return { modifiers, key };
}

/** 是否要求 Ctrl / Cmd。 */
function wantsModOf(modifiers: Set<string>): boolean {
  return modifiers.has('mod');
}

/**
 * 全局快捷键。
 *
 * <p>绑定对象每次渲染都会是新数组，所以真正注册到 DOM 上的监听器
 * 只装一次，靠 ref 拿最新的 handler——否则每敲一次键就要重装一遍监听。</p>
 */
export function useHotkeys(bindings: readonly HotkeyBinding[], enabled = true): void {
  const bindingsRef = useRef(bindings);
  bindingsRef.current = bindings;

  useEffect(() => {
    if (!enabled) {
      return;
    }

    const onKeyDown = (event: KeyboardEvent) => {
      /* 按住 Ctrl 再按 Shift+T 之类的组合，浏览器会当成"重开标签页"，
         提前拦掉，免得用户刚要触发命令结果页面刷没了 */
      if (event.key.length === 1 && event.ctrlKey && event.shiftKey) {
        const key = event.key.toLowerCase();
        if (key === 't' || key === 'w' || key === 'n') {
          return;
        }
      }

      const inInput = isEditableTarget(event.target);

      /* 有模态浮层（确认框、模板编辑、命令面板…）时只放行 Esc：
         浮层里的 Esc 由 Overlay 自己处理并 stopPropagation，这里放行也不会重复触发。
         以前只靠 App 传的 enabled 挡住命令面板/帮助/连接设置，确认框没算在内——
         对话框开着的时候按 1..9 会在背后跳页、按 r 会触发全量刷新，用户在框里
         完全看不到这些动作发生了。 */
      const modalOpen = document.querySelector('[role="dialog"][aria-modal="true"]') !== null;

      for (const binding of bindingsRef.current) {
        if (modalOpen && binding.combo !== 'escape') {
          continue;
        }
        if (inInput && binding.allowInInput !== true) {
          continue;
        }
        const { modifiers, key } = parseCombo(binding.combo);
        const modDown = event.ctrlKey || event.metaKey;

        /* 只要求"写了的修饰键"必须按下 */
        if (modifiers.has('shift') && !event.shiftKey) {
          continue;
        }
        if (modifiers.has('alt') && !event.altKey) {
          continue;
        }
        if (modifiers.has('ctrl') && !event.ctrlKey) {
          continue;
        }
        if (wantsModOf(modifiers)) {
          if (!modDown) {
            continue;
          }
        } else if (modDown) {
          /* 没要求 mod 却按着 Ctrl/Cmd：多半是浏览器自己的快捷键，放行 */
          continue;
        }
        if (event.key.toLowerCase() !== key) {
          continue;
        }
        event.preventDefault();
        binding.handler(event);
        return;
      }
    };

    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [enabled]);
}
