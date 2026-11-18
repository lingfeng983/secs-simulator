import { useMemo, useState } from 'react';
import { Panel } from '../../shared/ui/Panel';
import { ApiError } from '../../api/base';
import type { NoticeWire } from '../../state/useEventStream';
import type { PanelBaseProps } from './types';

/** 一条报警的当前状态（由本会话的 S5F1 通知重放出来）。 */
interface AlarmState {
  alid: number;
  text: string;
  category: number;
  active: boolean;
  at: number;
}

/**
 * 重放本会话的报警通知：后端只推置位/清除事件，不返回活动报警清单。
 * 同一 ALID 只看最后一次，清除了的也留一行，方便看"刚才是哪条报的"。
 */
function replayAlarms(notices: readonly NoticeWire[] | undefined, sessionId: string): AlarmState[] {
  const byAlid = new Map<number, AlarmState>();
  for (const notice of notices ?? []) {
    if (notice.type !== 'alarm' || notice.sessionId !== sessionId) {
      continue;
    }
    const alid = Number(notice.data.alid);
    if (!Number.isFinite(alid) || alid <= 0) {
      continue;
    }
    const category = Number(notice.data.category);
    byAlid.set(alid, {
      alid,
      text: typeof notice.data.text === 'string' ? notice.data.text : '',
      category: Number.isFinite(category) ? category : 0,
      active: notice.data.set === true,
      at: notice.at,
    });
  }
  return [...byAlid.values()].sort((left, right) => right.at - left.at);
}

/** 报警文本会进 SECS-II 的 A 类型，只接受 US-ASCII。 */
function hasNonAscii(text: string): boolean {
  return /[^\x00-\x7F]/.test(text);
}

/**
 * 报警面板：当前活动报警 + 编辑报警并发送 / 压测。
 *
 * <p>后端只给活动报警<b>计数</b>（counters.activeAlarms），明细靠本会话的
 * S5F1 通知重放；页面刷新前的置位不会补发，所以计数大于重放条数是正常的，
 * 界面必须说清楚，而不是把计数当成"一条具体报警"。历史报文去右侧消息监控
 * 看 S5F1/S5F2 更直接。</p>
 */
export function AlarmsPanel({ backend, notifier, liveEvents }: PanelBaseProps): JSX.Element {
  const [alid, setAlid] = useState(1001);
  const [alarmText, setAlarmText] = useState('TEMP HIGH');
  const [alarmCategory, setAlarmCategory] = useState(1);
  /* 报警风暴 */
  const [stormCount, setStormCount] = useState('100');
  const [stormInterval, setStormInterval] = useState('0');
  const [stormRunning, setStormRunning] = useState(false);

  const { activeSession, sessions } = backend;
  const disabled = activeSession === '';
  const alarmRows = useMemo(() => replayAlarms(liveEvents, activeSession), [liveEvents, activeSession]);
  const activeFromNotices = alarmRows.filter((row) => row.active).length;
  const activeCounter = sessions.find((session) => session.id === activeSession)?.counters.activeAlarms ?? 0;

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
      /* 409 = 后端互斥保护：同一设备已有一轮压测/风暴在跑，不是失败。
         译成可操作的提示，而不是抛 "409 Conflict" 这种资源加载错误。 */
      if (error instanceof ApiError && error.status === 409) {
        notifier.info('已有压测正在进行', '请等当前这一轮结束后再点「报警风暴」');
      } else {
        notifier.error(`报警风暴失败: ${(error as Error).message}`);
      }
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
    <div className="panel-stack">
      <Panel id="panel-active-alarms" title="当前活动报警" headless>
        <p className="hint">
          后端计数：<b>{activeCounter}</b> 条活动报警（会话 counters，权威值）。
          下面的明细由本会话收到的 S5F1 通知重放：当前置位 {activeFromNotices} 条，
          后端不返回活动报警清单，页面刷新前的置位不会补发。
        </p>
        {alarmRows.length === 0 ? (
          <p className="hint">
            {activeCounter > 0
              ? `后端报告 ${activeCounter} 条活动报警，但本次会话没有收到 S5F1 通知明细（可能是在页面打开前置位的）。请打开右侧消息监控看 S5F1，或清除后重新置位。`
              : '当前没有活动报警。后端不返回报警明细清单；要看历史 S5F1/S5F2，请打开右侧消息监控。'}
          </p>
        ) : (
          <>
            <table className="effects-table">
              <thead>
                <tr><th>ALID</th><th>状态</th><th>类别</th><th>文本</th><th>时间</th></tr>
              </thead>
              <tbody>
                {alarmRows.map((row) => (
                  <tr key={row.alid}>
                    <td className="mono">{row.alid}</td>
                    <td>
                      <span className={`badge ${row.active ? 'tone-danger' : 'tone-muted'}`}>
                        {row.active ? '活动' : '已清除'}
                      </span>
                    </td>
                    <td>{row.category === 0 ? '—' : row.category}</td>
                    <td className="mono">{row.text || '—'}</td>
                    <td className="mono">{new Date(row.at).toLocaleTimeString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="hint">
              同一 ALID 只显示最后一次置位/清除；与后端计数不一致时以后端计数为准（刷新前的置位没有通知可重放）。
            </p>
          </>
        )}
      </Panel>

      <Panel
        id="panel-alarms"
        title="报警"
        headless
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
              maxLength={120}
              onChange={(event) => setAlarmText(event.target.value)}
              disabled={disabled}
              title="置位/清除在面板右上角：会向主机发送 S5F1（带 W 位，等待 S5F2）。SECS-II 的 A 类型只接受 US-ASCII 字符。"
            />
          </label>
        </div>

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
        </Panel>
    </div>
  );
}
