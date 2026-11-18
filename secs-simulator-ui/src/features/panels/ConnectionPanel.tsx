import type { PanelBaseProps } from './types';
import { ConfigEditShell } from './ConfigEditShell';
import { SessionForm } from '../editors/ConfigFormEditor';

/**
 * 设备 · 连接：配置里的 session 段。
 *
 * <p>这段只有它能改：端口 / 绑定地址 / Device ID 变化必须断链重建，
 * 是唯一会「全清」的一页（保存成功后会提示主机重连、运行态清空）。
 * 保存与校验走 {@link ConfigEditShell} 的统一路径。</p>
 */
export function ConnectionPanel({ backend, sessionId, configName }: PanelBaseProps & { sessionId?: string; configName?: string }): JSX.Element {
  return (
    <ConfigEditShell
      backend={backend}
      sessionId={sessionId}
      configName={configName}
      panelId="panel-connection"
      title="连接"
      sections={['session']}
      /* 这段改的是监听口，保存一定重建会话：设备启动着时先问一句 */
      confirmOnSave={{
        title: '保存连接参数并重启会话？',
        message: '端口 / 绑定地址 / Device ID 改完要按新参数重建会话：端口会重新监听，'
          + '当前连接会断开，钉值、计划启用态、周期进度等运行状态全部清空。',
        confirmLabel: '保存并重启会话',
      }}
    >
      {({ root, edit }) => (
        <>
          <p className="hint">
            这段是 HSMS 连接参数。<b>保存后会按新参数重启会话</b>：端口重新监听、当前连接断开，
            钉值 / 计划启用态 / 周期进度这些运行状态全部清空（设备启动着保存会先问一句）。
          </p>
          <SessionForm root={root} edit={edit} />
        </>
      )}
    </ConfigEditShell>
  );
}
