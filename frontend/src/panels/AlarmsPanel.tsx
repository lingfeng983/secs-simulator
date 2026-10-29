import { useState } from 'react';
import { Panel } from '../ui/Panel';
import type { PanelBaseProps } from './types';

/** 报警文本会进 SECS-II 的 A 类型，只接受 US-ASCII。 */
function hasNonAscii(text: string): boolean {
  return /[^\x00-\x7F]/.test(text);
}

/**
 * 报警面板。
 *
 * <p>只保留"编辑报警并发送 / 压测"这一个能力。活动报警与终端消息的本地快照
 * 没有独立触发点（状态由 S5F2 的异步 ACK 决定），容易造成"接口成功但没数据"
 * 的误解；报警与终端报文是否真的到达主机，去设备「消息」页看 S5F1/S5F2、
 * S10F3/S10F4 更直接。</p>
 */
export function AlarmsPanel({ backend, notifier }: PanelBaseProps): JSX.Element {
  const [alid, setAlid] = useState(1001);
  const [alarmText, setAlarmText] = useState('TEMP HIGH');
  const [alarmCategory, setAlarmCategory] = useState(1);
  /* 报警风暴 */
  const [stormCount, setStormCount] = useState('100');
  const [stormInterval, setStormInterval] = useState('0');
  const [stormRunning, setStormRunning] = useState(false);

  const { activeSession } = backend;
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

  /** 置位/清除同一入口：文本先过 ASCII 校验，避免服务端才报错。 */
  const sendAlarm = (active: boolean) => {
    if (hasNonAscii(alarmText)) {
      notifier.error('报警文本只能包含 US-ASCII 字符', 'SECS-II 的 A 类型不接受中文等非 ASCII 字符');
      return;
    }
    void backend.setAlarm(activeSession, alid, active, alarmText, alarmCategory);
  };

  return (
    <Panel
      id="panel-alarms"
      title="报警"
      description="向主机发送 S5F1（带 W 位，等待 S5F2），可对同一组参数做报警风暴。"
      actions={
        <>
          <button
            type="button"
            onClick={() => sendAlarm(false)}
            disabled={disabled}
            title="清除报警（S5F1，带 W 位）"
          >
            清除报警
          </button>
          <button
            type="button"
            className="primary"
            onClick={() => sendAlarm(true)}
            disabled={disabled}
            title="置位报警（S5F1，带 W 位）"
          >
            置位报警
          </button>
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
            min={1}
            max={7}
            value={alarmCategory}
            onChange={(event) => setAlarmCategory(Math.min(7, Math.max(1, Number(event.target.value) || 1)))}
            disabled={disabled}
            title="E30：类别 1..7（0 无定义）"
          />
        </label>
        <label className="field field-wide">
          报警文本
          <input
            value={alarmText}
            onChange={(event) => setAlarmText(event.target.value)}
            disabled={disabled}
            title="SECS-II 的 A 类型只接受 US-ASCII"
          />
        </label>
      </div>
      <p className="hint">置位/清除在面板右上角：会向主机发送 S5F1（带 W 位，等待 S5F2）。文本只能包含 US-ASCII 字符。</p>

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

      <p className="hint">
        活动报警与终端消息的本地快照已移除（它们没有独立触发点，异步 ACK 失败时会误导）。
        要确认报警/终端是否真的到达主机，去设备「消息」页按「报警」或「终端」类别看
        S5F1/S5F2、S10F3/S10F4；完整记录仍在 <code>logs/messages.jsonl</code>。
      </p>
    </Panel>
  );
}
