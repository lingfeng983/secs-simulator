import type { ConfigTemplateEntry } from '../../api';
import { Modal } from '../../shared/ui/Modal';
import { FieldLabel } from '../../shared/ui/FieldLabel';

/** 新增设备弹框的输入：单台 / 批量共用一份草稿，批量时用前缀与起始值递增。 */
export interface CreateDeviceDraft {
  name: string;
  host: string;
  port: string;
  deviceId: string;
  passive: string;
  templateId: string;
  region: string;
  newRegion: string;
  /** 「立即启动」开关：true 创建后马上启动会话；false 只保存设备，停在停止态。批量模式恒为启动，不读它。 */
  startNow: boolean;
  /** 批量模式：按同一份设备模板开一批，连接标识逐台递增。 */
  batch: boolean;
  /** 批量台数（1–100），字符串形态与单台字段一致，提交时统一 parse。 */
  count: string;
  /** 会话 id 前缀：第 i 台 = 前缀 + i。 */
  prefix: string;
  /** 起始端口：第 i 台 = 起始 + i − 1。 */
  portBase: string;
  /** Device ID 起始：第 i 台 = 起始 + i − 1。 */
  deviceIdBase: string;
}

/** 批量模式的整数入参：解析失败给 null，由调用方给「数量需要是整数」这类提示。 */
export function parseBatchNumbers(draft: CreateDeviceDraft): {
  count: number; prefix: string; portBase: number; deviceIdBase: number;
} | null {
  const count = Math.round(Number(draft.count));
  const portBase = Math.round(Number(draft.portBase));
  const deviceIdBase = Math.round(Number(draft.deviceIdBase));
  if (!Number.isFinite(count) || !Number.isFinite(portBase) || !Number.isFinite(deviceIdBase)) {
    return null;
  }
  return { count, prefix: draft.prefix.trim(), portBase, deviceIdBase };
}

