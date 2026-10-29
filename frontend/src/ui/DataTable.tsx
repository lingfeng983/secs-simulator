import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { filterRows, parseCsv, sortRows, toCsv, type CsvColumn, type SortState } from '../lib/table';
import { saveTextFile, type SaveResult } from '../lib/saveFile';
import { usePersistentState } from '../state/usePersistentState';
import { EmptyState } from './EmptyState';

/** 一列。 */
export interface Column<T> {
  /** 列标识，排序与导出都按它定位。 */
  key: string;
  header: string;
  /** 单元格内容；不给就显示 sortValue 的文本。 */
  render?: (row: T) => ReactNode;
  /** 排序取值；不给就不可排序。 */
  sortValue?: (row: T) => string | number;
  align?: 'left' | 'right' | 'center';
  width?: string;
  className?: string;
}

/** 通用表格：搜索、排序、分页、CSV 导出。 */
export interface DataTableProps<T> {
  columns: readonly Column<T>[];
  rows: readonly T[];
  rowKey: (row: T) => string;
  /** 搜索框占位符；不给就不显示搜索框。 */
  searchPlaceholder?: string;
  /** 搜索用的文本；不给就用各列的 sortValue 拼。 */
  searchText?: (row: T) => string;
  /** 搜索框旁边的额外控件。 */
  toolbar?: ReactNode;
  emptyText?: string;
  /** 给了就显示"导出 CSV"按钮，值是文件名（不含扩展名）。 */
  exportName?: string;
  /** 导出完成后的回调：调用方用它提示"文件保存在哪"。 */
  onExported?: (result: SaveResult) => void;
  /** 导出的列；不给就用 columns（render 不可序列化，必须另给）。 */
  exportColumns?: readonly CsvColumn<T>[];
  /** 给了就显示"导入 CSV"按钮；参数是按表头映射后的行。 */
  onImport?: (rows: Record<string, string>[]) => void | Promise<void>;
  /** 导入按钮的说明（默认按导出时的表头解析）。 */
  importHint?: string;
  /** 每行的附加类名，用来标异常行。 */
  rowClassName?: (row: T) => string | undefined;
  /** 默认排序。 */
  defaultSort?: SortState;
  /** 每页条数；不给就不分页。 */
  pageSize?: number;
  /** 表头右侧显示的合计行数。 */
  caption?: string;
  /** 表格标识；给了就启用列宽拖拽，并把列宽记进 localStorage。 */
  tableKey?: string;
  /** 行点击(下钻);给了就整行可点、指针变手型。 */
  onRowClick?: (row: T) => void;
}

/**
 * 通用表格。
 *
 * <p>之前每个面板各写一遍 <table>，搜索和排序就没法统一：
 * 想在会话表里搜一个名字，只能靠眼睛扫。现在这些能力收敛到一处，
 * 新面板直接白拿。</p>
 */
