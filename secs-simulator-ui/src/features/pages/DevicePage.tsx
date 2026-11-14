import { useEffect, useLayoutEffect, useMemo, useState } from 'react';
import type { PanelBaseProps } from '../panels/types';
import {
  DEVICE_STATUS_LABEL,
  DEVICE_STATUS_TONE,
  deviceStatus,
  deviceStatusHint,
} from '../../shared/lib/deviceStatus';
import { EventsPanel } from '../panels/EventsPanel';
import { CeidPanel } from '../panels/CeidPanel';
import { AlarmsPanel } from '../panels/AlarmsPanel';
import { SvidPanel } from '../panels/SvidPanel';
import { ConfigPanel } from '../panels/ConfigPanel';
import { ConnectionPanel } from '../panels/ConnectionPanel';
import { StructurePanel } from '../panels/StructurePanel';
import { SendPanel } from '../panels/SendPanel';
import { LiveEventList } from '../../shared/ui/LiveEventList';
import { InstanceHead, SubTabs, ZoneSwitch } from '../../shared/ui/zone';
import {
  DEVICE_CONFIG_PAGES,
  DEVICE_CONFIG_PAGE_LABELS,
  DEVICE_RUNTIME_PAGES,
  DEVICE_RUNTIME_PAGE_LABELS,
  type DevicePage,
  type DeviceZone,
} from '../../app/router';

/**
 * 单设备详情页——两段式（设计 §3.2）：实例头 + 配置区 / 运行区。
 * 配置区改「这台设备是什么」（保存后需重载），运行区操作当前运行。
 * 进入设备页就把该会话钉为全局 activeSession——现有面板全部按
 * "当前会话"取数，钉住之后零改动复用。
 */
