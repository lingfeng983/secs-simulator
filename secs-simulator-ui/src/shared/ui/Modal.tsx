import { useEffect, useState } from 'react';
import type { PointerEvent as ReactPointerEvent } from 'react';
import { Overlay } from './Overlay';

export interface ModalProps {
  open: boolean;
  title: string;
  onClose: () => void;
  children: React.ReactNode;
  /** 底部按钮区。 */
  footer?: React.ReactNode;
  width?: string;
}

/** 八个方向的拖拽手柄；className 的后缀决定拖哪个边/角。 */
const RESIZE_DIRECTIONS = ['n', 's', 'e', 'w', 'ne', 'nw', 'se', 'sw'] as const;
type ResizeDirection = (typeof RESIZE_DIRECTIONS)[number];

const MIN_WIDTH = 360;
const MIN_HEIGHT = 220;

/**
 * 通用对话框。
 *
 * <p>Esc 关闭、点遮罩关闭、打开时锁住背景滚动、焦点留在对话框里——
 * 这四条每次手写都会漏一条，收在这里就统一了。</p>
 *
 * <p>四边四角都可拖拽改变大小：配置/事件这类表单字段多，固定 82vh
 * 常常看不到底部，允许放大比塞滚动条更直接。</p>
 */
export function Modal({ open, title, onClose, children, footer, width }: ModalProps): JSX.Element | null {
  /** 手动拖出来的尺寸；null = 用 CSS 默认。关闭后重置。 */
  const [size, setSize] = useState<{ width: number; height: number } | null>(null);

  useEffect(() => {
    if (!open) {
      setSize(null);
    }
  }, [open, title]);

  useEffect(() => {
    if (!open) {
      return;
    }
    /* Esc 关闭由 Overlay 统一处理；这里只管锁背景滚动 */
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
    };
  }, [open, onClose]);

  if (!open) {
    return null;
  }

  const startResize = (direction: ResizeDirection) => (event: ReactPointerEvent<HTMLSpanElement>) => {
    event.preventDefault();
    event.stopPropagation();
    const modal = event.currentTarget.closest('.modal');
    if (!(modal instanceof HTMLElement)) {
      return;
    }
    const rect = modal.getBoundingClientRect();
    const start = { x: event.clientX, y: event.clientY, width: rect.width, height: rect.height };

    const onMove = (move: PointerEvent) => {
      let nextWidth = start.width;
      let nextHeight = start.height;
      if (direction.includes('e')) {
        nextWidth = start.width + (move.clientX - start.x);
      }
      if (direction.includes('w')) {
        nextWidth = start.width - (move.clientX - start.x);
      }
      if (direction.includes('s')) {
        nextHeight = start.height + (move.clientY - start.y);
      }
      if (direction.includes('n')) {
        nextHeight = start.height - (move.clientY - start.y);
      }
      setSize({
        width: Math.round(Math.max(MIN_WIDTH, Math.min(window.innerWidth - 40, nextWidth))),
        height: Math.round(Math.max(MIN_HEIGHT, Math.min(window.innerHeight - 40, nextHeight))),
      });
    };
    const onUp = () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
    };
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
  };

  return (
    <Overlay onClose={onClose}>
      <div
        className="modal"
        style={{
          ...(width === undefined ? {} : { width }),
          ...(size === null ? {} : { width: size.width, height: size.height, maxHeight: 'none' }),
        }}
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        <div className="modal-head">
          <h2>{title}</h2>
          <button type="button" className="modal-close" onClick={onClose} aria-label="关闭">
            ×
          </button>
        </div>
        <div className="modal-body">{children}</div>
        {footer !== undefined && <div className="modal-foot">{footer}</div>}
        {RESIZE_DIRECTIONS.map((direction) => (
          <span
            key={direction}
            className={`modal-resize ${direction}`}
            role="separator"
            aria-label={`调整对话框大小（${direction}）`}
            title="拖动调整对话框大小"
            onPointerDown={startResize(direction)}
          />
        ))}
      </div>
    </Overlay>
  );
}