export function DataTable<T>({
  columns,
  rows,
  rowKey,
  searchPlaceholder,
  searchText,
  toolbar,
  emptyText = '暂无数据',
  exportName,
  exportColumns,
  onExported,
  onImport,
  importHint,
  rowClassName,
  defaultSort,
  pageSize,
  caption,
  tableKey,
  onRowClick,
}: DataTableProps<T>): JSX.Element {
  const [query, setQuery] = useState('');
  const [sort, setSort] = useState<SortState | null>(defaultSort ?? null);
  const [page, setPage] = useState(0);
  /* 列宽（px）：拖拽手柄更新；键为列 key。
     有 tableKey 才持久化；没有时只存组件内——用一个共享假键会让两张匿名表互相踩列宽。 */
  const [localWidths, setLocalWidths] = useState<Record<string, number>>({});
  const [persistedWidths, setPersistedWidths] = usePersistentState<Record<string, number>>(
    tableKey === undefined ? 'secs-sim-colw-__scratch__' : `secs-sim-colw-${tableKey}`,
    {},
  );
  /* 拖动期间只写组件内的 dragWidths；mouseup 才落一次 localStorage。
     原来的每像素一次写盘会在 JSON 序列化 + 同步 IO 上拖慢拖动。 */
  const [dragWidths, setDragWidths] = useState<Record<string, number>>({});
  const baseWidths = tableKey === undefined ? localWidths : persistedWidths;
  const persistWidths = tableKey === undefined ? setLocalWidths : setPersistedWidths;
  const columnWidths = { ...baseWidths, ...dragWidths };
  const baseWidthsRef = useRef(baseWidths);
  baseWidthsRef.current = baseWidths;
  const dragWidthsRef = useRef(dragWidths);
  dragWidthsRef.current = dragWidths;
  const resizing = useRef<{ key: string; startX: number; startWidth: number } | null>(null);
  const importRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (tableKey === undefined) {
      return;
    }
    const onMove = (event: MouseEvent) => {
      const state = resizing.current;
      if (state === null) {
        return;
      }
      const next = Math.max(48, Math.round(state.startWidth + event.clientX - state.startX));
      setDragWidths((previous) => ({ ...previous, [state.key]: next }));
    };
    const onUp = () => {
      if (resizing.current === null) {
        return;
      }
      resizing.current = null;
      document.body.style.cursor = '';
      /* 落一次盘：把拖动期间的临时宽度合并进持久化状态 */
      persistWidths({ ...baseWidthsRef.current, ...dragWidthsRef.current });
      setDragWidths({});
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
  }, [tableKey, persistWidths]);

  const startResize = useCallback((key: string, event: React.MouseEvent) => {
    event.preventDefault();
    event.stopPropagation();
    const th = (event.target as HTMLElement).closest('th');
    if (th === null) {
      return;
    }
    resizing.current = { key, startX: event.clientX, startWidth: th.getBoundingClientRect().width };
    document.body.style.cursor = 'col-resize';
  }, []);

  const textOf = useMemo(() => {
    if (searchText !== undefined) {
      return searchText;
    }
    const sortable = columns.filter((column) => column.sortValue !== undefined);
    return (row: T) =>
      sortable
        .map((column) => String(column.sortValue?.(row) ?? ''))
        .join(' ');
  }, [columns, searchText]);

  const sortAccessor = useMemo(
    () => (row: T, key: string) => {
      const column = columns.find((item) => item.key === key);
      return column?.sortValue?.(row) ?? '';
    },
    [columns],
  );

  const filtered = useMemo(() => filterRows(rows, query, textOf), [rows, query, textOf]);
  const sorted = useMemo(() => sortRows(filtered, sort, sortAccessor), [filtered, sort, sortAccessor]);

  const pageCount = pageSize === undefined ? 1 : Math.max(1, Math.ceil(sorted.length / pageSize));
  const safePage = Math.min(page, pageCount - 1);
  const visible =
    pageSize === undefined ? sorted : sorted.slice(safePage * pageSize, safePage * pageSize + pageSize);

  const toggleSort = (key: string) => {
    setSort((previous) => {
      if (previous?.key !== key) {
        return { key, direction: 'asc' };
      }
      return previous.direction === 'asc' ? { key, direction: 'desc' } : null;
    });
    setPage(0);
  };

  const onExport = async () => {
    if (exportName === undefined) {
      return;
    }
    const columnsForCsv =
      exportColumns ??
      columns
        .filter((column) => column.sortValue !== undefined)
        .map((column) => ({
          header: column.header,
          value: (row: T) => column.sortValue?.(row) ?? '',
        }));
    /* 弹保存位置（支持时）并把结果交给调用方提示；取消不提示 */
    const result = await saveTextFile(
      `${exportName}.csv`,
      toCsv(sorted, columnsForCsv),
      'text/csv;charset=utf-8',
    );
    onExported?.(result);
  };

  const searching = query.trim() !== '';

  const importCsv = async (file: File) => {
    if (onImport === undefined) {
      return;
    }
    try {
      const table = parseCsv(await file.text());
      if (table.length < 2) {
        throw new Error('CSV 没有数据行');
      }
      const headers = table[0].map((header) => header.trim());
      const records = table.slice(1)
        .filter((row) => row.some((cell) => cell.trim() !== ''))
        .map((row) => Object.fromEntries(headers.map((header, index) => [header, row[index] ?? ''])));
      await onImport(records);
    } catch (error) {
      window.alert(`导入失败：${(error as Error).message}`);
    }
  };

  return (
    <div className="table-wrap">
      {(searchPlaceholder !== undefined || toolbar !== undefined || exportName !== undefined || onImport !== undefined) && (
        <div className="table-toolbar">
          {searchPlaceholder !== undefined && (
            <input
              type="search"
              className="table-search"
              placeholder={searchPlaceholder}
              value={query}
              onChange={(event) => {
                setQuery(event.target.value);
                setPage(0);
              }}
              aria-label={searchPlaceholder}
            />
          )}
          {toolbar}
          <span className="table-count">
            {searching ? `${filtered.length} / ${rows.length}` : `${rows.length}`} 条
          </span>
          {exportName !== undefined && (
            <button type="button" onClick={() => void onExport()} title="导出当前筛选结果为 CSV（可选择保存位置）">
              导出 CSV
            </button>
          )}
          {onImport !== undefined && (
            <>
              <button
                type="button"
                onClick={() => importRef.current?.click()}
                title={importHint ?? '按导出时的表头导入 CSV'}
              >
                导入 CSV
              </button>
              <input
                ref={importRef}
                type="file"
                accept=".csv,text/csv"
                style={{ display: 'none' }}
                onChange={(event) => {
                  const file = event.target.files?.[0];
                  event.target.value = '';
                  if (file !== undefined) {
                    void importCsv(file);
                  }
                }}
              />
            </>
          )}
        </div>
      )}

      {visible.length === 0 ? (
        <EmptyState text={searching ? `没有匹配「${query}」的行` : emptyText} />
      ) : (
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                {columns.map((column) => {
                  const active = sort?.key === column.key;
                  const sortable = column.sortValue !== undefined;
                  return (
                    <th
                      key={column.key}
                      style={{
                        ...(column.width === undefined ? {} : { width: column.width }),
                        ...(columnWidths[column.key] === undefined ? {} : { width: columnWidths[column.key] }),
                        ...(tableKey === undefined ? {} : { position: 'relative' }),
                      }}
                      className={[
                        column.align === undefined ? '' : `align-${column.align}`,
                        column.className ?? '',
                        sortable ? 'sortable' : '',
                        active ? 'sorted' : '',
                      ]
                        .filter((item) => item !== '')
                        .join(' ')}
                      aria-sort={
                        sortable
                          ? active
                            ? sort?.direction === 'asc' ? 'ascending' : 'descending'
                            : 'none'
                          : undefined
                      }
                      /* 可排序表头本身就是按钮：键盘用户也要能排序，
                         所以给 tabIndex/role 和 Enter/Space 处理 */
                      role={sortable ? 'button' : undefined}
                      tabIndex={sortable ? 0 : undefined}
                      onClick={sortable ? () => toggleSort(column.key) : undefined}
                      onKeyDown={
                        sortable
                          ? (event) => {
                              if (event.key === 'Enter' || event.key === ' ') {
                                /* 空格默认会滚动页面，必须拦掉 */
                                event.preventDefault();
                                toggleSort(column.key);
                              }
                            }
                          : undefined
                      }
                    >
                      {column.header}
                      {sortable && (
                        <span className="sort-mark" aria-hidden="true">
                          {active ? (sort?.direction === 'asc' ? ' ▲' : ' ▼') : ' ⇅'}
                        </span>
                      )}
                      {tableKey !== undefined && (
                        <span
                          className="col-resize"
                          role="separator"
                          aria-orientation="vertical"
                          onMouseDown={(event) => startResize(column.key, event)}
                          onDoubleClick={() => {
                            /* 双击手柄恢复该列默认宽 */
                            persistWidths(() => {
                              const next = { ...baseWidthsRef.current };
                              delete next[column.key];
                              return next;
                            });
                            setDragWidths((previous) => {
                              const next = { ...previous };
                              delete next[column.key];
                              return next;
                            });
                          }}
                          title="拖动调整列宽，双击恢复默认"
                        />
                      )}
                    </th>
                  );
                })}
              </tr>
            </thead>
            <tbody>
              {visible.map((row) => (
                <tr
                  key={rowKey(row)}
                  className={rowClassName?.(row)}
                  onClick={onRowClick === undefined ? undefined : () => onRowClick(row)}
                  style={onRowClick === undefined ? undefined : { cursor: 'pointer' }}
                >
                  {columns.map((column) => (
                    <td
                      key={column.key}
                      className={[
                        column.align === undefined ? '' : `align-${column.align}`,
                        column.className ?? '',
                      ]
                        .filter((item) => item !== '')
                        .join(' ')}
                    >
                      {column.render === undefined
                        ? String(column.sortValue?.(row) ?? '')
                        : column.render(row)}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {pageSize !== undefined && pageCount > 1 && (
        <div className="table-pager">
          <button type="button" onClick={() => setPage(0)} disabled={safePage === 0}>
            « 首页
          </button>
          <button type="button" onClick={() => setPage(safePage - 1)} disabled={safePage === 0}>
            ‹ 上一页
          </button>
          <span>
            第 {safePage + 1} / {pageCount} 页
          </span>
          <button type="button" onClick={() => setPage(safePage + 1)} disabled={safePage >= pageCount - 1}>
            下一页 ›
          </button>
          <button type="button" onClick={() => setPage(pageCount - 1)} disabled={safePage >= pageCount - 1}>
            末页 »
          </button>
        </div>
      )}

      {caption !== undefined && <p className="table-caption">{caption}</p>}
    </div>
  );
}
