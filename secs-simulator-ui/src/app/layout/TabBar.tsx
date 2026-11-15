import { useEffect, useState } from 'react';
import { tabKindOf, type AppTab } from './tabs';

/** 标签页右键菜单的动作。 */
export type TabCloseMode = 'self' | 'others' | 'left' | 'right' | 'all';

export interface TabBarProps {
  tabs: readonly AppTab[];
  activeId: string;
  onSelect: (tab: AppTab) => void;
  onClose: (tab: AppTab) => void;
  /** 批量关闭：右键菜单用。 */
  onCloseMany: (tab: AppTab, mode: TabCloseMode) => void;
}

interface MenuState {
  tab: AppTab;
  x: number;
  y: number;
}

/**
 * 顶栏下方的页面标签条：打开过的页面留一个标签，点标签直接切回去。
 * 每个标签可单独关闭；工作台标签常驻，保证任何时候都有落脚点。
 *
 * <p>标签按三类上色（见 {@link tabKindOf}）：设备一色、主机一色、其余一色。
 * 左边一道域色竖条 + 极淡底色，选中时加重——一长排标签里也能看清
 * "这一条是设备、那一条是主机"。</p>
 *
 * <p>右键标签弹出批量关闭菜单：当前 / 其他 / 左侧 / 右侧 / 全部。
 * 菜单挂在 fixed 层上——标签条本身是横向滚动容器，菜单放里面会被裁掉。</p>
 */
export function TabBar({ tabs, activeId, onSelect, onClose, onCloseMany }: TabBarProps): JSX.Element {
  const [menu, setMenu] = useState<MenuState | null>(null);

  /* 点空白、按 Esc、滚动都收起菜单：菜单是临时浮层，不该粘在屏幕上 */
  useEffect(() => {
    if (menu === null) {
      return;
    }
    const close = () => setMenu(null);
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        close();
      }
    };
    window.addEventListener('pointerdown', close);
    window.addEventListener('keydown', onKey);
    window.addEventListener('resize', close);
    return () => {
      window.removeEventListener('pointerdown', close);
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('resize', close);
    };
  }, [menu]);

  const index = menu === null ? -1 : tabs.findIndex((tab) => tab.id === menu.tab.id);
  const onlyWorkbenchLeft = menu === null
    ? true
    : tabs.every((tab) => tab.id === menu.tab.id || tab.id === 'workbench');

  const run = (mode: TabCloseMode) => {
    if (menu === null) {
      return;
    }
    onCloseMany(menu.tab, mode);
    setMenu(null);
  };

  return (
    <nav className="tabbar" aria-label="打开的页面">
      <div className="tabbar-scroll">
        {tabs.map((tab) => (
          <div
            key={tab.id}
            /* 三类页签用三种颜色：设备 / 主机 / 其他，扫一眼就知道切到哪个域 */
            className={`app-tab kind-${tabKindOf(tab.id)}${tab.id === activeId ? ' active' : ''}`}
            data-tab-id={tab.id}
            data-tab-kind={tabKindOf(tab.id)}
            onContextMenu={(event) => {
              event.preventDefault();
              setMenu({ tab, x: event.clientX, y: event.clientY });
            }}
          >
            <button
              type="button"
              className="app-tab-label"
              onClick={() => onSelect(tab)}
              title={`切换到 ${tab.label}`}
              aria-current={tab.id === activeId ? 'page' : undefined}
            >
              {tab.label}
            </button>
            {tab.id !== 'workbench' && (
              <button
                type="button"
                className="app-tab-close"
                onClick={() => onClose(tab)}
                aria-label={`关闭 ${tab.label}`}
                title="关闭标签"
              >
                ×
              </button>
            )}
          </div>
        ))}
      </div>

      {menu !== null && (
        <div
          className="tab-menu"
          role="menu"
          style={{ left: menu.x, top: menu.y }}
          onPointerDown={(event) => event.stopPropagation()}
        >
          <button
            type="button"
            role="menuitem"
            disabled={menu.tab.id === 'workbench'}
            onClick={() => run('self')}
          >
            关闭当前
          </button>
          <button
            type="button"
            role="menuitem"
            disabled={onlyWorkbenchLeft}
            onClick={() => run('others')}
          >
            关闭其他
          </button>
          <button
            type="button"
            role="menuitem"
            disabled={index <= 0}
            onClick={() => run('left')}
          >
            关闭左侧
          </button>
          <button
            type="button"
            role="menuitem"
            disabled={index < 0 || index >= tabs.length - 1}
            onClick={() => run('right')}
          >
            关闭右侧
          </button>
          <span className="tab-menu-sep" aria-hidden="true" />
          <button
            type="button"
            role="menuitem"
            disabled={tabs.length <= 1}
            onClick={() => run('all')}
          >
            关闭全部
          </button>
        </div>
      )}
    </nav>
  );
}
