import type { PanelBaseProps } from '../types';
import type { HostsV2State } from '../../../state/useHostsV2';
import type { ApiClient } from '../../../api';

/** 主机详情各子页共用的入参：分发层把主机与客户端一次性传给子页。 */
export type PanelArgs = {
  hostId: string;
  hosts: HostsV2State;
  goTo?: (id: string) => void;
  readOnly?: boolean;
  client: ApiClient | null;
  messages?: PanelBaseProps['messages'];
};

/** 需要直接发报文的子页共用：后端未就绪时在异步 send 里抛错，由各面板转成行内提示。 */
export function requireClient(client: ApiClient | null): ApiClient {
  if (client === null) {
    throw new Error('后端未连接');
  }
  return client;
}