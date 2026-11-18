/**
 * 设备运行状态的统一四态：停止 / 启动 / 离线 / 在线。
 *
 * <p>后端有两层状态：{@code SessionState}（端口有没有打开）和
 * {@code EquipmentControlState}（链路与 GEM 控制状态）。界面按操作员关心的
 * 顺序把它们压成四档：</p>
 *
 * <ul>
 *   <li><b>停止</b>（灰）：端口没监听，主机连不上；</li>
 *   <li><b>启动</b>（灰）：端口已在监听，等主机 SELECT；</li>
 *   <li><b>离线</b>（黄）：HSMS 已连接（{@code COMMUNICATING}），但设备还没 ONLINE
 *       ——严格 GEM 下此时<b>不能</b>主动上报 S6F11 / S6F1；</li>
 *   <li><b>在线</b>（绿）：{@code ONLINE}，事件按规则正常发。</li>
 * </ul>
 *
 * <p>"离线"这一档是 3.0 严格化之后补上的：以前连接一通就显示"连接"，
 * 操作员看不出"连着但主机没让设备上线"，于是计划显示运行中、报文却一条都不发。
 * 上线有两条路——主机发 S1F17，或操作员在界面上直接置为在线。</p>
 *
 * <p>放在共享层是因为设备页头、侧栏设备树、设备清单表三处都要用同一套
 * 判据——各写一份必然出现"侧栏说在线、表格说离线"。</p>
 */

export type DeviceStatus = 'stopped' | 'started' | 'offline' | 'online';

/** 徽标色调：沿用项目的 muted / warn / ok 令牌（danger / info 仍保留供其它状态复用）。 */
export type DeviceStatusTone = 'danger' | 'warn' | 'info' | 'ok' | 'muted';

export interface DeviceStatusSource {
  /** 会话状态：CREATED / OPENING / OPEN / CLOSING / CLOSED。 */
  state?: string;
  /** 控制状态：OFFLINE / COMMUNICATING / ONLINE。 */
  controlState?: string;
}

/** 判定四态：先看端口，再看链路，最后看 GEM 控制状态。 */
export function deviceStatus(source: DeviceStatusSource): DeviceStatus {
  const state = source.state ?? '';
  if (state === 'CLOSED' || state === 'CLOSING' || state === 'CREATED' || state === '') {
    return 'stopped';
  }
  const control = source.controlState ?? '';
  if (control === 'ONLINE') {
    return 'online';
  }
  if (control === 'COMMUNICATING') {
    return 'offline';
  }
  return 'started';
}

export const DEVICE_STATUS_LABEL: Record<DeviceStatus, string> = {
  stopped: '停止',
  started: '启动',
  offline: '离线',
  online: '在线',
};

export const DEVICE_STATUS_TONE: Record<DeviceStatus, DeviceStatusTone> = {
  /* 正常停机 / 等待连接都是中间态，不该染成报警红或提醒黄 */
  stopped: 'muted',
  started: 'muted',
  /* 连着但没上线：需要关注，但不是故障 */
  offline: 'warn',
  online: 'ok',
};

/** 一行说明（tooltip 用）。 */
export function deviceStatusHint(status: DeviceStatus): string {
  switch (status) {
    case 'stopped':
      return '端口未监听：主机连不上，点「启动」开始监听';
    case 'started':
      return '端口已监听，等待主机建立 HSMS 连接';
    case 'offline':
      return '已连接但设备离线：等主机 S1F17，或手动点「上线」；离线期间不发 S6F11';
    default:
      return '设备在线：事件按规则正常上报';
  }
}
