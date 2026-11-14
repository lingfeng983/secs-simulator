import type { PanelBaseProps } from './types';
import { ConfigEditShell, MissingEquipment } from './ConfigEditShell';
import { ConstantsForm } from '../editors/ConfigFormEditor';
import { isJsonObject } from '../editors/templateConfig';

/**
 * 设备 · 配置区 · 设备常量（ECV）。
 *
 * <p>编辑 equipment.constants：S2F13 查询 / S2F15 写入的 ECID 清单。
 * 主机写 S2F15 后值会写回配置正文并升一个版本；这里改的是「初始定义」，
 * 保存后需重载生效。</p>
 */
export function ConstantsConfigPanel(props: PanelBaseProps & { sessionId?: string; configName?: string }): JSX.Element {
  const { backend, sessionId, configName } = props;
  return (
    <ConfigEditShell
      backend={backend}
      sessionId={sessionId}
      configName={configName}
      panelId="panel-constants-config"
      title="设备常量（ECV）"
      description={
        '设备常量是 S2F13/S2F15 的查询与写入对象：\n'
        + '这里维护 ECID 清单与初始值；主机 S2F15 写入后回写配置正文并升版本。\n'
        + '改动写入配置草稿，「校验」→「保存」后相关会话自动重载。'
      }
    >
      {({ root, edit }) => (
        isJsonObject(root.equipment)
          ? <ConstantsForm equipment={root.equipment} edit={edit} />
          : <MissingEquipment edit={edit} />
      )}
    </ConfigEditShell>
  );
}
