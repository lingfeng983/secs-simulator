import type { PanelBaseProps } from '../panels/types';
import { AuditPanel } from '../panels/AuditPanel';

/** 系统页：审计留痕（全局视角,不挂某台设备）。 */
export function SystemPage(props: PanelBaseProps & { page: 'audit' }): JSX.Element {
  return (
    <div className="panel-stack">
      <AuditPanel {...props} />
    </div>
  );
}
