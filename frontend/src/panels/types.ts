import type { BackendController } from '../state/useBackend';
import type { Preferences } from '../state/usePreferences';
import type { Notifier } from '../state/useToasts';

/**
 * 所有面板共有的入参。
 *
 * <p>面板只管渲染与收集输入：数据从 {@link BackendController} 来，
 * 反馈走 {@link Notifier}，跨面板的偏好走 {@link Preferences}。
 * 这样加面板不用碰 App.tsx。</p>
 */
export interface PanelBaseProps {
  backend: BackendController;
  notifier: Notifier;
  preferences: Preferences;
  updatePreferences: (patch: Partial<Preferences>) => void;
}
