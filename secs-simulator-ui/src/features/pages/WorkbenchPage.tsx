import type { PanelBaseProps } from '../panels/types';
import { DeviceOpsPanel } from '../panels/DeviceOpsPanel';

/**
 * 工作台（设备清单）。
 *
 * <p>一页只有一张表：一行一台设备，读数、勾选、生命周期操作都在这一行上。
 * 顶部不再有仪表卡片——在线数、速率、报警都在表里逐行看得到，
 * 汇总数退到表格脚注；页面级动作（新建 / 批量）都在表格工具条上。</p>
 */
export function WorkbenchPage({ backend, notifier, preferences, updatePreferences, goTo, regions }: PanelBaseProps): JSX.Element {
  return (
    <div className="panel-stack">
      <DeviceOpsPanel
        backend={backend}
        notifier={notifier}
        preferences={preferences}
        updatePreferences={updatePreferences}
        goTo={goTo}
        regions={regions}
      />
    </div>
  );
}
