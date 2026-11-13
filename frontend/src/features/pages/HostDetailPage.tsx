import { useEffect, useState } from 'react';
import type { PanelBaseProps } from '../panels/types';
import {
  HOST_CONFIG_PAGES,
  HOST_CONFIG_PAGE_LABELS,
  HOST_RUNTIME_PAGES,
  HOST_RUNTIME_PAGE_LABELS,
  type HostPage,
  type HostZone,
} from '../../app/router';
import { hostPhaseLabel, hostPhaseTone, type HostsV2State } from '../../state/useHostsV2';
import { InstanceHead, SubTabs, ZoneBar } from '../../shared/ui/zone';
import { Panel } from '../../shared/ui/Panel';
import { HostDetailPanels } from '../panels/hostsim/HostDetailPanels';
/**
 * 主机详情页（设计 §3.4）：实例头 + 配置区(5 页) / 运行区(6 页)。
 * 运行区所有操作只作用于本主机（第三轮修订 13）；收发记录只看本主机。
 */
export function HostDetailPage({
  hostId,
  zone,
  page,
  hosts,
  goTo,
  backend,
}: PanelBaseProps & {
  hostId: string;
  zone: HostZone;
  page: HostPage;
  hosts: HostsV2State;
}): JSX.Element {
  const host = hosts.find(hostId);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  /* 详情页 2 秒轮询：相位/版本/计数都是活数据 */
  useEffect(() => {
    const timer = window.setInterval(() => void hosts.refresh(), 2_000);
    return () => window.clearInterval(timer);
  }, [hosts]);

  if (host === null) {
    return (
      <Panel id="panel-host-missing" title="主机不存在" description="实例可能已被删除。">
        <p className="hint">回「主机模拟」列表查看现有实例。</p>
        <button type="button" onClick={() => goTo?.('hosts')}>回主机列表</button>
      </Panel>
    );
  }

  const openHost = () => {
    setBusy(true);
    setError('');
    hosts.connect(hostId)
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusy(false));
  };
  const disconnectHost = () => {
    setBusy(true);
    setError('');
    hosts.disconnect(hostId).finally(() => setBusy(false));
  };
  const applySettings = () => {
    setBusy(true);
    setError('');
    hosts.apply(hostId)
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusy(false));
  };

  const badge = (
    <span className={`badge tone-${hostPhaseTone(host.phase)}`}>{hostPhaseLabel(host.phase)}</span>
  );
  const settingsPending = host.settingRevision !== host.appliedRevision;

  const pages = zone === 'config'
    ? HOST_CONFIG_PAGES.map((id) => ({ id, label: HOST_CONFIG_PAGE_LABELS[id] }))
    : HOST_RUNTIME_PAGES.map((id) => ({ id, label: HOST_RUNTIME_PAGE_LABELS[id] }));

  return (
    <div className="panel-stack">
      <InstanceHead
        title={`${host.id} · ${host.name}`}
        badge={badge}
        subtitle={
          <>
            目标 <span className="mono">{host.target}</span> · Device ID {host.deviceId}
            {host.autoReconnect ? ' · 自动重连开' : ''}
            {host.lastError ? <span className="danger"> · {host.lastError}</span> : ''}
          </>
        }
        metrics={[
          { label: '设置版本', value: `v${host.settingRevision}` },
          { label: '当前生效', value: host.open ? `v${host.appliedRevision}` : '— 离线' },
          { label: '重试次数', value: host.autoReconnect ? host.retryCount : '—' },
        ]}
        actions={
          <>
            {host.open ? (
              <button type="button" disabled={busy} onClick={disconnectHost}>断开连接</button>
            ) : (
              <button type="button" className="primary" disabled={busy} onClick={openHost}>
                {host.phase === 'FAILED' ? '重新连接' : '连接'}
              </button>
            )}
            <button type="button" disabled={busy} onClick={applySettings} title="按最新设置重连并恢复采集">
              使设置生效
            </button>
          </>
        }
      />

      {error !== '' && <p className="inline-note danger">{error}</p>}
      {settingsPending && (
        <div className="pending-banner settings" role="status">
          <span className="pending-tag">设置待生效</span>
          <span className="pending-text">
            设置已更新到 <b>v{host.settingRevision}</b>，当前生效仍是{' '}
            <b>{host.open ? `v${host.appliedRevision}` : '—'}</b>
          </span>
          <span className="spacer" />
          <button type="button" className="primary" disabled={busy} onClick={applySettings}>
            使设置生效
          </button>
        </div>
      )}
      {!host.open && zone === 'runtime' && (
        <div className="empty-zone" role="status">
          <strong>链路已断开，主机仍在列表里</strong>
          <div>目标地址已释放，设置照常可以编辑。</div>
          <div style={{ marginTop: 10 }}>
            <button type="button" className="primary" disabled={busy} onClick={openHost}>重新连接</button>
          </div>
        </div>
      )}

      <ZoneBar
        zone={zone}
        onChange={(next) => goTo?.(`host-zone:${hostId}:${next}`)}
        configNote="▣ 修改后点「使设置生效」，按最新设置重连。"
        runtimeNote="◉ 这里的操作只对本次连接有效；收发只作用于本主机。"
      />
      <SubTabs
        pages={pages}
        active={page}
        onSelect={(next) => goTo?.(`host-page:${hostId}:${zone}:${next}`)}
      />

      <HostDetailBody
        hostId={hostId}
        zone={zone}
        page={page}
        hosts={hosts}
        goTo={goTo}
        client={backend.client}
      />
    </div>
  );
}

/** 按 zone+page 分发子页内容；面板全部复用既有主机模拟实现（一项不删）。 */
function HostDetailBody({
  hostId,
  zone,
  page,
  hosts,
  goTo,
  client,
}: {
  hostId: string;
  zone: HostZone;
  page: HostPage;
  hosts: HostsV2State;
  goTo?: (id: string) => void;
  client: PanelBaseProps['backend']['client'];
}): JSX.Element {
  const host = hosts.find(hostId);
  if (host === null) {
    return <Panel id="panel-host-missing-inner" title="主机不存在" description="" ><p className="hint">实例可能已被删除。</p></Panel>;
  }
  /* 子页实现见 HostDetailPanels;这里保持骨架文件可读 */
  return <HostDetailPanels hostId={hostId} zone={zone} page={page} hosts={hosts} goTo={goTo} client={client} />;
}
