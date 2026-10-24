import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { downloadText, filterRows, sortRows, toCsv, type CsvColumn, type SortState } from '../lib/table';
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
  /** 导出的列；不给就用 columns（render 不可序列化，必须另给）。 */
  exportColumns?: readonly CsvColumn<T>[];
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
  rowClassName,
  defaultSort,
  pageSize,
  caption,
  tableKey,
}: DataTableProps<T>): JSX.Element {
  const [query, setQuery] = useState('');
  const [sort, setSort] = useState<SortState | null>(defaultSort ?? null);
  const [page, setPage] = useState(0);
  /* 列宽（px）：拖拽手柄更新；键为列 key。持久化键按表隔离。 */
  const widthsKey = tableKey === undefined ? '' : `secs-sim-colw-${tableKey}`;
  const [columnWidths, setColumnWidths] = usePersistentState<Record<string, number>>(
    widthsKey === '' ? 'secs-sim-colw-none' : widthsKey,
    {},
  );
  const resizing = useRef<{ key: string; startX: number; startWidth: number } | null>(null);

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
      setColumnWidths((previous) => ({ ...previous, [state.key]: next }));
    };
    const onUp = () => {
      resizing.current = null;
      document.body.style.cursor = '';
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
  }, [tableKey, setColumnWidths]);

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

  const onExport = () => {
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
    downloadText(`${exportName}.csv`, toCsv(sorted, columnsForCsv), 'text/csv;charset=utf-8');
  };

  const searching = query.trim() !== '';

  return (
    <div className="table-wrap">
      {(searchPlaceholder !== undefined || toolbar !== undefined || exportName !== undefined) && (
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
            <button type="button" onClick={onExport} title="导出当前筛选结果为 CSV">
              导出 CSV
            </button>
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
                      aria-sort={active ? (sort?.direction === 'asc' ? 'ascending' : 'descending') : undefined}
                      onClick={sortable ? () => toggleSort(column.key) : undefined}
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
                            setColumnWidths((previous) => {
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
                <tr key={rowKey(row)} className={rowClassName?.(row)}>
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
