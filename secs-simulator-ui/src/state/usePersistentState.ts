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

  const initialRef = useRef(initial);

  const update = useCallback(
    (next: T | ((previous: T) => T)) => {
      /* 只改状态，落盘放到 effect 里：setState 的 updater 必须是纯函数，
         在并发渲染下可能被调用多次、也可能算出一个最终没被提交的结果。
         把 localStorage 写在里面会出现"写了一个界面上并不存在的值"。 */
      setValue((previous) => (
        typeof next === 'function' ? (next as (previous: T) => T)(previous) : next
      ));
    },
    [],
  );

  /* 提交之后再落盘（读回来的与界面上看到的一定是同一个值） */
  useEffect(() => {
    try {
      window.localStorage.setItem(key, JSON.stringify(value));
    } catch {
      /* 隐私模式或配额满：状态照常用，只是不落盘 */
    }
  }, [key, value]);

  /* 多标签页：别的标签页改了同一个键，这里跟着换 */
  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (event.key !== key) {
        return;
      }
      if (event.newValue === null) {
        /* 另一个标签页把这个键删了：跟着回默认值。以前这里直接 return，
           结果本标签页会一直停在一个已经不存在的设置上。 */
        setValue(initialRef.current);
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
