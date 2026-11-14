/**
 * E5 控制状态的中文标签与色调。
 *
 * <p>工作台(设备与会话)与设备页头都要用,放在这里而不是某个面板里,
 * 避免为了两个字符串互相 import 一整个面板。</p>
 */

/** E5 简化控制状态的中文标签。 */
export function controlStateLabel(state: string | undefined): string {
  switch (state) {
    case 'OFFLINE':
      return '离线';
    case 'COMMUNICATING':
      /* 链路已建、设备还没上线：GEM 里这是"已连接但 OFF-LINE"，
         直接叫"通信中"会让人以为能收发了 */
      return '已连接未上线';
    case 'ONLINE':
      return '在线';
    default:
      return state ?? '-';
  }
}
