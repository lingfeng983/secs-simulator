import { useEffect, useState } from 'react';
import { useVisibilityInterval } from '../../state/useVisibilityInterval';
import type { PanelBaseProps } from '../panels/types';
import {
  HOST_CONFIG_PAGES,
  HOST_CONFIG_PAGE_LABELS,
  HOST_CONFIG_PAGE_NOTES,
  HOST_RUNTIME_PAGES,
  HOST_RUNTIME_PAGE_LABELS,
  HOST_RUNTIME_PAGE_NOTES,
  type HostPage,
  type HostZone,
} from '../../app/router';
import { type HostsV2State } from '../../state/useHostsV2';
import { EndpointTag, SubTabs, ZoneSwitch } from '../../shared/ui/zone';
import { Panel } from '../../shared/ui/Panel';
import { hostOfTarget } from '../../shared/lib/format';
import { HostDetailPanels } from '../panels/hostsim/HostDetailPanels';
/**
 * 主机详情页（设计 §3.4）：一条操作栏 + 配置区(4 页) / 运行区(3 页)。
 * 头部与设备页同构：不摆标题、状态徽标与指标，只留"切区"和"对这台主机动手"。
 * 运行区所有操作只作用于本主机（第三轮修订 13）；收发记录只看本主机。
 */
export function HostDetailPage({
  hostId,
  zone,
  page,
  hosts,
  goTo,
  backend,
  messages,
}: PanelBaseProps & {
  hostId: string;
  zone: HostZone;
  page: HostPage;
  hosts: HostsV2State;
}): JSX.Element {
  const host = hosts.find(hostId);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  /* 详情页 2 秒轮询：相位/版本/计数都是活数据。
     N6：useVisibilityInterval——回调走 ref，不因 hosts 每次 render 重建而重启定时器；
     后台标签页跳过，回页立即补拉一次。 */
  const refreshHosts = hosts.refresh;
  useVisibilityInterval(() => void refreshHosts(), 2_000);

  if (host === null) {
    return (
      <Panel id="panel-host-missing" title="主机不存在" description="实例可能已被删除。">
        <p className="hint">回「主机清单」查看现有实例。</p>
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

  const pages = zone === 'config'
    ? HOST_CONFIG_PAGES.map((id) => ({ id, label: HOST_CONFIG_PAGE_LABELS[id], note: HOST_CONFIG_PAGE_NOTES[id] }))
    : HOST_RUNTIME_PAGES.map((id) => ({ id, label: HOST_RUNTIME_PAGE_LABELS[id], note: HOST_RUNTIME_PAGE_NOTES[id] }));

  return (
    <div className="panel-stack host-page" role="region" aria-label={`主机 ${host.name}`} data-host={host.id}>
      {/* 与设备页同一条操作栏：切配置区/运行区 + 对这台主机动手。
          标题、相位徽标与指标都不再占行——名字在页签与侧栏里。 */}
      <div className="detail-bar">
        <ZoneSwitch
          zone={zone}
          onChange={(next) => goTo?.(`host-zone:${hostId}:${next}`)}
          configNote="▣ 修改后点「使设置生效」，按最新设置重连。"
          runtimeNote="◉ 这里的操作只对本次连接有效；收发只作用于本主机。"
        />
        <EndpointTag
          label="目标"
          host={hostOfTarget(host.target)}
          port={host.port}
          deviceId={host.deviceId}
          hint="目标地址：这台主机连向哪个设备的哪个端口，以及目标 Device ID"
        />
        <span className="spacer" aria-hidden="true" />
        <div className="detail-actions">
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
        </div>
      </div>

      {error !== '' && <p className="inline-note danger">{error}</p>}

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
        messages={messages}
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
  messages,
}: {
  hostId: string;
  zone: HostZone;
  page: HostPage;
  hosts: HostsV2State;
  goTo?: (id: string) => void;
  client: PanelBaseProps['backend']['client'];
  messages?: PanelBaseProps['messages'];
}): JSX.Element {
  const host = hosts.find(hostId);
  if (host === null) {
    return <Panel id="panel-host-missing-inner" title="主机不存在" description="" ><p className="hint">实例可能已被删除。</p></Panel>;
  }
  /* 子页实现见 HostDetailPanels;这里保持骨架文件可读 */
  return (
    <HostDetailPanels
      hostId={hostId}
      zone={zone}
      page={page}
      hosts={hosts}
      goTo={goTo}
      client={client}
      messages={messages}
    />
  );
}
