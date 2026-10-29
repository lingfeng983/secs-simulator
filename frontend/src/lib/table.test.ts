import { describe, expect, it } from 'vitest';
import { compareValues, filterRows, parseCsv, safeFilename, sortRows, toCsv } from './table';

interface Row {
  id: string;
  count: number;
  label: string;
}

const ROWS: Row[] = [
  { id: 'b', count: 2, label: 'Beta' },
  { id: 'a', count: 10, label: 'alpha' },
  { id: 'c', count: 2, label: 'Gamma' },
];

describe('compareValues', () => {
  it('compares numbers numerically', () => {
    expect(compareValues(2, 10)).toBeLessThan(0);
    expect(compareValues(10, 2)).toBeGreaterThan(0);
  });

  it('compares strings case-insensitively', () => {
    expect(compareValues('alpha', 'Beta')).toBeLessThan(0);
  });

  it('treats missing values as empty strings', () => {
    expect(compareValues(undefined, 'a')).toBeLessThan(0);
  });
});

describe('sortRows', () => {
  const accessor = (row: Row, key: string): unknown =>
    key === 'count' ? row.count : key === 'label' ? row.label : row.id;

  it('returns a copy when there is no sort state', () => {
    const result = sortRows(ROWS, null, accessor);
    expect(result).toEqual(ROWS);
    expect(result).not.toBe(ROWS);
  });

  it('sorts ascending and descending', () => {
    expect(sortRows(ROWS, { key: 'count', direction: 'asc' }, accessor).map((r) => r.id)).toEqual([
      'b',
      'c',
      'a',
    ]);
    expect(sortRows(ROWS, { key: 'count', direction: 'desc' }, accessor).map((r) => r.id)).toEqual([
      'a',
      'b',
      'c',
    ]);
  });

  it('is stable for equal values', () => {
    const sorted = sortRows(ROWS, { key: 'count', direction: 'asc' }, accessor);
    /* b 和 c 的 count 相同，原顺序必须保持 */
    expect(sorted.map((r) => r.id)).toEqual(['b', 'c', 'a']);
  });
});

describe('filterRows', () => {
  const textOf = (row: Row) => `${row.id} ${row.label}`;

  it('returns everything for an empty query', () => {
    expect(filterRows(ROWS, '   ', textOf)).toHaveLength(3);
  });

  it('matches substrings case-insensitively', () => {
    expect(filterRows(ROWS, 'ALPHA', textOf).map((r) => r.id)).toEqual(['a']);
  });

  it('combines multiple terms with AND', () => {
    expect(filterRows(ROWS, 'a alpha', textOf).map((r) => r.id)).toEqual(['a']);
    expect(filterRows(ROWS, 'a zeta', textOf)).toHaveLength(0);
  });
});

describe('toCsv', () => {
  it('writes a header and one line per row', () => {
    const csv = toCsv(ROWS, [
      { header: 'id', value: (row) => row.id },
      { header: 'count', value: (row) => row.count },
    ]);
    const lines = csv.split('\r\n');
    expect(lines[1]).toBe('b,2');
  });

  it('escapes commas, quotes and newlines', () => {
    const csv = toCsv(
      [{ id: 'a,b', count: 1 }],
      [
        { header: 'id', value: (row) => row.id },
        { header: 'count', value: (row) => row.count },
      ],
    );
    expect(csv).toContain('"a,b"');
  });

  it('escapes embedded double quotes by doubling them', () => {
    const csv = toCsv(
      [{ id: 'say "hi"' }],
      [{ header: 'id', value: (row) => row.id }],
    );
    expect(csv).toContain('"say ""hi"""');
  });

  it('renders missing values as empty cells', () => {
    const csv = toCsv(
      [{ id: undefined }],
      [{ header: 'id', value: (row) => row.id }],
    );
    expect(csv.split('\r\n')[1]).toBe('');
  });
});

describe('parseCsv', () => {
  it('round-trips CSV with quotes, commas, CRLF and BOM', () => {
    const text = toCsv(
      [{ id: 'a,b', note: 'say "hi"' }, { id: 'line\nbreak', note: '' }],
      [{ header: 'id', value: (row) => row.id }, { header: 'note', value: (row) => row.note }],
    );
    const rows = parseCsv(text);
    expect(rows[0]).toEqual(['id', 'note']);
    expect(rows[1]).toEqual(['a,b', 'say "hi"']);
    expect(rows[2]).toEqual(['line\nbreak', '']);
  });

  it('handles trailing newline without an extra empty row', () => {
    expect(parseCsv('a,b\r\n1,2\r\n')).toEqual([['a', 'b'], ['1', '2']]);
  });
});

describe('safeFilename', () => {
  it('replaces path-hostile characters', () => {
    expect(safeFilename('a/b:c*d.json')).toBe('a_b_c_d.json');
  });
});
