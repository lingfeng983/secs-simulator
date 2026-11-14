import { Overlay } from './Overlay';

/** 一条快捷键说明。 */
export interface ShortcutHint {
  keys: string;
  description: string;
}

/**
 * 快捷键总览。
 *
 * <p>快捷键只有"写在页面上"才有人用。
 * 按 ? 打开这张表，比让人猜快得多。</p>
 */
export function ShortcutHelp({ open, onClose, hints }: { open: boolean; onClose: () => void; hints: readonly ShortcutHint[] }): JSX.Element | null {
  if (!open) {
    return null;
  }
  return (
    <Overlay onClose={onClose}>
      <div className="modal shortcut-help" role="dialog" aria-modal="true" aria-label="快捷键">
        <div className="modal-head">
          <h2>快捷键</h2>
          <button type="button" className="modal-close" onClick={onClose} aria-label="关闭">
            ×
          </button>
        </div>
        <div className="modal-body">
          <table className="shortcut-table">
            <tbody>
              {hints.map((hint) => (
                <tr key={hint.keys}>
                  <td className="shortcut-keys">
                    {hint.keys.split('+').map((key, index) => (
                      <kbd key={`${key}-${index}`}>{key}</kbd>
                    ))}
                  </td>
                  <td>{hint.description}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </Overlay>
  );
}
