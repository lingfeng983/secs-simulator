import { downloadBlob } from './table';

/** 导出实际落到哪里。 */
export type SaveMethod = 'picker' | 'download' | 'cancelled';

/** 一次导出的结果。 */
export interface SaveResult {
  /** 用户是否真的导出了（取消时为 false）。 */
  saved: boolean;
  method: SaveMethod;
  /** 最终文件名（选择器里可能被用户改过）。 */
  name: string;
}

/** File System Access API 的最小形状（TS 内置 lib 尚未覆盖）。 */
interface SaveFilePickerHandle {
  readonly name: string;
  createWritable: () => Promise<{
    write: (data: Blob | string) => Promise<void>;
    close: () => Promise<void>;
  }>;
}

interface SaveFilePickerOptions {
  suggestedName?: string;
  types?: Array<{ description?: string; accept: Record<string, string[]> }>;
}

type SaveFilePicker = (options?: SaveFilePickerOptions) => Promise<SaveFilePickerHandle>;

/** 由扩展名推一个保存对话框能显示的类型。 */
function pickerTypes(name: string): SaveFilePickerOptions['types'] {
  const ext = name.includes('.') ? name.slice(name.lastIndexOf('.') + 1).toLowerCase() : '';
  switch (ext) {
    case 'csv':
      return [{ description: 'CSV 表格', accept: { 'text/csv': ['.csv'] } }];
    case 'json':
      return [{ description: 'JSON 文件', accept: { 'application/json': ['.json'] } }];
    case 'jsonl':
    case 'ndjson':
      return [{ description: 'JSON Lines', accept: { 'application/x-ndjson': ['.jsonl', '.ndjson'] } }];
    case 'txt':
      return [{ description: '文本文件', accept: { 'text/plain': ['.txt'] } }];
    default:
      return undefined;
  }
}

/**
 * 导出文本文件：能弹保存位置就弹，不能就退回浏览器下载。
 *
 * <p>原来所有导出都直接触发下载，用户既不知道文件去了哪，也没法选目录。
 * Chromium/WebView2 支持 File System Access API 时先让用户选位置；
 * 老浏览器/受限 iframe 里 `showSaveFilePicker` 不存在或抛错，则退回下载，
 * 行为与修复前一致，不会因为能力检测失败而导不出来。</p>
 *
 * @param name 建议文件名（含扩展名）
 * @param text 正文
 * @param mime MIME 类型
 * @returns 导出结果（供调用方提示"保存在哪"）
 */
export async function saveTextFile(
  name: string,
  text: string,
  mime = 'text/plain;charset=utf-8',
): Promise<SaveResult> {
  const host = window as unknown as { showSaveFilePicker?: SaveFilePicker };

  if (typeof host.showSaveFilePicker === 'function') {
    try {
      const handle = await host.showSaveFilePicker({
        suggestedName: name,
        types: pickerTypes(name),
      });
      const writable = await handle.createWritable();
      await writable.write(new Blob([text], { type: mime }));
      await writable.close();
      return { saved: true, method: 'picker', name: handle.name };
    } catch (error) {
      if ((error as { name?: string }).name === 'AbortError') {
        return { saved: false, method: 'cancelled', name };
      }
      /* 其它错误（权限、跨源 iframe、能力半残）：退回下载，不能让导出失败 */
    }
  }

  downloadBlob(name, new Blob([text], { type: mime }));
  return { saved: true, method: 'download', name };
}

/**
 * 给用户看的一句"文件去哪了"。
 *
 * @param result 导出结果
 * @returns 提示文案；取消时为空串（不该弹"取消成功"）
 */
export function saveHint(result: SaveResult): string {
  if (!result.saved) {
    return '';
  }
  return result.method === 'picker'
    ? `已保存到「${result.name}」（你选择的位置）`
    : `已开始下载「${result.name}」（浏览器默认下载目录）`;
}
