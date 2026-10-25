import type { PanelBaseProps } from '../panels/types';
import { MessagesPanel } from '../panels/MessagesPanel';

/** 报文页:HSMS 报文流的搜索、查看与导出,只做这一件事。 */
export function MessagesPage({ backend, notifier, preferences, updatePreferences }: PanelBaseProps): JSX.Element {
  return (
    <div className="panel-stack">
      <MessagesPanel
        backend={backend}
        notifier={notifier}
        preferences={preferences}
        updatePreferences={updatePreferences}
      />
    </div>
  );
}
