import { useState, type ReactNode } from 'react';
import { Modal } from '../../shared/ui/Modal';
import type { JsonObject } from './templateConfig';

/** 编辑函数：fn 拿到弹窗里这条数据项（新副本）的活引用。 */
export type ItemEdit = (fn: (target: JsonObject) => void) => void;

/**
 * 配置区列表页的「新建 / 编辑一条」弹窗。
 *
 * <p>弹窗改的是这条数据项的一份副本，点「确定」才交给页面写回草稿，
 * 点「取消」或 Esc 不留痕迹。调用方按需挂载（打开时才渲染），每次打开都从
 * {@code initial} 重新开始。</p>
 */
export function ConfigItemModal({ title, initial, problemOf, onClose, onSubmit, children }: {
  title: string;
  initial: JsonObject;
  /** 当前副本有问题时返回提示文字，「确定」随之禁用；没问题返回 ''。 */
  problemOf?: (item: JsonObject) => string;
  onClose: () => void;
  onSubmit: (item: JsonObject) => void;
  children: (item: JsonObject, mutate: ItemEdit) => ReactNode;
}): JSX.Element {
  const [item, setItem] = useState<JsonObject>(() => structuredClone(initial));
  const mutate: ItemEdit = (fn) => {
    setItem((previous) => {
      const next = structuredClone(previous);
      fn(next);
      return next;
    });
  };
  const problem = problemOf?.(item) ?? '';

  return (
    <Modal
      open
      title={title}
      hint="「确定」只改到本页草稿，点页面底部的「保存」后才写入配置；取消或 Esc 不留痕迹。"
      onClose={onClose}
      width="min(920px, 92vw)"
      footer={
        <>
          <button type="button" onClick={onClose}>取消</button>
          <button
            type="button"
            className="primary"
            disabled={problem !== ''}
            title={problem !== '' ? problem : undefined}
            onClick={() => onSubmit(item)}
          >
            确定
          </button>
        </>
      }
    >
      {children(item, mutate)}
      {problem !== '' && <p className="problems">{problem}</p>}
    </Modal>
  );
}
