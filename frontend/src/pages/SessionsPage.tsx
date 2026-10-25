import type { PanelBaseProps } from '../panels/types';
import { SessionsPanel } from '../panels/SessionsPanel';

/** 会话管理页:新建/批量新建、重载、上报、批量操作——设备生命周期都在这里。 */
export function SessionsPage({ backend, notifier, preferences, updatePreferences, goTo }: PanelBaseProps): JSX.Element {
  return (
    <div className="panel-stack">
      <SessionsPanel
        backend={backend}
        notifier={notifier}
        preferences={preferences}
        updatePreferences={updatePreferences}
        goTo={goTo}
      />
    </div>
  );
}
