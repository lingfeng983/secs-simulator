import { useEffect } from 'react';

export interface ModalProps {
  open: boolean;
  title: string;
  onClose: () => void;
  children: React.ReactNode;
  /** 底部按钮区。 */
  footer?: React.ReactNode;
  width?: string;
}

/**
 * 通用浮层。
 *
 * <p>Esc 关闭、点遮罩关闭、打开时锁住背景滚动——这三条每次手写都会漏一条，
 * 收在这里就统一了。</p>
 */
export function Modal({ open, title, onClose, children, footer, width }: ModalProps): JSX.Element | null {
  useEffect(() => {
    if (!open) {
      return;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        onClose();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.body.style.overflow = previous;
    };
  }, [open, onClose]);

  if (!open) {
    return null;
  }

  return (
    <div className="overlay" onMouseDown={onClose}>
      <div
        className="modal"
        style={width === undefined ? undefined : { width }}
        onMouseDown={(event) => event.stopPropagation()}
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
      </div>
    </div>
  );
}
