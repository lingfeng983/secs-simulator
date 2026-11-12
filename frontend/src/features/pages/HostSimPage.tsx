import type { HostSimPage as HostSimPageId } from '../../app/router';
import { HOSTSIM_PAGE_LABELS, HOSTSIM_PAGES } from '../../app/router';
import { HostSimHostsPanel } from '../panels/hostsim/HostSimHostsPanel';
import { HostSimMessagesPanel } from '../panels/hostsim/HostSimMessagesPanel';
import { HostSimFdcPanel } from '../panels/hostsim/HostSimFdcPanel';
import { HostSimCollectPanel } from '../panels/hostsim/HostSimCollectPanel';
import { HostSimMonitorPanel } from '../panels/hostsim/HostSimMonitorPanel';
import type { PanelBaseProps } from '../panels/types';
import { useHostSims } from '../../state/useHostSims';
import { useEffect } from 'react';

/** 各子页的一句话说明。 */
const PAGE_DESCRIPTIONS: Record<HostSimPageId, string> = {
  hosts: '新建 / 断开主机实例：每个主机可以连当前库里的任意设备，也可以连远程 SECS。',
  messages: '以主机角色发 SML：补上 S2F33 / S2F35 / S2F37 这类只有主机能发的报文，可压测连发。',
  fdc: '② S6F1 采集（S2F23 → S6F1）：设备按 DSPER 采样、REPGSZ 分条上报。本机设备用表单，远程主机手填报文。',
  collect: '③ S1F3 数据采集：主机侧按周期拉取 SVID，设备推 S6F11 触发起停。本机设备多选变量，远程主机手填。',
  monitor: '实时监控所有主机实例的收发：按主机 / 方向 / 类别 / 关键字过滤，远程主机也在内。',
};

/**
 * 主机模拟页（顶层入口，不在设备页签里）。
 *
 * <p>五块：主机实例（建/断/选）、① 消息发送（含压测）、② S6F1 采集、
 * ③ S1F3 采集、消息监控。三类采集各自成页——原来挤在一张面板里，
 * 参数多到看不清谁在配谁；收发记录独立成「消息监控」，全部主机（含远程）
 * 一起看。</p>
 */
export function HostSimPage({
  page,
  backend,
  notifier,
  preferences,
  updatePreferences,
  goTo,
  liveEvents,
  messages,
}: PanelBaseProps & { page: HostSimPageId }): JSX.Element {
  const hostSims = useHostSims(backend.client ?? null);

  /* 切子页时刷新实例列表：连接状态是活的。
     依赖 refresh 函数本身（稳定的 useCallback），不依赖整个对象。 */
  const refreshHosts = hostSims.refresh;
  useEffect(() => {
    void refreshHosts();
  }, [page, refreshHosts]);

  const baseProps: PanelBaseProps = {
    backend,
    notifier,
    preferences,
    updatePreferences,
    goTo,
    liveEvents,
    messages,
  };

  return (
    <div className="panel-stack">
      <div className="device-tabs" role="tablist">
        {HOSTSIM_PAGES.map((value) => (
          <button
            key={value}
            type="button"
            role="tab"
            aria-selected={page === value}
            className={page === value ? 'active' : ''}
            title={PAGE_DESCRIPTIONS[value]}
            onClick={() => goTo?.(`hostsim-page:${value}`)}
          >
            {HOSTSIM_PAGE_LABELS[value]}
          </button>
        ))}
      </div>

      <div className="tab-page">
      {page === 'hosts' && (
        <HostSimHostsPanel {...baseProps} hostSims={hostSims} />
      )}
      {page === 'messages' && (
        <HostSimMessagesPanel {...baseProps} hostSims={hostSims} />
      )}
      {page === 'fdc' && (
        <HostSimFdcPanel {...baseProps} hostSims={hostSims} />
      )}
      {page === 'collect' && (
        <HostSimCollectPanel {...baseProps} hostSims={hostSims} />
      )}
      {page === 'monitor' && (
        <HostSimMonitorPanel {...baseProps} hostSims={hostSims} />
      )}
      </div>
    </div>
  );
}
