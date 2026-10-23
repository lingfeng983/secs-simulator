import { useState } from 'react';
import type { AlarmState, TerminalMessage } from '../api';
import { Panel, StatCard } from '../ui/Panel';
import { DataTable, type Column } from '../ui/DataTable';
import { formatClock, formatRelative } from '../lib/format';
import type { PanelBaseProps } from './types';

/**
 * 报警面板。
 *
 * <p>置位/清除会向主机发 S5F1（带 W 位，等 S5F2）。
 * 活动报警和终端消息都是"现在"的快照，后端只保留最近若干条，
 * 所以这里也说明了保留上限，免得用户以为消息丢了。</p>
 */
export function AlarmsPanel({ backend }: PanelBaseProps): JSX.Element {
  const [alid, setAlid] = useState(1001);
  const [alarmText, setAlarmText] = useState('TEMP HIGH');
  const [alarmCategory, setAlarmCategory] = useState(1);

  const { alarms, terminalMessages, activeSession, canOperate, operateHint, role } = backend;
  const disabled = !canOperate || activeSession === '';

  const alarmColumns: Column<AlarmState>[] = [
    { key: 'alid', header: 'ALID', align: 'right', sortValue: (row) => row.alid, render: (row) => row.alid },
    { key: 'category', header: '类别', align: 'right', sortValue: (row) => row.category, render: (row) => row.category },
    { key: 'text', header: '文本', sortValue: (row) => row.text, render: (row) => row.text },
    {
      key: 'at',
      header: '置位时间',
      sortValue: (row) => row.atMillis,
      render: (row) => (
        <span title={new Date(row.atMillis).toLocaleString()}>{formatClock(row.atMillis)}</span>
      ),
    },
    {
      key: 'ago',
      header: '距今',
      sortValue: (row) => row.atMillis,
      render: (row) => formatRelative(row.atMillis),
    },
  ];

  const terminalColumns: Column<TerminalMessage>[] = [
    { key: 'tid', header: 'TID', align: 'right', sortValue: (row) => row.tid, render: (row) => row.tid },
    { key: 'text', header: '文本', sortValue: (row) => row.text, render: (row) => row.text },
    {
      key: 'at',
      header: '收到时间',
      sortValue: (row) => row.atMillis,
      render: (row) => (
        <span title={new Date(row.atMillis).toLocaleString()}>{formatClock(row.atMillis)}</span>
      ),
    },
  ];

  return (
    <Panel
      id="panel-alarms"
      title="报警"
      description="向主机发送 S5F1（带 W 位，等待 S5F2），并查看活动报警与终端消息。"
      stats={
        <>
          <StatCard label="活动报警" value={alarms.length} tone={alarms.length > 0 ? 'warn' : 'ok'} />
          <StatCard label="终端消息" value={terminalMessages.length} />
        </>
      }
    >
      <div className="preview-controls">
        <label className="field">
          ALID
          <input
            type="number"
            min={1}
            value={alid}
            onChange={(event) => setAlid(Number(event.target.value) || 1)}
            disabled={disabled}
          />
        </label>
        <label className="field">
          类别
          <input
            type="number"
            min={0}
            max={7}
            value={alarmCategory}
            onChange={(event) => setAlarmCategory(Number(event.target.value) || 0)}
            disabled={disabled}
          />
        </label>
        <label className="field field-wide">
          报警文本
          <input value={alarmText} onChange={(event) => setAlarmText(event.target.value)} disabled={disabled} />
        </label>
        <button
          type="button"
          onClick={() => void backend.setAlarm(activeSession, alid, true, alarmText, alarmCategory)}
          disabled={disabled}
          title={canOperate ? '置位报警（S5F1）' : operateHint}
        >
          置位
        </button>
        <button
          type="button"
          onClick={() => void backend.setAlarm(activeSession, alid, false, alarmText, alarmCategory)}
          disabled={disabled}
          title={canOperate ? '清除报警（S5F1）' : operateHint}
        >
          清除
        </button>
      </div>
      <p className="hint">
        置位/清除会向主机发送 S5F1（带 W 位，等待 S5F2）。
        {canOperate
          ? ''
          : ` 当前${role === undefined ? '身份未确认' : `角色为${roleLabelOf(role)}`}，操作已禁用。`}
      </p>

      <h3 className="sub-title">活动报警</h3>
      <DataTable
        columns={alarmColumns}
        rows={alarms}
        rowKey={(row) => String(row.alid)}
        searchPlaceholder="搜索 ALID / 文本"
        exportName="alarms"
        emptyText="暂无活动报警"
        exportColumns={[
          { header: 'ALID', value: (row) => row.alid },
          { header: '类别', value: (row) => row.category },
          { header: '文本', value: (row) => row.text },
          { header: '置位时间', value: (row) => new Date(row.atMillis).toISOString() },
        ]}
      />

      <h3 className="sub-title">终端消息</h3>
      <DataTable
        columns={terminalColumns}
        rows={terminalMessages}
        rowKey={(row) => `${row.atMillis}-${row.tid}`}
        searchPlaceholder="搜索 TID / 文本"
        exportName="terminal-messages"
        emptyText="暂无终端消息"
        exportColumns={[
          { header: 'TID', value: (row) => row.tid },
          { header: '文本', value: (row) => row.text },
          { header: '收到时间', value: (row) => new Date(row.atMillis).toISOString() },
        ]}
      />
    </Panel>
  );
}

/** 角色中文标签（避免为了一个标签再引一遍 api 模块的循环依赖）。 */
function roleLabelOf(role: string): string {
  switch (role) {
    case 'viewer':
      return '只读';
    case 'operator':
      return '操作员';
    case 'admin':
      return '管理员';
    default:
      return '未知';
  }
}
