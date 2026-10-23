import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { DataTable, type Column } from './DataTable';

interface Row {
  id: string;
  count: number;
}

const ROWS: Row[] = [
  { id: 'beta', count: 2 },
  { id: 'alpha', count: 10 },
];

const COLUMNS: Column<Row>[] = [
  { key: 'id', header: '标识', sortValue: (row) => row.id },
  { key: 'count', header: '计数', align: 'right', sortValue: (row) => row.count },
];

describe('DataTable', () => {
  it('renders a header per column and a row per record', () => {
    const html = renderToStaticMarkup(
      <DataTable columns={COLUMNS} rows={ROWS} rowKey={(row) => row.id} />,
    );

    expect(html).toContain('标识');
    expect(html).toContain('计数');
    expect(html).toContain('beta');
    expect(html).toContain('alpha');
  });

  it('shows the empty state when there is nothing to show', () => {
    const html = renderToStaticMarkup(
      <DataTable columns={COLUMNS} rows={[]} rowKey={(row) => row.id} emptyText="什么都没有" />,
    );

    expect(html).toContain('什么都没有');
    expect(html).not.toContain('beta');
  });

  it('marks sortable columns so users can tell they can sort', () => {
    const html = renderToStaticMarkup(
      <DataTable columns={COLUMNS} rows={ROWS} rowKey={(row) => row.id} />,
    );

    expect(html).toContain('sortable');
  });

  it('falls back to the sort value when a column has no custom renderer', () => {
    const html = renderToStaticMarkup(
      <DataTable columns={COLUMNS} rows={ROWS} rowKey={(row) => row.id} />,
    );

    expect(html).toContain('>10<');
  });

  it('renders the search box only when a placeholder is given', () => {
    const withSearch = renderToStaticMarkup(
      <DataTable columns={COLUMNS} rows={ROWS} rowKey={(row) => row.id} searchPlaceholder="搜索" />,
    );
    const withoutSearch = renderToStaticMarkup(
      <DataTable columns={COLUMNS} rows={ROWS} rowKey={(row) => row.id} />,
    );

    expect(withSearch).toContain('type="search"');
    expect(withoutSearch).not.toContain('type="search"');
  });
});
