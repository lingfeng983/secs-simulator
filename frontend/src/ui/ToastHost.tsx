import type { Toast } from '../state/useToasts';

/** 提示条的呈现。 */
export interface ToastHostProps {
  toasts: readonly Toast[];
  onDismiss: (id: number) => void;
  onDismissAll: () => void;
}

const KIND_LABEL: Record<Toast['kind'], string> = {
  success: '成功',
  error: '失败',
  warn: '注意',
  info: '提示',
};

/**
 * 提示条容器。
 *
 * <p>固定在右下角：用户刚点完按钮，视线还在那一块，
 * 反馈就该从那儿冒出来，而不是安静地跑到页面最底下。</p>
 */
export function ToastHost({ toasts, onDismiss, onDismissAll }: ToastHostProps): JSX.Element | null {
  if (toasts.length === 0) {
    return null;
  }
  return (
    <div className="toast-host" role="status" aria-live="polite">
      {toasts.length > 1 && (
        <div className="toast-bulk">
          <button type="button" onClick={onDismissAll}>
            全部清除（{toasts.length}）
          </button>
        </div>
      )}
      {toasts.map((toast) => (
        <div key={toast.id} className={`toast toast-${toast.kind}`}>
          <div className="toast-head">
            <span className="toast-kind">{KIND_LABEL[toast.kind]}</span>
            <span className="toast-title">{toast.title}</span>
            <button type="button" className="toast-close" onClick={() => onDismiss(toast.id)} aria-label="关闭">
              ×
            </button>
          </div>
          {toast.detail !== undefined && toast.detail !== '' && (
            <p className="toast-detail">{toast.detail}</p>
          )}
        </div>
      ))}
    </div>
  );
}