export function DevicePage({
  sessionId,
  zone,
  page,
  backend,
  notifier,
  preferences,
  updatePreferences,
  goTo,
  liveEvents,
  messages,
}: PanelBaseProps & { sessionId: string; zone: DeviceZone; page: DevicePage }): JSX.Element {
  const summary = backend.sessions.find((session) => session.id === sessionId);
  const [busy, setBusy] = useState(false);

  /* 四态判据统一走 deviceStatus：设备页头、侧栏、设备清单必须说同一句话 */
  const status = deviceStatus(summary ?? {});

  const startDevice = () => {
    setBusy(true);
    void backend.connectSession(sessionId).finally(() => setBusy(false));
  };
  const stopDevice = () => {
    setBusy(true);
    void backend.closeSession(sessionId).finally(() => setBusy(false));
  };
  /* 上线/下线只动 GEM 控制状态，不动链路：等价于主机发 S1F17 / S1F15 */
  const setOnline = (online: boolean) => {
    setBusy(true);
    void backend.setDeviceOnline(sessionId, online).finally(() => setBusy(false));
  };

  /* 进入设备页就把全局会话钉到这台设备：所有面板按 activeSession 取数。
     必须是 layout effect —— 面板的写操作闭包在 hook 创建时就捕获了当时的
     activeSession，用 useEffect 的话会先画出一帧（还指向上一台设备），
     这一帧里用户如果正好点「删除 SVID / 删除报告」，动的就是 A 设备的数据。 */
  const setActiveSession = backend.setActiveSession;
  const activeSession = backend.activeSession;
  useLayoutEffect(() => {
    if (summary !== undefined && activeSession !== sessionId) {
      setActiveSession(sessionId);
    }
  }, [activeSession, setActiveSession, sessionId, summary]);

  const baseProps: PanelBaseProps = {
    backend,
    notifier,
    preferences,
    updatePreferences,
    goTo,
    liveEvents,
    messages,
  };

  const deviceEvents = (liveEvents ?? []).filter(
    (notice) => notice.type === 's6f11' && notice.sessionId === sessionId,
  );

  /* 会话不存在（被关掉、或手输/收藏了旧地址）时不要继续渲染面板：
     面板按 activeSession 取数，会把"上一台设备"的数据挂在这个不存在的 id 下。 */
  const sessionKnown = backend.sessions.length === 0 || summary !== undefined;
  if (!sessionKnown) {
    return (
      <div className="panel-stack">
        <div className="empty-state">
          <p className="empty-text">设备 {sessionId} 不存在</p>
          <p className="empty-hint">它可能已被删除。回到设备清单重新选一台设备。</p>
          <div className="empty-action">
            <button type="button" onClick={() => goTo?.('workbench')}>
              ← 设备清单
            </button>
          </div>
        </div>
      </div>
    );
  }

  const pages = zone === 'config'
    ? DEVICE_CONFIG_PAGES.map((id) => ({ id, label: DEVICE_CONFIG_PAGE_LABELS[id] }))
    : DEVICE_RUNTIME_PAGES.map((id) => ({ id, label: DEVICE_RUNTIME_PAGE_LABELS[id] }));

  return (
    <div className="panel-stack">
      <InstanceHead
        title={summary?.device ?? sessionId}
        badge={
          <span className={`badge tone-${DEVICE_STATUS_TONE[status]}`} title={deviceStatusHint(status)}>
            {DEVICE_STATUS_LABEL[status]}
          </span>
        }
        subtitle={
          <>
            <span className="mono">{sessionId}</span> · {DEVICE_STATUS_LABEL[status]}
            {summary?.units !== undefined ? ` · ${summary.units} 个单元` : ''}
            {summary?.lastError ? <span className="danger"> · {summary.lastError}</span> : ''}
          </>
        }
        metrics={[
          { label: '设备状态', value: DEVICE_STATUS_LABEL[status] },
          { label: '单元数', value: summary?.units ?? '—' },
          { label: '累计上报', value: summary?.counters.emitted ?? '—' },
        ]}
        actions={
          <>
            {status === 'stopped' ? (
              <button
                type="button"
                className="primary"
                disabled={busy}
                onClick={startDevice}
                title="启动端口监听：主机随后可以连上来"
              >
                启动设备
              </button>
            ) : (
              <button
                type="button"
                className="danger"
                disabled={busy}
                onClick={stopDevice}
                title="停止端口监听：链路断开、端口释放，设备仍保留在设备清单里"
              >
                停止设备
              </button>
            )}
            {status === 'offline' && (
              <button
                type="button"
                className="primary"
                disabled={busy}
                onClick={() => setOnline(true)}
                title="设备上线（等价主机发 S1F17）：上线后事件才按规则上报"
              >
                上线
              </button>
            )}
            {status === 'online' && (
              <button
                type="button"
                disabled={busy}
                onClick={() => setOnline(false)}
                title="设备下线（等价主机发 S1F15）：主动上报立即停止"
              >
                下线
              </button>
            )}
            <button type="button" onClick={() => goTo?.('workbench')}>
              ← 设备清单
            </button>
          </>
        }
        zoneSwitch={
          <ZoneSwitch
            zone={zone}
            onChange={(next) => goTo?.(`device-zone:${sessionId}:${next}`)}
            configNote="▣ 修改的是设备定义;保存后需重载才会进入运行。"
            runtimeNote="◉ 这里的操作只对本次运行有效;重载后按配置恢复。"
          />
        }
      />

      <SubTabs
        pages={pages}
        active={page}
        onSelect={(next) => goTo?.(`device-page:${sessionId}:${zone}:${next}`)}
      />

      {/* 面板按会话重挂：切设备时编辑器/自动打开的配置不能沿用上一台设备的状态 */}
      <div className="tab-page">
        {zone === 'config' && page === 'overview' && (
          <DeviceOverviewPanel {...baseProps} sessionId={sessionId} />
        )}
        {zone === 'config' && page === 'connection' && (
          <ConnectionPanel key={sessionId} {...baseProps} sessionId={sessionId} />
        )}
        {zone === 'config' && page === 'structure' && (
          <StructurePanel key={sessionId} {...baseProps} sessionId={sessionId} />
        )}
        {zone === 'config' && page === 'document' && (
          <ConfigPanel key={sessionId} {...baseProps} sessionId={sessionId} />
        )}
        {zone === 'config' && page === 'constants' && (
          <DeviceConstantsPanel {...baseProps} sessionId={sessionId} />
        )}
        {zone === 'config' && page === 'revisions' && (
          <DeviceRevisionsPanel {...baseProps} sessionId={sessionId} />
        )}

        {zone === 'runtime' && page === 'plans' && (
          <div className="panel-stack">
            <EventsPanel key={sessionId} {...baseProps} />
            <section className="panel-section">
              <div className="section-head">
                <h2>本设备实时事件</h2>
              </div>
              <LiveEventList
                events={deviceEvents}
                emptyText="暂无实时事件;编排触发或手动上报后这里实时滚动。"
              />
            </section>
          </div>
        )}
        {zone === 'runtime' && page === 'catalog' && <CeidPanel key={sessionId} {...baseProps} mode="both" />}
        {zone === 'runtime' && page === 'svids' && <SvidPanel key={sessionId} {...baseProps} />}
        {zone === 'runtime' && page === 'manual' && (
          <div className="panel-stack">
            <SendPanel key={sessionId} {...baseProps} />
            <AlarmsPanel key={sessionId + ':alarms'} {...baseProps} />
          </div>
        )}
      </div>
    </div>
  );
}

