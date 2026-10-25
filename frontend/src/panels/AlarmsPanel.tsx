import { useState } from 'react';
import type { AlarmState, TerminalMessage } from '../api';
import { Panel, PanelFootnote } from '../ui/Panel';
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
export function AlarmsPanel({ backend, notifier }: PanelBaseProps): JSX.Element {
  const [alid, setAlid] = useState(1001);
  const [alarmText, setAlarmText] = useState('TEMP HIGH');
  const [alarmCategory, setAlarmCategory] = useState(1);
  /* 报警风暴 */
  const [stormCount, setStormCount] = useState('100');
  const [stormInterval, setStormInterval] = useState('0');
  const [stormRunning, setStormRunning] = useState(false);

  const { alarms, terminalMessages, activeSession } = backend;
  const disabled = activeSession === '';

  /** 对编辑好的报警信息做服务端压测:交替置位/清除。 */
  const runStorm = async () => {
    const count = Number.parseInt(stormCount, 10);
    if (!Number.isFinite(count) || count < 1 || count > 5000) {
      notifier.error('次数要在 1..5000 之间');
      return;
    }
    setStormRunning(true);
    try {
      const stats = await backend.burst(activeSession, {
        kind: 'alarm',
        alid,
        text: alarmText,
        category: alarmCategory,
        count,
        intervalMs: Number.parseInt(stormInterval, 10) || 0,
      });
      notifier.info(`报警风暴完成:成功 ${stats.sent} / 失败 ${stats.failed}`);
    } catch (error) {
      notifier.error(`报警风暴失败: ${(error as Error).message}`);
    } finally {
      setStormRunning(false);
    }
  };

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
          title='置位报警（S5F1）'
        >
          置位
        </button>
        <button
          type="button"
          onClick={() => void backend.setAlarm(activeSession, alid, false, alarmText, alarmCategory)}
          disabled={disabled}
          title='清除报警（S5F1）'
        >
          清除
        </button>
      </div>
      <p className="hint">置位/清除会向主机发送 S5F1（带 W 位，等待 S5F2）。</p>

      <div className="toolbar">
        <label className="field">
          风暴次数
          <input value={stormCount} onChange={(event) => setStormCount(event.target.value)} inputMode="numeric" />
        </label>
        <label className="field">
          间隔 ms
          <input value={stormInterval} onChange={(event) => setStormInterval(event.target.value)} inputMode="numeric" title="0 = 全速" />
        </label>
        <button
          type="button"
          className="primary"
          disabled={disabled || stormRunning}
          onClick={() => void runStorm()}
          title="在服务端对上面编辑好的报警交替置位/清除,观察主机侧压力"
        >
          {stormRunning ? '风暴中…' : '报警风暴'}
        </button>
      </div>

      <h3 className="sub-title">活动报警</h3>
      <DataTable
        tableKey="alarms"
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
        tableKey="alarms-2"
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
          <PanelFootnote
        items={[
          { label: '活动报警', value: alarms.length, tone: alarms.length > 0 ? 'warn' : undefined },
          { label: '终端消息', value: terminalMessages.length },
        ]}
      />
    </Panel>
  );
}
