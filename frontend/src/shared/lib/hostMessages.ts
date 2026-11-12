import type { WireMessage } from '../../api';

/** 归属计算只需要主机实例的这几个字段。 */
export interface HostOwnershipRef {
  id: string;
  session: string;
  remote: boolean;
  open: boolean;
}

/**
 * 把实时报文归属到主机实例上（消息监控页用）。
 *
 * <p>两条通道的报文形状不同，必须分开处理：</p>
 * <ul>
 *   <li><b>远程主机</b>：报文由主机实例自己发布，带 {@code hostId}，
 *       直接按实例归属；{@code hostId} 指向已不存在的实例时丢弃（那个实例已经关了）。</li>
 *   <li><b>本机主机</b>：报文走设备会话，设备侧发布的那一份<b>不带</b> {@code hostId}。
 *       只有"当前连着这台会话的本机主机"才认领它——主机断开后设备可能被别的主机连上，
 *       那时再按会话归属就会把别人的报文算到已断开的实例头上。</li>
 * </ul>
 *
 * <p>没有任何主机实例认领的设备报文不进监控页（那是设备自己的业务，
 * 在设备页的「消息」里看）。</p>
 *
 * @param messages 实时报文（设备视角方向）
 * @param hosts    主机实例
 * @returns 带 {@code hostId} 的报文，顺序与入参一致
 */
export function attributeHostMessages(
  messages: readonly WireMessage[],
  hosts: readonly HostOwnershipRef[],
): WireMessage[] {
  const known = new Set<string>();
  const localHostBySession = new Map<string, string>();
  for (const host of hosts) {
    known.add(host.id);
    if (!host.remote && host.open) {
      localHostBySession.set(host.session, host.id);
    }
  }

  const owned: WireMessage[] = [];
  for (const message of messages) {
    if (message.hostId !== undefined && message.hostId !== '') {
      if (known.has(message.hostId)) {
        owned.push(message);
      }
      continue;
    }
    const hostId = localHostBySession.get(message.sessionId);
    if (hostId !== undefined) {
      owned.push({ ...message, hostId });
    }
  }
  return owned;
}