/** 配置区·概览:设备定义摘要(从配置原文提炼,不重复编辑入口)。 */
function DeviceOverviewPanel({
  sessionId,
  backend,
}: PanelBaseProps & { sessionId: string }): JSX.Element {
  const summary = backend.sessions.find((session) => session.id === sessionId);
  const config = backend.configList.find((entry) => entry.name === summary?.config);
  return (
    <section className="panel-section">
      <div className="section-head">
        <h2>设备定义摘要</h2>
      </div>
      <table className="effects-table">
        <tbody>
          <tr><th>会话 id</th><td className="mono">{sessionId}</td></tr>
          <tr><th>设备名</th><td>{summary?.device ?? '—'}</td></tr>
          <tr><th>配置文件</th><td className="mono">{summary?.config ?? '—'}</td></tr>
          <tr><th>模板</th><td>{config?.template ?? '—'}</td></tr>
          <tr><th>单元数</th><td>{summary?.units ?? '—'}</td></tr>
          <tr><th>大小 / 修改时间</th><td className="mono">
            {config ? `${config.sizeBytes} B` : '—'}{config ? ` · ${new Date(config.modifiedMs).toLocaleString()}` : ''}
          </td></tr>
        </tbody>
      </table>
      <p className="hint">连接 / 结构 / 常量 / 原文的编辑在配置区的其它子页;保存后需重载使生效。</p>
    </section>
  );
}

