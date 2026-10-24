import { useState } from 'react';
import type { SessionBulkResult, SessionSummary } from '../api';
import { Panel, PanelFootnote } from '../ui/Panel';
import { DataTable, type Column } from '../ui/DataTable';
import { Modal } from '../ui/Modal';
import { NumberField, TextField } from '../ui/CommitFields';
import { formatCount } from '../lib/format';
import type { PanelBaseProps } from './types';

/** E5 简化控制状态的中文标签。 */
export function controlStateLabel(state: string | undefined): string {
  switch (state) {
    case 'OFFLINE':
      return '离线';
    case 'COMMUNICATING':
      return '通信中';
    case 'ONLINE':
      return '在线';
    default:
      return state ?? '-';
  }
}

/** 控制状态的色调。 */
function controlStateTone(state: string | undefined): 'ok' | 'warn' | 'danger' | 'muted' {
  switch (state) {
    case 'ONLINE':
      return 'ok';
    case 'COMMUNICATING':
      return 'warn';
    case 'OFFLINE':
      return 'danger';
    default:
      return 'muted';
  }
}

/**
 * 会话面板。
 *
 * <p>会话是模拟器的"控制台"：看状态、触发上报、重载、关闭都在这里。
 * 原来这些操作散在表格里且没有过滤，现在可以搜、可以排序、可以导出，
 * 列表长了也不怕。</p>
 */
