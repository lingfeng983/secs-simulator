import type { BackendController } from '../state/useBackend';
import type { Preferences } from '../state/usePreferences';
import type { Notifier } from '../state/useToasts';
import type { StreamState } from '../state/useEventStream';

/**
 * 所有面板共有的入参。
 *
 * <p>面板只管渲染与收集输入：数据从 {@link BackendController} 来，
 * 反馈走 {@link Notifier}，跨面板的偏好走 {@link Preferences}。
 * 这样加面板不用碰 App.tsx。</p>
 *
 * <p>goTo / streamState / streamLabel 是可选的联动能力：
 * 只有总览这类需要跳面板、看流状态的页面会用到。</p>
 */
export interface PanelBaseProps {
  backend: BackendController;
  notifier: Notifier;
  preferences: Preferences;
  updatePreferences: (patch: Partial<Preferences>) => void;
  /** 跳转到另一个面板。 */
  goTo?: (id: string) => void;
  /** 事件流状态与文案（总览 KPI 用）。 */
  streamState?: StreamState;
  streamLabel?: string;
}
