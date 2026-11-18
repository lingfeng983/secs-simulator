import { useEffect, useRef, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

/** 打开中的浮层（先开在前）；Esc/Tab 只由最上层处理，嵌套弹窗按 Esc 不会把外层一起关掉。 */
const openOverlays: object[] = [];

/**
 * 浮层底座：遮罩 + 焦点圈定。
 *
 * <p>所有浮层（对话框、命令面板、快捷键表）都套这一层：
 * 打开时把焦点移进来、Tab 只在浮层里循环、关闭时还给原来的元素。
 * 每次手写都会漏掉一条，收在这里就统一了。</p>
 *
 * <p>整层 portal 到 body：调用方经常在带层叠上下文的容器里（例如右侧消息
 * 监控抽屉 z-index 45），浮层挂在那种容器里会连同按钮一起被压住——
 * 无论浮层自己写多大的 z-index 都救不回来。挂到 body 后才和抽屉平级，
 * 由全局的层级阶梯（抽屉 45 &lt; 遮罩 80 &lt; 弹窗 90 &lt; toast 100）决定先后。</p>
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

    const token = {};
    openOverlays.push(token);

    const onKeyDown = (event: KeyboardEvent) => {
      if (openOverlays[openOverlays.length - 1] !== token) {
        return;
      }
      if (event.key === 'Escape') {
        /* 浮层统一在这里响应 Esc：快捷键表、命令面板之外的浮层以前都没接 */
        event.stopPropagation();
        onClose();
        return;
      }
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
      openOverlays.splice(openOverlays.indexOf(token), 1);
      document.removeEventListener('keydown', onKeyDown);
      previous?.focus();
    };
  }, []);

  return createPortal(
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
    </div>,
    document.body,
  );
}
