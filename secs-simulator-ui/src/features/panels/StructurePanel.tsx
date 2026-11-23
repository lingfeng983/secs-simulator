import type { PanelBaseProps } from './types';
import { ConfigEditShell } from './ConfigEditShell';
import { EquipmentForm } from '../editors/ConfigFormEditor';

/**
 * 设备 · 结构配置：配置里的 equipment 全局（号段规划 / 事件发送）与单元。
 *
 * <p>设备全局是一张表单 + 「保存」按钮（只提交 structure 与 throttle 两段，
 * throttle 是 2026-11-20 从「事件计划」页搬来的事件发送三项）；单元是列表，
 * 新增弹窗确认、行内修改与删除都立即保存。2026-11-21 起设备启动中也可以保存：
 * 改动落盘但不热应用，重启设备后才生效。</p>
 */
export function StructurePanel({ backend, sessionId, configName }: PanelBaseProps & { sessionId?: string; configName?: string }): JSX.Element {
  return (
    <ConfigEditShell
      backend={backend}
      sessionId={sessionId}
      configName={configName}
      panelId="panel-structure"
      title="结构配置"
      sections={['structure', 'throttle']}
    >
      {({ root, edit, save, busy }) => (
        <EquipmentForm
          root={root}
          edit={edit}
          onMutated={() => void save()}
          onSave={() => save()}
          busy={busy}
        />
      )}
    </ConfigEditShell>
  );
}
