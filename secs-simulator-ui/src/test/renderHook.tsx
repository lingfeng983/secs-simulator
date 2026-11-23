import { act } from 'react';
import { createRoot } from 'react-dom/client';

/**
 * 最小 hook 测试台。
 *
 * <p>项目里没有 @testing-library：绝大多数测试是 `renderToStaticMarkup` 的
 * 静态渲染，够用且快。但 `useConfigDraft` / `useEventStream` 这类 hook 的价值
 * 全在"effect 跑起来之后会发生什么"（读盘、409 守卫、重连补拉），SSR 渲染
 * 碰不到它们。所以这里补一个只有三十行的挂载器：一个 `<div>` + `createRoot`，
 * 用 `React.act` 把 effect 与 setState 一起冲掉，再用 ref 把最新返回值读出来。</p>
 *
 * <p>用到它的测试文件要在首行声明 `// @vitest-environment jsdom`——其余测试
 * 继续跑在 node 环境里，不必为 DOM 付启动成本。</p>
 */

/** 让 act 认得这是一次受控的测试渲染，否则 React 会打 "not wrapped in act" 警告。 */
declare global {
  // eslint-disable-next-line no-var
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true;

export interface RenderHookResult<T> {
  /** 最新一次渲染时 hook 的返回值（每次渲染就地更新）。 */
  result: { current: T };
  /** 强制重渲染一次（依赖变了之后拿新值）。 */
  rerender: () => void;
  /** 卸载：跑 effect 的清理函数（定时器、监听器都该在这里收掉）。 */
  unmount: () => void;
}

export function renderHook<T>(useHook: () => T): RenderHookResult<T> {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  const result = { current: undefined as unknown as T };

  function Probe(): null {
    result.current = useHook();
    return null;
  }

  act(() => {
    root.render(<Probe />);
  });

  return {
    result,
    rerender: () => {
      act(() => {
        root.render(<Probe />);
      });
    },
    unmount: () => {
      act(() => {
        root.unmount();
      });
      container.remove();
    },
  };
}

/** 把挂起的 Promise / 定时器推进完：`await flush()` 之后断言才看得到新状态。 */
export async function flush(times = 3): Promise<void> {
  for (let index = 0; index < times; index += 1) {
    await act(async () => {
      await Promise.resolve();
    });
  }
}

/** 同步调用 hook 上的动作（setText / cancelPending 这类），包进 act 免得漏更新。 */
export function actSync(work: () => void): void {
  act(() => {
    work();
  });
}

/** 异步调用 hook 上的动作（save / reload / confirmPending），等它整条链路走完。 */
export async function actAsync<T>(work: () => Promise<T>): Promise<T> {
  let output!: T;
  await act(async () => {
    output = await work();
  });
  return output;
}
