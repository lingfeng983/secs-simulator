import type { PanelBaseProps } from '../panels/types';
import { AuditPanel } from '../panels/AuditPanel';
import { ConsolePanel } from '../panels/ConsolePanel';

/** 系统页：审计与事件流日志（全局视角,不挂某台设备）。 */
export function SystemPage(props: PanelBaseProps & { page: 'audit' | 'console' }): JSX.Element {
  return (
    <div className="panel-stack">
      {props.page === 'audit' ? <AuditPanel {...props} /> : <ConsolePanel {...props} />}
    </div>
  );
}
