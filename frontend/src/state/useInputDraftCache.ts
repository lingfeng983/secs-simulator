import { useEffect, useRef } from 'react';

/**
 * 全局输入草稿缓存（需求：切换页签/页面后回到原页，输入还在）。
 *
 * <p>实现为 DOM 级缓存而不是让每个面板各自改 useState：整个界面有上百个
 * 输入控件，逐个迁移既容易漏，也会把"保存草稿"这件事散落到业务组件里。
 * 这里按「路由范围 + 控件稳定标识」把值写进 localStorage，页面重新挂载后
 * 再把值回填给 React 受控控件（用原型上的原生 setter 绕开 React 的 value
 * tracker，再派发 input/change，React 的 onChange 会正常收到）。</p>
 *
 * <p>只在 `.content` 范围内生效：顶栏、右侧消息监控、弹窗里的临时输入不缓存。
 * 同一路由内用户亲手改过的控件会记进 touched，异步数据回填时不会被草稿
 * 反过来覆盖。</p>
 */

const STORE_KEY = 'secs.ui.input-drafts.v1';

type DraftPage = Record<string, string>;
type DraftStore = Record<string, DraftPage>;

/** 面板里可缓存的表单控件。 */
type DraftElement = HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement;

function readStore(): DraftStore {
  try {
    const raw = window.localStorage.getItem(STORE_KEY);
    if (raw === null) {
      return {};
    }
    const parsed = JSON.parse(raw) as DraftStore;
    return typeof parsed === 'object' && parsed !== null ? parsed : {};
  } catch {
    return {};
  }
}

function writeStore(store: DraftStore): void {
  try {
    window.localStorage.setItem(STORE_KEY, JSON.stringify(store));
  } catch {
    /* 隐私模式 / 配额满：缓存降级为"本次会话内不保留"，不影响功能 */
  }
}

function isDraftable(node: EventTarget | null): node is DraftElement {
  if (!(node instanceof HTMLElement)) {
    return false;
  }
  if (node.dataset.draftSkip !== undefined) {
    return false;
  }
  if (node instanceof HTMLInputElement) {
    return !['file', 'password', 'hidden', 'submit', 'button', 'reset', 'image'].includes(node.type);
  }
  return node instanceof HTMLTextAreaElement || node instanceof HTMLSelectElement;
}

function readValue(element: DraftElement): string {
  if (element instanceof HTMLInputElement && (element.type === 'checkbox' || element.type === 'radio')) {
    return element.checked ? 'true' : 'false';
  }
  return element.value;
}

/** 控件标识：优先用组件显式给的 data-draft-key，否则用语义属性 + 同类序号兜底。 */
function baseKey(element: DraftElement): string {
  const explicit = element.dataset.draftKey;
  if (explicit !== undefined && explicit !== '') {
    return `key:${explicit}`;
  }
  const tag = element.tagName.toLowerCase();
  const type = element instanceof HTMLInputElement ? element.type : tag;
  const label = element.getAttribute('aria-label')
    ?? element.getAttribute('name')
    ?? element.id
    ?? element.getAttribute('placeholder')
    ?? '';
  return `${tag}[${type}]:${label}`;
}

function allDraftElements(root: HTMLElement): DraftElement[] {
  return Array.from(root.querySelectorAll<HTMLElement>('input, textarea, select'))
    .filter(isDraftable);
}

function elementKey(root: HTMLElement, element: DraftElement): string {
  const base = baseKey(element);
  const same = allDraftElements(root).filter((candidate) => baseKey(candidate) === base);
  return `${base}#${Math.max(0, same.indexOf(element))}`;
}

/** 回填一个值，并派发 React 能识别的事件。 */
function applyValue(element: DraftElement, value: string): void {
  if (element instanceof HTMLInputElement && (element.type === 'checkbox' || element.type === 'radio')) {
    const wanted = value === 'true';
    if (element.checked !== wanted) {
      /* React 的 checkbox onChange 由真实 click 驱动；这里只用 click 对齐状态 */
      element.click();
    }
    return;
  }
  const prototype = element instanceof HTMLTextAreaElement
    ? HTMLTextAreaElement.prototype
    : element instanceof HTMLSelectElement
      ? HTMLSelectElement.prototype
      : HTMLInputElement.prototype;
  const setter = Object.getOwnPropertyDescriptor(prototype, 'value')?.set;
  if (setter !== undefined) {
    setter.call(element, value);
  } else {
    element.value = value;
  }
  element.dispatchEvent(new Event(
    element instanceof HTMLSelectElement ? 'change' : 'input',
    { bubbles: true },
  ));
}

/**
 * @param scopeKey 当前页面范围（一般是路由 key，含设备/主机 id 与子页）
 */
export function useInputDraftCache(scopeKey: string): void {
  const scopeRef = useRef(scopeKey);
  scopeRef.current = scopeKey;
  /** 本次访问里用户亲手改过的控件：异步回填不得覆盖它们。 */
  const touched = useRef<Set<string>>(new Set());
  /** 回填动作自己派发的事件不能再被当成"用户输入"。 */
  const restoring = useRef(false);

  useEffect(() => {
    const onEdit = (event: Event) => {
      const element = event.target;
      if (restoring.current || !isDraftable(element)) {
        return;
      }
      const root = document.querySelector<HTMLElement>('.content');
      if (root === null || !root.contains(element)) {
        return;
      }
      const scope = scopeRef.current;
      const key = elementKey(root, element);
      touched.current.add(`${scope}::${key}`);
      const store = readStore();
      const page = store[scope] ?? {};
      page[key] = readValue(element);
      store[scope] = page;
      writeStore(store);
    };
    document.addEventListener('input', onEdit, true);
    document.addEventListener('change', onEdit, true);
    return () => {
      document.removeEventListener('input', onEdit, true);
      document.removeEventListener('change', onEdit, true);
    };
  }, []);

  useEffect(() => {
    touched.current.clear();
    const scope = scopeRef.current;
    const restore = () => {
      const root = document.querySelector<HTMLElement>('.content');
      if (root === null) {
        return;
      }
      const page = readStore()[scope] ?? {};
      for (const element of allDraftElements(root)) {
        const key = elementKey(root, element);
        if (touched.current.has(`${scope}::${key}`)) {
          continue;
        }
        const saved = page[key];
        if (saved === undefined || readValue(element) === saved) {
          continue;
        }
        restoring.current = true;
        try {
          applyValue(element, saved);
        } finally {
          restoring.current = false;
        }
      }
    };
    /* 三次回填：DOM 挂载后、面板异步取数后、异步取数稍慢的情况 */
    const timers = [
      window.setTimeout(restore, 0),
      window.setTimeout(restore, 200),
      window.setTimeout(restore, 700),
    ];
    return () => timers.forEach((timer) => window.clearTimeout(timer));
  }, [scopeKey]);
}