/** 配置区·设备常量:从配置原文提炼 ECV 列表(只读快照;S2F15 回写后此处随之更新)。 */
function DeviceConstantsPanel({
  sessionId,
  backend,
}: PanelBaseProps & { sessionId: string }): JSX.Element {
  const summary = backend.sessions.find((session) => session.id === sessionId);
  const configName = summary?.config ?? '';
  const [text, setText] = useState<string | undefined>(undefined);
  useEffect(() => {
    if (configName === '' || backend.client === null) {
      return;
    }
    let cancelled = false;
    backend.client.configText(configName)
      .then((body) => {
        if (!cancelled) {
          setText(body.text);
        }
      })
      .catch(() => {
        /* 读不到就显示空列表 */
      });
    return () => {
      cancelled = true;
    };
  }, [configName, backend.client]);
  const constants = useMemoConstants(text, configName);
  return (
    <section className="panel-section">
      <div className="section-head">
        <h2>设备常量（ECV）</h2>
      </div>
      {constants.length === 0 ? (
        <p className="hint">配置里没有 equipment.constants 段:主机用 S2F13 查询会得到空列表,S2F15 会回 EAC=1。</p>
      ) : (
        <table className="effects-table">
          <thead>
            <tr><th>ECID</th><th>名称</th><th>类型</th><th>当前值</th><th>单位</th></tr>
          </thead>
          <tbody>
            {constants.map((constant) => (
              <tr key={constant.id}>
                <td className="mono">{constant.id}</td>
                <td>{constant.name}</td>
                <td className="mono">{constant.type}</td>
                <td className="mono">{constant.value}</td>
                <td>{constant.unit || '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <p className="hint">主机写 S2F15 后,值写回配置正文(只替换 constants 子树)并升一个版本;此处随正文刷新。</p>
    </section>
  );
}

/** 从配置原文解析 constants(轻量 JSON 解析;原文坏了就显示空)。 */
function useMemoConstants(text: string | undefined, configName: string):
  ReadonlyArray<{ id: number; name: string; type: string; value: number; unit: string }> {
  return useMemo(() => {
    if (text === undefined || configName === '') {
      return [];
    }
    try {
      const root = JSON.parse(text) as {
        equipment?: { constants?: Array<{ id: number; name?: string; type?: string; value?: number; unit?: string }> };
      };
      return (root.equipment?.constants ?? []).map((constant) => ({
        id: constant.id,
        name: constant.name ?? '',
        type: constant.type ?? '',
        value: constant.value ?? 0,
        unit: constant.unit ?? '',
      }));
    } catch {
      return [];
    }
  }, [text, configName]);
}

/** 配置区·版本历史:config_revision 快照(v2 端点)。 */
function DeviceRevisionsPanel({
  sessionId,
  backend,
  notifier,
}: PanelBaseProps & { sessionId: string }): JSX.Element {
  const summary = backend.sessions.find((session) => session.id === sessionId);
  const configName = summary?.config ?? '';
  const [revisions, setRevisions] = useState<ReadonlyArray<{
    revision: number; sha256: string; createdAt: number; createdBy: string; note: string;
  }>>([]);
  const [error, setError] = useState('');

  useEffect(() => {
    if (configName === '' || backend.client === null) {
      return;
    }
    let cancelled = false;
    backend.client.configRevisions(configName, 50)
      .then((body) => {
        if (!cancelled) {
          setRevisions(body);
          setError('');
        }
      })
      .catch((e: Error) => {
        if (!cancelled) {
          setError(e.message);
          setRevisions([]);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [configName, backend.client, notifier]);

  return (
    <section className="panel-section">
      <div className="section-head">
        <h2>配置版本历史</h2>
      </div>
      {error !== '' && <p className="inline-note danger">{error}</p>}
      {revisions.length === 0 && error === '' ? (
        <p className="hint">还没有版本记录;在「配置原文」保存一次就会留一个版本(每设备保留最近 200 版)。</p>
      ) : (
        <table className="effects-table">
          <thead>
            <tr>
              <th>版本</th><th>时间</th><th>操作者</th><th>备注</th>
              <th title="这次保存内容的校验值：两次保存的值相同，说明文件逐字节一致">校验值</th>
            </tr>
          </thead>
          <tbody>
            {revisions.map((revision) => (
              <tr key={revision.revision}>
                <td className="mono">v{revision.revision}</td>
                <td className="mono">{new Date(revision.createdAt).toLocaleString()}</td>
                <td>{revision.createdBy}</td>
                <td>{revision.note || '—'}</td>
                <td className="mono hint-inline">{revision.sha256.slice(0, 12)}…</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

/** 运行区·实时状态已下线：三态徽标、单元数与累计上报都收进实例头。 */
