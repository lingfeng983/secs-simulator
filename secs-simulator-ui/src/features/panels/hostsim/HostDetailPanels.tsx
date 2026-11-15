import type { PanelBaseProps } from '../types';
import type { HostPage, HostZone } from '../../../app/router';
import type { HostsV2State } from '../../../state/useHostsV2';
import type { ApiClient } from '../../../api';
import { HostOverviewPanel } from './HostOverviewPanel';
import { HostPresetsPanel } from './HostPresetsPanel';
import { HostSendPanel } from './HostSendPanel';
import { HostTracePanel } from './HostTracePanel';
import { HostCollectPanel } from './HostCollectPanel';

/**
 * 主机详情子页分发。
 *
 * <p>配置区 4 页 + 运行区 3 页（消息监控已移到右侧抽屉，实时状态并入实例头）。
 * 配置区的 Trace / 采集设置只管理方案与参数；下发、发送与曲线都在运行区。
 * Trace 运行页直接从实时 S6F1 报文里取参数值画趋势，按 TRID 分别选择要看的参数。</p>
 *
 * <p>各子页实现按职责拆在同目录的独立文件里，本文件只保留分发与导出。
 * 连接页、实时状态页、手动操作页已删除：连接/断开/使设置生效在实例头，
 * 单发报文用「消息发送」，报文收发在右侧抽屉。</p>
 */
export function HostDetailPanels({
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
  client: ApiClient | null;
  messages?: PanelBaseProps['messages'];
}): JSX.Element {
  const base = { hosts, goTo, hostId, client, messages };
  if (zone === 'config') {
    switch (page) {
      case 'overview':
        return <HostOverviewPanel {...base} />;
      case 'presets':
        return <HostPresetsPanel {...base} />;
      case 'trace':
        return <HostTracePanel {...base} readOnly key="trace-config" />;
      case 'collect':
        return <HostCollectPanel {...base} readOnly key="collect-config" />;
      default:
        return <HostOverviewPanel {...base} />;
    }
  }
  switch (page) {
    case 'messages':
      return <HostSendPanel {...base} />;
    case 'trace':
      return <HostTracePanel {...base} key="trace-runtime" />;
    case 'collect':
      return <HostCollectPanel {...base} key="collect-runtime" />;
    default:
      return <HostSendPanel {...base} />;
  }
}