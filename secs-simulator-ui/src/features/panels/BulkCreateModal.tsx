import { useState } from 'react';
import type { ConfigEntry, ConfigTemplateEntry, SessionBulkResult, SessionSummary } from '../../api';
import type { BackendController } from '../../state/useBackend';
import { Modal } from '../../shared/ui/Modal';
import { NumberField, TextField } from '../../shared/ui/CommitFields';

/** 批量新建设备弹框：按模板配置开一批带不同会话 id / 端口 / 设备号的设备。 */
export function BulkCreateModal({
  open,
  configList,
  sessions,
  backend,
  onClose,
}: {
  open: boolean;
  configList: ConfigEntry[];
  sessions: SessionSummary[];
  backend: BackendController;
  onClose: () => void;
}): JSX.Element {
  const [bulkConfig, setBulkConfig] = useState('');
  const [bulkCount, setBulkCount] = useState(5);
  const [bulkPrefix, setBulkPrefix] = useState('dev-');
  const [bulkPortBase, setBulkPortBase] = useState(5100);
  const [bulkDeviceBase, setBulkDeviceBase] = useState(0);
  const [bulkBusy, setBulkBusy] = useState(false);
  const [bulkResult, setBulkResult] = useState<SessionBulkResult | null>(null);
  const [bulkError, setBulkError] = useState('');
  /* 内置模板目录：沿用原面板的占位空列表（未接线，不改变既有行为） */
  const [configTemplates] = useState<ConfigTemplateEntry[]>([]);
  const [bulkTplId, setBulkTplId] = useState('etch');
  const [bulkTplName, setBulkTplName] = useState('');
  const [bulkTplBusy, setBulkTplBusy] = useState(false);

  /* 批量新建预检:会话 id 冲突可以提前算——这是最常见的重复创建问题 */
  const bulkIds = new Set(sessions.map((item) => item.id));
  const plannedIds = Array.from(
    { length: Math.max(0, Math.min(100, Math.round(bulkCount))) },
    (_, i) => `${bulkPrefix.trim() === '' ? 'dev-' : bulkPrefix.trim()}${i + 1}`,
  );
  const idConflicts = plannedIds.filter((id) => bulkIds.has(id));

  return (
    <Modal
      open={open}
      title="批量新建设备"
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>
            关闭
          </button>
          <button
            type="button"
            className="primary"
            disabled={bulkBusy || bulkConfig === '' || bulkCount < 1 || idConflicts.length > 0}
            onClick={() => {
              setBulkBusy(true);
              setBulkResult(null);
              setBulkError('');
              void backend
                .bulkCreateSessions({
                  config: bulkConfig,
                  count: Math.min(100, Math.max(1, Math.round(bulkCount))),
                  sessionIdPrefix: bulkPrefix.trim() === '' ? 'dev-' : bulkPrefix.trim(),
                  portBase: Math.max(1, Math.round(bulkPortBase)),
                  deviceIdBase: Math.max(0, Math.round(bulkDeviceBase)),
                })
                .then((result) => {
                  setBulkResult(result);
                })
                .catch((error: unknown) => {
                  /* 失败也要复位按钮，否则永久停在「创建中…」且拒绝无人接收 */
                  setBulkError((error as Error).message);
                })
                .finally(() => setBulkBusy(false));
            }}
          >
            {bulkBusy ? '创建中…' : '开始创建'}
          </button>
        </>
      }
    >
      <div className="form-row">
        <label className="field">
          设备配置模板
          <select value={bulkConfig} onChange={(event) => setBulkConfig(event.target.value)}>
            <option value="">（选择配置）</option>
            {configList.map((entry) => (
              <option key={entry.name} value={entry.name}>
                {entry.name}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          数量（1–100）
          <NumberField value={bulkCount} integer onCommit={setBulkCount} />
        </label>
        <label className="field">
          会话 id 前缀
          <TextField value={bulkPrefix} onCommit={setBulkPrefix} />
        </label>
        <label className="field">
          起始端口
          <NumberField value={bulkPortBase} integer onCommit={(next) => setBulkPortBase(Math.max(1, next))} />
        </label>
        <label className="field">
          device-id 起始
          <NumberField value={bulkDeviceBase} integer onCommit={setBulkDeviceBase} />
        </label>
      </div>
      <div className="form-row">
        <label className="field">
          从内置设备模板建一份
          <select value={bulkTplId} onChange={(event) => setBulkTplId(event.target.value)}>
            {configTemplates.map((entry) => (
              <option key={entry.id} value={entry.id}>
                {entry.label}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          新配置名
          <TextField value={bulkTplName} onCommit={setBulkTplName} placeholder="如 etch-line.json" />
        </label>
        <button
          type="button"
          disabled={bulkTplBusy || bulkTplName.trim() === '' || !bulkTplName.trim().toLowerCase().endsWith('.json')}
          title="新建一份配置文件并选为下面的设备配置模板"
          onClick={() => {
            setBulkTplBusy(true);
            void backend
              .createConfig(bulkTplName.trim(), bulkTplId)
              .then((status) => {
                if (status === 201) {
                  setBulkConfig(bulkTplName.trim());
                  setBulkTplName('');
                }
              })
              .finally(() => setBulkTplBusy(false));
          }}
        >
          {bulkTplBusy ? '创建中…' : '建并选用'}
        </button>
      </div>
      <p className="hint">
        第 i 台:会话 id = 前缀 + i,端口 = 起始端口 + i − 1,device-id = 起始 + i − 1。
        设备本体(单元、变量、事件、模板)全部来自所选配置模板;这些连接标识只在运行期覆盖,
        不写回模板文件。起始端口必填(≥1),每台递增;模板里写了端口的也可留作单一设备使用。
      </p>
      {bulkError !== '' && <p className="problems">批量创建失败:{bulkError}</p>}
      {idConflicts.length > 0 && bulkResult === null && (
        <p className="problems">
          会话 id 已存在:{idConflicts.slice(0, 5).join('、')}
          {idConflicts.length > 5 ? ` 等 ${idConflicts.length} 个` : ''}(先关闭或换前缀)
        </p>
      )}
      {bulkResult !== null && (
        <div className="template-card">
          <strong>已创建 {bulkResult.created.length} 台</strong>
          {bulkResult.created.length > 0 && (
            <p className="hint mono">{bulkResult.created.join('、')}</p>
          )}
          {bulkResult.failed.length > 0 && (
            <ul className="problems">
              {bulkResult.failed.map((item) => (
                <li key={item.index}>
                  第 {item.index} 台:{item.error === 'conflict' ? '会话 id 已存在' : item.error === 'failed' ? '端口被占或配置编译失败' : item.error}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </Modal>
  );
}