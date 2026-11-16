import { Modal } from '../../shared/ui/Modal';

/** 批量同步配置弹框：只填起始端口/设备号，配置内容由面板里的同步逻辑生成。 */
export function SyncConfigModal({
  open,
  sessionIds,
  portBase,
  deviceBase,
  busy,
  onPortBaseChange,
  onDeviceBaseChange,
  onClose,
  onConfirm,
}: {
  open: boolean;
  sessionIds: readonly string[];
  portBase: number;
  deviceBase: number;
  busy: boolean;
  onPortBaseChange: (value: number) => void;
  onDeviceBaseChange: (value: number) => void;
  onClose: () => void;
  onConfirm: () => void;
}): JSX.Element {
  return (
    <Modal
      open={open}
      title="批量同步配置"
      hint={`以选中的第一台（${sessionIds[0] ?? '—'}）为原始配置，把设备/单元/SVID/报告/事件/模板复制到其余 ${Math.max(0, sessionIds.length - 1)} 台选中设备；只改各自的会话 id、端口、设备号与设备名，其余配置保持一致。`}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>取消</button>
          <button type="button" className="primary" disabled={busy} onClick={onConfirm}>
            {busy ? '同步中…' : '开始同步'}
          </button>
        </>
      }
    >
      <div className="form-row">
        <label className="field">
          起始端口
          <input
            type="number"
            min={1}
            value={portBase}
            onChange={(event) => onPortBaseChange(Number(event.target.value) || 5100)}
          />
        </label>
        <label className="field">
          起始 Device ID
          <input
            type="number"
            min={0}
            value={deviceBase}
            onChange={(event) => onDeviceBaseChange(Number(event.target.value) || 0)}
          />
        </label>
      </div>
    </Modal>
  );
}