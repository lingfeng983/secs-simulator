import type { PanelBaseProps } from './types';
import { ConfigEditShell } from './ConfigEditShell';
import { EquipmentForm } from '../editors/ConfigFormEditor';

/**
 * 设备 · 结构：配置里的 equipment 段（单元 / 号段 / 设备名）。
 *
 * <p>增删单元、改 unitId 属于结构变更：后端要求设备不在线（守卫 A），
 * 外壳会先查一次控制状态，在线时直接禁用保存并说明原因。</p>
 */
export function StructurePanel({ backend, sessionId, configName }: PanelBaseProps & { sessionId?: string; configName?: string }): JSX.Element {
  return (
    <ConfigEditShell
      backend={backend}
      sessionId={sessionId}
      configName={configName}
      panelId="panel-structure"
      title="结构"
      sections={['structure']}
    >
      {({ root, edit }) => <EquipmentForm root={root} edit={edit} />}
    </ConfigEditShell>
  );
}
