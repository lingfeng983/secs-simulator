import { Modal } from './Modal';

/**
 * 确认对话框——危险操作的统一确认入口,替代 window.confirm。
 *
 * <p>danger 时确认按钮用红色;确认后先关浮层再执行动作。</p>
 */
export function ConfirmModal({
  open,
  title,
  message,
  confirmLabel = '确认',
  danger = false,
  onConfirm,
  onClose,
}: {
  open: boolean;
  title: string;
  message: string;
  confirmLabel?: string;
  danger?: boolean;
  onConfirm: () => void;
  onClose: () => void;
}): JSX.Element {
  return (
    <Modal
      open={open}
      title={title}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>
            取消
          </button>
          <button
            type="button"
            className={danger ? 'danger' : 'primary'}
            onClick={() => {
              onClose();
              onConfirm();
            }}
          >
            {confirmLabel}
          </button>
        </>
      }
    >
      <p style={{ margin: 0 }}>{message}</p>
    </Modal>
  );
}
