import { useState } from 'react';
import type { SessionSummary } from '../api';
import { Panel, StatCard } from '../ui/Panel';
import { DataTable, type Column } from '../ui/DataTable';
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
export function SessionsPanel({ backend }: PanelBaseProps): JSX.Element {
  const [newConfig, setNewConfig] = useState('demo');
  const { sessions, canOperate, operateHint, ceid, setCeid } = backend;

  const totalEmitted = sessions.reduce((sum, item) => sum + (item.counters.emitted ?? 0), 0);
  const totalRejected = sessions.reduce((sum, item) => sum + (item.counters.rejected ?? 0), 0);
  const totalSkipped = sessions.reduce((sum, item) => sum + (item.counters.skipped ?? 0), 0);
  const online = sessions.filter((item) => item.controlState === 'ONLINE').length;

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
      key: 'trace',
      header: 'S6F1',
      align: 'right',
      sortValue: (row) => row.counters.traceFrames ?? 0,
      render: (row) => formatCount(row.counters.traceFrames),
    },
    {
      key: 'actions',
      header: '操作',
      render: (row) => (
        <div className="row-actions">
          <button
            type="button"
            onClick={() => void backend.trigger(row.id, ceid)}
            disabled={!canOperate}
            title={canOperate ? `手动触发一次 S6F11（CEID ${ceid}）` : operateHint}
          >
            上报
          </button>
          <button
            type="button"
            onClick={() => void backend.reloadSession(row.id)}
            disabled={!canOperate}
            title={canOperate ? '按配置文件重新装载（配置有问题时旧会话不受影响）' : operateHint}
          >
            重载
          </button>
          <button
            type="button"
            className="danger"
            onClick={() => void backend.closeSession(row.id)}
            disabled={!canOperate}
            title={canOperate ? '关闭并移除该会话（端口立即解绑）' : operateHint}
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
      stats={
        <>
          <StatCard label="会话数" value={sessions.length} />
          <StatCard label="在线" value={online} tone={online === sessions.length && sessions.length > 0 ? 'ok' : undefined} />
          <StatCard label="已上报" value={formatCount(totalEmitted)} />
          <StatCard
            label="丢弃/跳过"
            value={`${formatCount(totalRejected)} / ${formatCount(totalSkipped)}`}
            tone={totalRejected + totalSkipped > 0 ? 'warn' : undefined}
          />
        </>
      }
    >
      <div className="preview-controls">
        <label className="field field-wide">
          新建会话的配置名
          <input
            value={newConfig}
            onChange={(event) => setNewConfig(event.target.value)}
            disabled={!canOperate}
            placeholder="demo 或 work-dir 下的配置文件名"
          />
        </label>
        <button
          type="button"
          onClick={() => void backend.createSession(newConfig)}
          disabled={!canOperate || newConfig.trim() === ''}
          title={canOperate ? '按该配置新建一个会话' : operateHint}
        >
          新建会话
        </button>
        <label className="field">
          CEID
          <input
            type="number"
            min={1}
            value={ceid}
            onChange={(event) => setCeid(Number(event.target.value) || 1)}
            disabled={!canOperate}
          />
        </label>
        <span className="hint">
          配置只认 <code>--work-dir</code> 下的文件名，不接受路径。
        </span>
      </div>

      <DataTable
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
        ]}
      />

      {!canOperate && (
        <p className="hint">当前为只读身份：上报与报警操作需要操作员或管理员。</p>
      )}
      {canOperate && <p className="hint">CEID 可在「设备目录」里按名称挑选，不用背数字。</p>}
    </Panel>
  );
}
