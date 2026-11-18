import { describe, expect, it } from 'vitest';
import { buildPaletteRows } from './CommandPalette';
import type { CommandItem } from '../lib/command';

function command(id: string, label: string, group: string): CommandItem {
  return { id, label, group, run: () => undefined };
}

describe('buildPaletteRows', () => {
  it('把同一台设备的三条命令收成一行 + 两个分支', () => {
    const device = command('device:E01', '打开设备 ETCH01（E01）', '设备');
    const config = command('device-page:E01:config', 'E01 · 配置区', '设备');
    const runtime = command('device-page:E01:runtime', 'E01 · 运行区', '设备');

    const rows = buildPaletteRows([device, config, runtime]);

    expect(rows).toHaveLength(1);
    expect(rows[0].primary).toBe(device);
    expect(rows[0].branches.map((branch) => branch.label)).toEqual(['配置区', '运行区']);
    expect(rows[0].branches.map((branch) => branch.item)).toEqual([config, runtime]);
  });

  it('分支顺序固定：配置区在前、运行区在后，不随命中顺序变', () => {
    const device = command('device:E01', '打开设备 ETCH01（E01）', '设备');
    const runtime = command('device-page:E01:runtime', 'E01 · 运行区', '设备');
    const config = command('device-page:E01:config', 'E01 · 配置区', '设备');

    const rows = buildPaletteRows([runtime, device, config]);

    expect(rows).toHaveLength(1);
    expect(rows[0].branches.map((branch) => branch.label)).toEqual(['配置区', '运行区']);
  });

  it('只剩主命令时仍是一行，但没有分支按钮', () => {
    const device = command('device:E01', '打开设备 ETCH01（E01）', '设备');

    const rows = buildPaletteRows([device]);

    expect(rows).toHaveLength(1);
    expect(rows[0].branches).toEqual([]);
  });

  it('没配对到主命令的分支保留成普通行，不会凭空消失', () => {
    const config = command('device-page:E01:config', 'E01 · 配置区', '设备');

    const rows = buildPaletteRows([config]);

    expect(rows).toHaveLength(1);
    expect(rows[0].primary).toBe(config);
    expect(rows[0].branches).toEqual([]);
  });

  it('普通命令原样保留，多台设备各自成行', () => {
    const tab = command('tab:workbench', '前往 设备清单', '跳转');
    const a = command('device:A', '打开设备 A', '设备');
    const aConfig = command('device-page:A:config', 'A · 配置区', '设备');
    const aRuntime = command('device-page:A:runtime', 'A · 运行区', '设备');
    const b = command('device:B', '打开设备 B', '设备');
    const bConfig = command('device-page:B:config', 'B · 配置区', '设备');

    const rows = buildPaletteRows([tab, a, aConfig, aRuntime, b, bConfig]);

    expect(rows).toHaveLength(3);
    expect(rows[0].primary).toBe(tab);
    expect(rows[1].primary).toBe(a);
    expect(rows[1].branches).toHaveLength(2);
    expect(rows[2].primary).toBe(b);
    expect(rows[2].branches.map((branch) => branch.label)).toEqual(['配置区']);
  });
});
