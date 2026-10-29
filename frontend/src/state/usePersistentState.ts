import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * 与 localStorage 同步的状态。
 *
 * <p>读取时必须容错：localStorage 可能被策略禁用、可能存了半截 JSON、
 * 也可能存的是旧版本的结构。任何一种都不该让整个界面白屏，
 * 所以解析失败一律回退到默认值。</p>
 *
 * @param key 存储键
 * @param initial 默认值（同时作为解析失败时的回退）
 */
export function usePersistentState<T>(key: string, initial: T): [T, (value: T | ((previous: T) => T)) => void] {
  const [value, setValue] = useState<T>(() => {
    try {
      const raw = window.localStorage.getItem(key);
      if (raw === null) {
        return initial;
      }
      const parsed = JSON.parse(raw) as T;
      return parsed === null || parsed === undefined ? initial : parsed;
    } catch {
      return initial;
    }
  });

  const update = useCallback(
    (next: T | ((previous: T) => T)) => {
      setValue((previous) => {
        const resolved =
          typeof next === 'function' ? (next as (previous: T) => T)(previous) : next;
        try {
          window.localStorage.setItem(key, JSON.stringify(resolved));
        } catch {
          /* 隐私模式或配额满：状态照常用，只是不落盘 */
        }
        return resolved;
      });
    },
    [key],
  );

  /* 多标签页：别的标签页改了同一个键，这里跟着换 */
  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (event.key !== key || event.newValue === null) {
        return;
      }
      try {
        const parsed = JSON.parse(event.newValue) as T;
        setValue(parsed);
      } catch {
        /* 别的标签页写了非法内容，忽略 */
      }
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, [key]);

  return [value, update];
}
