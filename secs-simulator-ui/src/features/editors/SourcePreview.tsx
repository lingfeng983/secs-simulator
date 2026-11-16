import { useMemo, useState } from 'react';
import { Modal } from '../../shared/ui/Modal';
import { ParamCurveChart } from '../charts/ParamCurveChart';
import {
  SIM_DURATION_CHOICES,
  formatSimTime,
  formatSimValue,
  simulateSource,
} from './paramTemplateSimulation';
import { describeSource, type JsonObject } from './templateConfig';

/**
 * 一条数据项的值定义预览：按当前草稿在本地试跑一遍，画出随时间的变化。
 *
 * <p>不连后端、不写任何东西。曲线语义与运行时同一套（8 类曲线、表达式、
 * 私有曲线），随机类曲线用确定性噪声——换「重新模拟」只换种子。</p>
 */
export function SourcePreview({ open, title, def, autoDurationMs, axisNote, onClose }: {
  open: boolean;
  title: string;
  /** 「自动」时长的固定窗口；不给就按曲线周期估算（下限 30s）。 */
  autoDurationMs?: number;
  /** 横轴说明；不给就按片内相位说明。 */
  axisNote?: string;
  /** 带 source 的数据项（SVID / 常量 / 属性都行；文本型没有曲线）。 */
  def: JsonObject;
  onClose: () => void;
}): JSX.Element {
  const [durationChoice, setDurationChoice] = useState(0);
  const [seed, setSeed] = useState(1);

  const source = useMemo(
    () => (def.source !== null && typeof def.source === 'object' ? def.source as JsonObject : {}),
    [def],
  );
  const simulation = useMemo(
    () => simulateSource(source, { durationMs: durationChoice === 0 ? autoDurationMs : durationChoice, seed }),
    [source, durationChoice, autoDurationMs, seed],
  );

  return (
    <Modal
      open={open}
      title={`预览 · ${title}`}
      hint={`${axisNote ?? '横轴是片内相位（有 cycle 段时每片重放）'}，纵轴是这条值定义的输出（${simulation.shape}）。这是示意：运行时噪声种子由会话与数据项派生。`}
      onClose={onClose}
      width="min(880px, 94vw)"
      footer={<button type="button" onClick={onClose}>关闭</button>}
    >
      <div className="param-preview">
        <div className="param-preview-toolbar">
          <label className="param-preview-control">
            <span>模拟时长</span>
            <select value={durationChoice} onChange={(event) => setDurationChoice(Number(event.target.value))}>
              {SIM_DURATION_CHOICES.map((choice) => (
                <option key={choice.value} value={choice.value}>{choice.label}</option>
              ))}
            </select>
          </label>
          <button type="button" onClick={() => setSeed((value) => value + 1)}>⟳ 重新模拟</button>
          <span className="hint-inline">{describeSource(def)}</span>
        </div>

        <div className="param-preview-stats">
          <div className="param-preview-stat is-current">
            <span>首值</span>
            <strong>{formatSimValue(simulation.first)}</strong>
          </div>
          <div className="param-preview-stat"><span>最小</span><strong>{formatSimValue(simulation.min)}</strong></div>
          <div className="param-preview-stat"><span>最大</span><strong>{formatSimValue(simulation.max)}</strong></div>
          <div className="param-preview-stat"><span>平均</span><strong>{formatSimValue(simulation.avg)}</strong></div>
          <div className="param-preview-stat">
            <span>窗口</span><strong>{formatSimTime(simulation.durationMs)}</strong>
          </div>
        </div>

        <ParamCurveChart
          samples={simulation.samples}
          durationMs={simulation.durationMs}
          cursorMs={null}
          onReadout={() => undefined}
        />

        {simulation.warning !== '' && <p className="inline-note danger">模拟提示：{simulation.warning}</p>}
      </div>
    </Modal>
  );
}
