import type { ConfigTemplateEntry } from '../../api';
import { Modal } from '../../shared/ui/Modal';

/** 新增设备弹框的输入：链接信息、设备模板与区域一次填完。 */
export interface CreateDeviceDraft {
  name: string;
  host: string;
  port: string;
  deviceId: string;
  passive: string;
  templateId: string;
  region: string;
  newRegion: string;
}

/** 新增设备弹框：链接信息 + 设备模板 + 区域，一次填完再建。 */
export function CreateDeviceModal({
  open,
  draft,
  templates,
  regionNames,
  busy,
  error,
  onChange,
  onClose,
  onCreate,
}: {
  open: boolean;
  draft: CreateDeviceDraft;
  templates: readonly ConfigTemplateEntry[];
  regionNames: readonly string[];
  busy: boolean;
  error: string;
  onChange: (draft: CreateDeviceDraft) => void;
  onClose: () => void;
  onCreate: (draft: CreateDeviceDraft) => void;
}): JSX.Element | null {
  const patch = (part: Partial<CreateDeviceDraft>) => onChange({ ...draft, ...part });
  const valid = draft.name.trim() !== '' && draft.templateId !== '' && templates.length > 0;
  return (
    <Modal
      open={open}
      title="新增设备"
      width="min(620px, 92vw)"
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>取消</button>
          <button type="button" className="primary" disabled={busy || !valid} onClick={() => onCreate(draft)}>
            {busy ? '创建中…' : '创建设备'}
          </button>
        </>
      }
    >
      {error !== '' && <p className="problems">{error}</p>}
      <div className="form-row">
        <label className="field">
          设备名称
          <input
            value={draft.name}
            onChange={(event) => patch({ name: event.target.value })}
            placeholder="如 etch-line-a"
            aria-label="设备名称"
          />
        </label>
        <label className="field">
          设备模板
          <select
            value={draft.templateId}
            onChange={(event) => patch({ templateId: event.target.value })}
            aria-label="新建设备使用的模板"
          >
            {templates.length === 0 && <option value="">（还没有模板）</option>}
            {templates.map((item) => (
              <option key={item.id} value={item.id}>
                {item.label}（{item.id}）{item.source === 'custom' ? ' · 自定义' : ''}
              </option>
            ))}
          </select>
        </label>
      </div>

      <div className="section-head"><h3>链接信息</h3></div>
      <div className="form-row">
        <label className="field">
          监听地址
          <input
            value={draft.host}
            onChange={(event) => patch({ host: event.target.value })}
            placeholder="0.0.0.0"
            aria-label="监听地址"
          />
        </label>
        <label className="field">
          端口
          <input
            type="number"
            min={1}
            max={65535}
            value={draft.port}
            onChange={(event) => patch({ port: event.target.value })}
            aria-label="端口"
          />
        </label>
        <label className="field">
          Device ID
          <input
            type="number"
            min={0}
            max={32767}
            value={draft.deviceId}
            onChange={(event) => patch({ deviceId: event.target.value })}
            aria-label="Device ID"
          />
        </label>
        <label className="field">
          连接模式
          <select
            value={draft.passive}
            onChange={(event) => patch({ passive: event.target.value })}
            aria-label="连接模式"
          >
            <option value="true">被动监听（等主机连入）</option>
            <option value="false">主动连接（连目标主机）</option>
          </select>
        </label>
      </div>

      <div className="section-head"><h3>区域分组</h3></div>
      <div className="form-row">
        <label className="field">
          所属区域
          <select
            value={draft.region}
            onChange={(event) => patch({ region: event.target.value })}
            aria-label="所属区域"
          >
            {regionNames.map((name) => <option key={name} value={name}>{name}</option>)}
          </select>
        </label>
        <label className="field">
          新建区域（可选）
          <input
            value={draft.newRegion}
            onChange={(event) => patch({ newRegion: event.target.value })}
            placeholder="填了就建新区域并归入"
            aria-label="新建区域"
          />
        </label>
      </div>
      <p className="hint">名称同时用作配置文件名与会话 id；端口不能与已有设备重复。</p>
    </Modal>
  );
}