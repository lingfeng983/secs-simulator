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
 *
 * <p>{@code problemBanner} 关掉时，问题只体现在「确定」按钮的禁用与悬浮说明上——
 * "必填"这类一眼就能看出来的约束，用标题上的星号说就够了，不必在弹框里
 * 常驻一条红条（用户还没动手就先看到一片红）。</p>
 *
 * <p>全配置区的编辑模型统一为「行内快改 + 弹窗精修」：表格行内只留 offset、
 * 名称这类单字段快改；曲线 / 表达式 / 值定义、引用勾选、跨字段联动这些复杂值
 * 定义统一进本弹窗精修。新增入口统一叫「＋ 新建X」，逐条编辑入口统一叫
 * 「编辑」，弹窗标题统一为「新建X」/「编辑X · &lt;名字&gt;」，避免各页各写一套。</p>
 */
export function ConfigItemModal({ title, initial, problemOf, problemBanner = true, onClose, onSubmit, children }: {
  title: string;
  initial: JsonObject;
  /** 当前副本有问题时返回提示文字，「确定」随之禁用；没问题返回 ''。 */
  problemOf?: (item: JsonObject) => string;
  /** 是否把问题显示成弹框底部的红色横幅（默认显示）。 */
  problemBanner?: boolean;
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
      {problemBanner && problem !== '' && <p className="problems">{problem}</p>}
    </Modal>
  );
}
