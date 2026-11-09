import { useEffect, useRef, type ReactNode } from 'react';

/**
 * 浮层底座：遮罩 + 焦点圈定。
 *
 * <p>所有浮层（对话框、命令面板、快捷键表）都套这一层：
 * 打开时把焦点移进来、Tab 只在浮层里循环、关闭时还给原来的元素。
 * 每次手写都会漏掉一条，收在这里就统一了。</p>
 */
export function Overlay({
  onClose,
  children,
  labelledBy,
}: {
  onClose: () => void;
  children: ReactNode;
  labelledBy?: string;
}): JSX.Element {
  const panelRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const panel = panelRef.current;

    /* 打开就把焦点送进来：键盘用户不该还在页面里摸 */
    const focusables = () =>
      Array.from(
        panel?.querySelectorAll<HTMLElement>(
          'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
        ) ?? [],
      ).filter((element) => element.offsetParent !== null);
    const first = focusables()[0];
    if (first !== undefined) {
      first.focus();
    } else {
      panel?.focus();
    }

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Tab') {
        return;
      }
      const items = focusables();
      if (items.length === 0) {
        event.preventDefault();
        return;
      }
      const current = document.activeElement;
      const index = items.indexOf(current as HTMLElement);
      const next = event.shiftKey ? index - 1 : index + 1;
      event.preventDefault();
      items[(next + items.length) % items.length]?.focus();
    };

    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      previous?.focus();
    };
  }, []);

  return (
    <div className="overlay" onMouseDown={onClose}>
      <div
        ref={panelRef}
        className="overlay-panel"
        onMouseDown={(event) => event.stopPropagation()}
        tabIndex={-1}
        aria-labelledBy={labelledBy}
      >
        {children}
      </div>
    </div>
  );
}