/** 新增设备弹框：单台/批量一体；一行一个配置项、标题列对齐；说明悬浮在标题上展示。 */
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
  const numbers = draft.batch ? parseBatchNumbers(draft) : null;
  const singleValid = draft.name.trim() !== '' && draft.templateId !== '' && templates.length > 0;
  const batchValid = numbers !== null
    && draft.templateId !== ''
    && templates.length > 0
    && numbers.count >= 1
    && numbers.count <= 100
    && numbers.prefix !== ''
    && !/[/\\"\s]/.test(numbers.prefix)
    && numbers.portBase >= 1
    && numbers.portBase + numbers.count - 1 <= 65535
    && numbers.deviceIdBase >= 0
    && numbers.deviceIdBase + numbers.count - 1 <= 32767;
  const valid = draft.batch ? batchValid : singleValid;
  const count = numbers?.count ?? (Number(draft.count) || 0);
  return (
    <Modal
      open={open}
      title={draft.batch ? '批量新建设备' : '新增设备'}
      width="min(620px, 92vw)"
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>取消</button>
          <button type="button" className="primary" disabled={busy || !valid} onClick={() => onCreate(draft)}>
            {busy ? '创建中…' : draft.batch ? `创建 ${count} 台设备` : '创建设备'}
          </button>
        </>
      }
    >
      {error !== '' && <p className="problems">{error}</p>}
      <div className="modal-form">
        <label className="field modal-field">
          <FieldLabel
            text="批量新建"
            help="打开后按同一份设备模板一次开一批：会话 id、端口、Device ID 按前缀与起始值逐台递增；批量创建的每台创建后立即启动。"
          />
          <span className="check-inline">
            <input
              type="checkbox"
              checked={draft.batch}
              onChange={(event) => patch({ batch: event.target.checked })}
              aria-label="批量新建设备"
            />
            一次创建多台
          </span>
        </label>
        <label className="field modal-field">
          <FieldLabel text="设备模板" help="新建设备从模板生成配置；模板在「模板管理」页维护。" />
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

        {!draft.batch && (
          <label className="field modal-field">
            <FieldLabel
              text="设备名称"
              help="名称同时用作配置文件名与会话 id；端口不能与已有设备重复。"
            />
            <input
              value={draft.name}
              onChange={(event) => patch({ name: event.target.value })}
              placeholder="如 etch-line-a"
              aria-label="设备名称"
            />
          </label>
        )}

        {draft.batch && (
          <>
            <div className="section-head"><h3>批量参数</h3></div>
            <label className="field modal-field">
              <FieldLabel text="数量" help="一次创建的台数（1–100）。" />
              <input
                type="number"
                min={1}
                max={100}
                value={draft.count}
                onChange={(event) => patch({ count: event.target.value })}
                aria-label="批量数量"
              />
            </label>
            <label className="field modal-field">
              <FieldLabel
                text="会话 id 前缀"
                help="第 i 台的会话 id = 前缀 + i（如 dev-1、dev-2）；不能含空白、斜杠或引号。"
              />
              <input
                value={draft.prefix}
                onChange={(event) => patch({ prefix: event.target.value })}
                placeholder="dev-"
                aria-label="会话 id 前缀"
              />
            </label>
            <label className="field modal-field">
              <FieldLabel text="起始端口" help="第 i 台的端口 = 起始端口 + i − 1，逐台递增；端口段不能与已有设备重复。" />
              <input
                type="number"
                min={1}
                max={65535}
                value={draft.portBase}
                onChange={(event) => patch({ portBase: event.target.value })}
                aria-label="起始端口"
              />
            </label>
            <label className="field modal-field">
              <FieldLabel text="Device ID 起始" help="第 i 台的 Device ID = 起始 + i − 1（0–32767）。" />
              <input
                type="number"
                min={0}
                max={32767}
                value={draft.deviceIdBase}
                onChange={(event) => patch({ deviceIdBase: event.target.value })}
                aria-label="Device ID 起始"
              />
            </label>
          </>
        )}

        <div className="section-head"><h3>连接信息</h3></div>
        <label className="field modal-field">
          <FieldLabel text="监听地址" help="被动模式是本机监听地址（0.0.0.0 表示所有网卡）；主动模式是目标主机地址。" />
          <input
            value={draft.host}
            onChange={(event) => patch({ host: event.target.value })}
            placeholder="0.0.0.0"
            aria-label="监听地址"
          />
        </label>
        {!draft.batch && (
          <>
            <label className="field modal-field">
              <FieldLabel text="端口" help="HSMS 端口（1–65535），不能与已有设备重复。" />
              <input
                type="number"
                min={1}
                max={65535}
                value={draft.port}
                onChange={(event) => patch({ port: event.target.value })}
                aria-label="端口"
              />
            </label>
            <label className="field modal-field">
              <FieldLabel text="Device ID" help="HSMS Device ID（0–32767），与主机侧必须一致。" />
              <input
                type="number"
                min={0}
                max={32767}
                value={draft.deviceId}
                onChange={(event) => patch({ deviceId: event.target.value })}
                aria-label="Device ID"
              />
            </label>
          </>
        )}
        <label className="field modal-field">
          <FieldLabel text="连接模式" help="被动监听等主机连入；主动连接由设备去连目标主机。" />
          <select
            value={draft.passive}
            onChange={(event) => patch({ passive: event.target.value })}
            aria-label="连接模式"
          >
            <option value="true">被动监听（等主机连入）</option>
            <option value="false">主动连接（连目标主机）</option>
          </select>
        </label>
        {!draft.batch && (
          <label className="field modal-field">
            <FieldLabel text="立即启动" help="勾选后创建即启动会话；不勾选则只保存设备，之后在清单里点「启动」。" />
            <span className="check-inline">
              <input
                type="checkbox"
                checked={draft.startNow}
                onChange={(event) => patch({ startNow: event.target.checked })}
                aria-label="立即启动设备"
              />
              创建后立即启动
            </span>
          </label>
        )}

        <div className="section-head"><h3>区域分组</h3></div>
        <label className="field modal-field">
          <FieldLabel text="所属区域" help={draft.batch ? '这一批设备全部归入的区域分组，用于工作台筛选与侧栏归类。' : '设备归入的区域分组，用于工作台筛选与侧栏归类。'} />
          <select
            value={draft.region}
            onChange={(event) => patch({ region: event.target.value })}
            aria-label="所属区域"
          >
            {regionNames.map((name) => <option key={name} value={name}>{name}</option>)}
          </select>
        </label>
        <label className="field modal-field">
          <FieldLabel text="新建区域（可选）" help="填了就新建一个区域并把设备归入，优先于上面选择的区域。" />
          <input
            value={draft.newRegion}
            onChange={(event) => patch({ newRegion: event.target.value })}
            placeholder="填了就建新区域并归入"
            aria-label="新建区域"
          />
        </label>
      </div>
    </Modal>
  );
}
