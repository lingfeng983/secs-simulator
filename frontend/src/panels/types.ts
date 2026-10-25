import type { BackendController } from '../state/useBackend';
import type { Preferences } from '../state/usePreferences';
import type { Notifier } from '../state/useToasts';
import type { StreamState, NoticeWire } from '../state/useEventStream';

/**
 * 所有面板共有的入参。
 *
 * <p>面板只管渲染与收集输入：数据从 {@link BackendController} 来，
 * 反馈走 {@link Notifier}，跨面板的偏好走 {@link Preferences}。
 * 这样加面板不用碰 App.tsx。</p>
 *
 * <p>goTo / streamState / streamLabel / liveEvents 是可选的联动能力：
 * 只有需要跳转、看流状态或订阅实时事件的页面会用到。</p>
 */
export interface PanelBaseProps {
  backend: BackendController;
  notifier: Notifier;
  preferences: Preferences;
  updatePreferences: (patch: Partial<Preferences>) => void;
  /** 跳转到另一个面板（旧面板 id 兼容:overview/sessions→工作台,config→当前设备配置…）。 */
  goTo?: (id: string) => void;
  /** 事件流状态与文案（总览 KPI 用）。 */
  streamState?: StreamState;
  streamLabel?: string;
  /** 实时类型化通知（v6:s6f11/alarm/session,工作台与设备页事件流用）。 */
  liveEvents?: NoticeWire[];
}
