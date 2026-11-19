import { describe, expect, it } from 'vitest';
import { batchDeviceName, parseBatchNumbers, type CreateDeviceDraft } from './CreateDeviceModal';

/**
 * 批量新建设备的命名规则：设备名 = 批次前缀 + 序号，序号从起始值起算。
 *
 * <p>规则看似简单，但它是清单里的唯一键：算错一位，就会出现「同名两台设备」
 * 或者「点开 A 改的是 B」。这里直接打纯函数，不经过渲染。</p>
 */
function draft(part: Partial<CreateDeviceDraft>): CreateDeviceDraft {
  return {
    name: '', host: '0.0.0.0', port: '5010', deviceId: '0', passive: 'true',
    templateId: 'etch', region: '默认', newRegion: '', startNow: true,
    batch: true, count: '3', prefix: 'dev-', start: '1', portBase: '5100',
    ...part,
  };
}

describe('批量新建设备的命名', () => {
  it('设备名 = 批次前缀 + 序号，序号从起始值起算', () => {
    expect(batchDeviceName('dev-', 1, 0)).toBe('dev-1');
    expect(batchDeviceName('dev-', 1, 2)).toBe('dev-3');
    /* 起始序号不是 1 时也从它起算——换了起始值就该换名字，不能还从 1 开始 */
    expect(batchDeviceName('cell-', 5, 0)).toBe('cell-5');
    expect(batchDeviceName('cell-', 5, 2)).toBe('cell-7');
  });

  it('前缀为空串时名字只剩序号（提交前会被拦下，这里只保证不拼出 NaN）', () => {
    expect(batchDeviceName('', 1, 0)).toBe('1');
  });

  it('中文前缀原样保留：设备名不是路径也不是标识符', () => {
    expect(batchDeviceName('批次-', 1, 0)).toBe('批次-1');
  });
});

describe('批量参数解析', () => {
  it('数量 / 起始序号 / 起始端口都解析成整数', () => {
    expect(parseBatchNumbers(draft({}))).toEqual({
      count: 3, prefix: 'dev-', start: 1, portBase: 5100,
    });
  });

  it('前缀去首尾空白：粘贴带空格的前缀不该变成另一个名字', () => {
    expect(parseBatchNumbers(draft({ prefix: '  dev-  ' }))?.prefix).toBe('dev-');
  });

  it('非数字给 null，由调用方提示', () => {
    expect(parseBatchNumbers(draft({ count: 'abc' }))).toBeNull();
    expect(parseBatchNumbers(draft({ start: '' }))).toBeNull();
    expect(parseBatchNumbers(draft({ portBase: 'x' }))).toBeNull();
  });
});