export function SessionsPanel({ backend, notifier }: PanelBaseProps): JSX.Element {
  const [newConfig, setNewConfig] = useState('demo');
  const [bulkOpen, setBulkOpen] = useState(false);
  const [bulkConfig, setBulkConfig] = useState('');
  const [bulkCount, setBulkCount] = useState(5);
  const [bulkPrefix, setBulkPrefix] = useState('dev-');
  const [bulkPortBase, setBulkPortBase] = useState(5100);
  const [bulkDeviceBase, setBulkDeviceBase] = useState(100);
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [batchBusy, setBatchBusy] = useState(false);
  const [batchProgress, setBatchProgress] = useState<string>('');
  const [bulkBusy, setBulkBusy] = useState(false);
  const [bulkResult, setBulkResult] = useState<SessionBulkResult | null>(null);
  const { sessions, ceid, setCeid, configList, bulkCreateSessions, batchTrigger, batchReload, batchClose } = backend;

  const online = sessions.filter((item) => item.controlState === 'ONLINE').length;

  /* 批量新建预检:后端摘要不带端口,端口冲突只能靠创建后的逐台错误;
     会话 id 冲突可以提前算——这是最常见的重复创建问题 */
  const bulkIds = new Set(sessions.map((item) => item.id));
  const plannedIds = Array.from(
    { length: Math.max(0, Math.min(100, Math.round(bulkCount))) },
    (_, i) => `${bulkPrefix.trim() === '' ? 'dev-' : bulkPrefix.trim()}${i + 1}`,
  );
  const idConflicts = plannedIds.filter((id) => bulkIds.has(id));

  const columns: Column<SessionSummary>[] = [
    {
      key: 'id',
      header: 'id',
      sortValue: (row) => row.id,
      render: (row) => <span className="mono">{row.id}</span>,
    },
    {
      key: 'state',
      header: '状态',
      sortValue: (row) => row.state,
      render: (row) => <span className={`badge badge-${row.state === 'OPEN' ? 'ok' : 'muted'}`}>{row.state}</span>,
    },
    {
      key: 'control',
      header: '控制',
      sortValue: (row) => row.controlState ?? '',
      render: (row) => (
        <span className={`badge badge-${controlStateTone(row.controlState)}`}>
          {controlStateLabel(row.controlState)}
        </span>
      ),
    },
    { key: 'device', header: '设备', sortValue: (row) => row.device },
    {
      key: 'units',
      header: '单元',
      align: 'right',
      sortValue: (row) => row.units,
      render: (row) => formatCount(row.units),
    },
    {
      key: 'emitted',
      header: '已上报',
      align: 'right',
      sortValue: (row) => row.counters.emitted ?? 0,
      render: (row) => formatCount(row.counters.emitted),
    },
    {
      key: 'rejected',
      header: '限流丢弃',
      align: 'right',
      sortValue: (row) => row.counters.rejected ?? 0,
      render: (row) => (
        <span className={row.counters.rejected ? 'badge badge-warn' : undefined}>
          {formatCount(row.counters.rejected)}
        </span>
      ),
    },
    {
      key: 'skipped',
      header: '禁用跳过',
      align: 'right',
      sortValue: (row) => row.counters.skipped ?? 0,
      render: (row) => formatCount(row.counters.skipped),
    },
    {
      key: 's9',
      header: 'S9Fx',
      align: 'right',
      sortValue: (row) => row.counters.s9Replies ?? 0,
      render: (row) =>
        row.counters.s9Replies ? (
          <span className="badge badge-warn" title="主机发了设备不认识的报文，设备回了 S9F3/S9F5">
            {formatCount(row.counters.s9Replies)}
          </span>
        ) : (
          0
        ),
    },
    {
      key: 'trace',
      header: 'S6F1',
      align: 'right',
      sortValue: (row) => row.counters.traceFrames ?? 0,
      render: (row) => formatCount(row.counters.traceFrames),
    },
    {
      key: 'select',
      header: '选择',
      render: (row) => (
        <input
          type="checkbox"
          checked={selected.has(row.id)}
          onChange={(event) => {
            const next = new Set(selected);
            if (event.target.checked) {
              next.add(row.id);
            } else {
              next.delete(row.id);
            }
            setSelected(next);
          }}
          aria-label={`选择 ${row.id}`}
        />
      ),
    },
    {
      key: 'actions',
      header: '操作',
      render: (row) => (
        <div className="row-actions">
          <button
            type="button"
            onClick={() => void backend.trigger(row.id, ceid)}
            title={`手动触发一次 S6F11（CEID ${ceid}）`}
          >
            上报
          </button>
          <button
            type="button"
            onClick={() => void backend.reloadSession(row.id)}
            title='按配置文件重新装载（配置有问题时旧会话不受影响）'
          >
            重载
          </button>
          <button
            type="button"
            className="danger"
            onClick={() => void backend.closeSession(row.id)}
            title='关闭并移除该会话（端口立即解绑）'
          >
            关闭
          </button>
        </div>
      ),
    },
  ];

  return (
    <Panel
      id="panel-sessions"
      title="会话"
      description="模拟器里的设备实例。状态、计数与生命周期操作都在这里。"
    >
      <div className="preview-controls">
        <label className="field field-wide">
          新建会话的配置名
          <input
            value={newConfig}
            onChange={(event) => setNewConfig(event.target.value)}
            placeholder="demo 或 work-dir 下的配置名（.json 可省略）"
          />
        </label>
        <button
          type="button"
          onClick={() => {
            const name = newConfig.trim();
            /* 内部工具别为难人:漏了 .json 就自动补上,后端只认带后缀的文件名 */
            void backend.createSession(name.toLowerCase().endsWith('.json') ? name : `${name}.json`);
          }}
          disabled={newConfig.trim() === ''}
          title='按该配置新建一个会话（后缀 .json 可省略）'
        >
          新建会话
        </button>
        <button type="button" onClick={() => { setBulkResult(null); setBulkOpen(true); }} title='从一份设备配置批量开多台，端口与设备号逐台递增'>
          批量新建
        </button>
        <label className="field">
          CEID
          <input
            type="number"
            min={1}
            value={ceid}
            onChange={(event) => setCeid(Number(event.target.value) || 1)}
          />
        </label>
      </div>

      <DataTable
        tableKey="sessions"
        columns={columns}
        rows={sessions}
        rowKey={(row) => row.id}
        searchPlaceholder="搜索会话 id / 设备 / 状态"
        exportName="sessions"
        emptyText="暂无会话（用 --demo-session / --config 启动，或在上方新建）"
        defaultSort={{ key: 'id', direction: 'asc' }}
        exportColumns={[
          { header: 'id', value: (row) => row.id },
          { header: '状态', value: (row) => row.state },
          { header: '控制', value: (row) => controlStateLabel(row.controlState) },
          { header: '设备', value: (row) => row.device },
          { header: '单元', value: (row) => row.units },
          { header: '已上报', value: (row) => row.counters.emitted ?? 0 },
          { header: '限流丢弃', value: (row) => row.counters.rejected ?? 0 },
          { header: '禁用跳过', value: (row) => row.counters.skipped ?? 0 },
          { header: 'S6F1', value: (row) => row.counters.traceFrames ?? 0 },
          { header: 'S9Fx', value: (row) => row.counters.s9Replies ?? 0 },
        ]}
      />

      <div className="preview-controls">
        <span className="table-count">已选 {selected.size} 台</span>
        {batchBusy && batchProgress !== '' && (
          <span className="table-count">{batchProgress}…</span>
        )}
        <button
          type="button"
          disabled={selected.size === 0 || batchBusy}
          onClick={() => setSelected(new Set(sessions.map((item) => item.id)))}
        >
          全选
        </button>
        <button type="button" disabled={selected.size === 0} onClick={() => setSelected(new Set())}>
          清空选择
        </button>
        <button
          type="button"
          className="primary"
          disabled={selected.size === 0 || batchBusy || ceid < 1}
          title={`对选中的 ${selected.size} 台逐台触发 CEID ${ceid}（S6F11）`}
          onClick={() => {
            setBatchBusy(true);
            void batchTrigger(selected, ceid, (done, total) => setBatchProgress(`上报 ${done}/${total}`))
              .finally(() => { setBatchBusy(false); setBatchProgress(''); });
          }}
        >
          批量上报
        </button>
        <button
          type="button"
          disabled={selected.size === 0 || batchBusy}
          title='对选中的设备逐台重载（配置有问题时旧会话不受影响；主机会断连）'
          onClick={() => {
            setBatchBusy(true);
            void batchReload(selected, (done, total) => setBatchProgress(`重载 ${done}/${total}`))
              .finally(() => { setBatchBusy(false); setBatchProgress(''); });
          }}
        >
          批量重载
        </button>
        <button
          type="button"
          className="danger"
          disabled={selected.size === 0 || batchBusy}
          title='关闭并移除选中的设备（端口立即解绑，不可恢复）'
          onClick={() => {
            if (!window.confirm(`确定关闭选中的 ${selected.size} 台设备？端口会立即解绑。`)) {
              return;
            }
            setBatchBusy(true);
            void batchClose(selected, (done, total) => setBatchProgress(`关闭 ${done}/${total}`))
              .finally(() => {
                setSelected(new Set());
                setBatchBusy(false);
                setBatchProgress('');
              });
          }}
        >
          批量关闭
        </button>
      </div>

      <p className="hint">
        CEID 可在「设备目录」里按名称挑选；配置只认 <code>--work-dir</code> 下的文件名。
        批量操作对勾选的设备逐台执行：上报要设备在线，重载会让主机断连后重连。
      </p>
      <PanelFootnote
        items={[
          { label: '会话数', value: sessions.length },
          { label: '在线', value: online, tone: online > 0 ? 'ok' : undefined },
        ]}
      />

      <Modal
        open={bulkOpen}
        title="批量新建设备"
        onClose={() => setBulkOpen(false)}
        footer={
          <>
            <button type="button" onClick={() => setBulkOpen(false)}>
              关闭
            </button>
            <button
              type="button"
              className="primary"
              disabled={bulkBusy || bulkConfig === '' || bulkCount < 1 || idConflicts.length > 0}
              onClick={() => {
                setBulkBusy(true);
                setBulkResult(null);
                void backend
                  .bulkCreateSessions({
                    config: bulkConfig,
                    count: Math.min(100, Math.max(1, Math.round(bulkCount))),
                    sessionIdPrefix: bulkPrefix.trim() === '' ? 'dev-' : bulkPrefix.trim(),
                    portBase: Math.max(0, Math.round(bulkPortBase)),
                    deviceIdBase: Math.max(0, Math.round(bulkDeviceBase)),
                  })
                  .then((result) => {
                    setBulkResult(result);
                    setBulkBusy(false);
                  });
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
            <NumberField value={bulkPortBase} integer onCommit={setBulkPortBase} />
          </label>
          <label className="field">
            device-id 起始
            <NumberField value={bulkDeviceBase} integer onCommit={setBulkDeviceBase} />
          </label>
        </div>
        <p className="hint">
          第 i 台：会话 id = 前缀 + i，端口 = 起始端口 + i − 1，device-id = 起始 + i − 1。
          设备本体（单元、变量、事件、模板）全部来自所选配置模板；这些连接标识只在运行期覆盖，
          不写回模板文件。端口从 0 或留空表示全部沿用模板端口（多台会因端口冲突而失败）。
        </p>
        {idConflicts.length > 0 && bulkResult === null && (
          <p className="problems">
            会话 id 已存在：{idConflicts.slice(0, 5).join('、')}
            {idConflicts.length > 5 ? ` 等 ${idConflicts.length} 个` : ''}（先关闭或换前缀）
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
                    第 {item.index} 台：{item.error === 'conflict' ? '会话 id 已存在' : item.error === 'failed' ? '端口被占或配置编译失败' : item.error}
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </Modal>
    </Panel>
  );
}
