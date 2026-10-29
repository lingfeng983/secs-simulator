import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { formatJson, tokenizeJson } from '../lib/jsonHighlight';

export interface JsonEditorProps {
  value: string;
  onChange: (next: string) => void;
  ariaLabel: string;
}

/**
 * JSON 原文编辑器：高亮 + 格式化 + 占满可用高度。
 *
 * <p>实现是"透明 textarea 叠在高亮 pre 上"：输入、光标、选择仍由原生
 * textarea 负责，pre 只负责上色；两层共用同一套字体/内边距/滚动位置，
 * 所以缩放、换行、横向滚动都不会错位。</p>
 */
export function JsonEditor({ value, onChange, ariaLabel }: JsonEditorProps): JSX.Element {
  const rootRef = useRef<HTMLDivElement | null>(null);
  const preRef = useRef<HTMLPreElement | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const [error, setError] = useState('');
  /** 上一次写入的高度；ResizeObserver 回调和 fit 互相触发时用它收敛。 */
  const lastHeight = useRef(0);

  const tokens = useMemo(() => tokenizeJson(value), [value]);
  const lineCount = useMemo(() => value.split('\n').length, [value]);

  /* 让编辑区正好占满"当前窗体里剩下的高度"：先量它距顶部的距离，
     再扣掉它后面的提示/差异/统计块，剩下的都给它；超出部分内部滚动。
     用 useCallback 固定引用，监听只绑一次，不再每次渲染重绑。 */
  const fit = useCallback(() => {
    const element = rootRef.current;
    if (element === null) {
      return;
    }
    const top = element.getBoundingClientRect().top;
    let below = 64;
    let sibling = element.nextElementSibling;
    while (sibling !== null) {
      below += sibling.getBoundingClientRect().height + 10;
      sibling = sibling.nextElementSibling;
    }
    const available = window.innerHeight - top - below;
    const next = Math.max(280, Math.round(available));
    /* 高度没变就别再写 style：否则 ResizeObserver 会自己触发自己 */
    if (Math.abs(next - lastHeight.current) < 1) {
      return;
    }
    lastHeight.current = next;
    element.style.height = `${next}px`;
  }, []);

  /* 只在挂载时绑定一次：窗口 resize + 外层容器尺寸变化（ResizeObserver） */
  useEffect(() => {
    fit();
    window.addEventListener('resize', fit);
    const parent = rootRef.current?.parentElement ?? null;
    const observer =
      parent !== null && typeof ResizeObserver !== 'undefined'
        ? new ResizeObserver(() => fit())
        : null;
    if (observer !== null && parent !== null) {
      observer.observe(parent);
    }
    return () => {
      window.removeEventListener('resize', fit);
      observer?.disconnect();
    };
  }, [fit]);

  /* 内容或提示变化会改变下方兄弟节点的高度，渲染完补量一次 */
  useEffect(() => {
    fit();
    const timer = window.setTimeout(fit, 120);
    return () => {
      window.clearTimeout(timer);
    };
  }, [fit, value, error]);

  const syncScroll = () => {
    const pre = preRef.current;
    const textarea = textareaRef.current;
    if (pre !== null && textarea !== null) {
      pre.scrollTop = textarea.scrollTop;
      pre.scrollLeft = textarea.scrollLeft;
    }
  };

  const format = () => {
    const formatted = formatJson(value);
    if (formatted === null) {
      setError('JSON 不合法，无法格式化');
      return;
    }
    setError('');
    onChange(formatted);
    /* 格式化后回顶部，避免沿用旧滚动位置 */
    window.setTimeout(() => {
      if (textareaRef.current !== null) {
        textareaRef.current.scrollTop = 0;
        textareaRef.current.scrollLeft = 0;
        syncScroll();
      }
    }, 0);
    if (preRef.current !== null) {
      preRef.current.scrollTop = 0;
      preRef.current.scrollLeft = 0;
    }
  };

  return (
    <div className="config-json-editor" ref={rootRef}>
      <div className="config-json-editor-toolbar">
        <span className="hint-inline">JSON · {lineCount} 行</span>
        <span className="spacer" />
        {error !== '' && <span className="danger">{error}</span>}
        <button type="button" onClick={format} title="按 2 空格缩进重新排版（不合法时保持原文）">
          格式化
        </button>
      </div>
      <div className="config-json-editor-body">
        <pre ref={preRef} aria-hidden="true">
          {tokens.map((token, index) => (
            <span key={index} className={`json-${token.kind}`}>{token.text}</span>
          ))}
        </pre>
        <textarea
          ref={textareaRef}
          className="config-editor"
          spellCheck={false}
          wrap="off"
          value={value}
          onChange={(event) => {
            setError('');
            onChange(event.target.value);
          }}
          onScroll={syncScroll}
          aria-label={ariaLabel}
        />
      </div>
    </div>
  );
}
