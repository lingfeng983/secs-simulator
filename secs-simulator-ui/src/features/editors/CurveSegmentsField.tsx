import { useState } from 'react';
import { CurveFields } from './CurveFields';
import { SourcePreview } from './SourcePreview';
import { NumberField } from '../../shared/ui/CommitFields';
import {
  arrayField,
  defaultCurveFields,
  isJsonObject,
  numberField,
  objectField,
  type JsonObject,
} from './templateConfig';

/**
 * 分段序列的 segments 编辑：一段一张卡片（时长 + 一条子曲线）。
 *
 * <p>段内时间从 0 重新开始，子曲线可以是任意类型——再选「分段序列」就是嵌套，
 * 与后端递归解析同一套形状。段的顺序就是时间顺序，用上移/下移调整；
 * 所有改动经 mutateCurve 写穿回整份文档，不改解析树上的活引用。</p>
 *
 * @param curve       曲线对象（就地读取）
 * @param mutateCurve 写穿回调：拿到克隆后的整份文档里的这份曲线
 */
export function CurveSegmentsField({ curve, mutateCurve }: {
  curve: JsonObject;
  mutateCurve: (fn: (target: JsonObject) => void) => void;
}): JSX.Element {
  const segments = arrayField(curve, 'segments');
  const [previewIndex, setPreviewIndex] = useState<number | null>(null);
  const previewSegment = previewIndex === null ? undefined : segments[previewIndex];
  const durations = segments.map((item) => (isJsonObject(item) ? numberField(item, 'durationMs') ?? 0 : 0));
  const totalMs = durations.reduce((sum, value) => sum + value, 0);
  const starts: number[] = [];
  let acc = 0;
  for (const duration of durations) {
    starts.push(acc);
    acc += duration;
  }

  const mutateSegments = (fn: (items: JsonObject[]) => void) => {
    mutateCurve((target) => {
      const items = Array.isArray(target.segments) ? (target.segments as JsonObject[]) : [];
      fn(items);
      target.segments = items;
    });
  };

  const move = (index: number, delta: number) => {
    mutateSegments((items) => {
      const to = index + delta;
      if (to < 0 || to >= items.length) {
        return;
      }
      const [moved] = items.splice(index, 1);
      items.splice(to, 0, moved);
    });
  };

  return (
    <div className="curve-segments">
      <div className="curve-segments-head">
        <span className="template-label">
          分段（{segments.length} 段 · 合计 {(totalMs / 1_000).toFixed(1)}s）
        </span>
        <button
          type="button"
          onClick={() => mutateSegments((items) => {
            items.push({ durationMs: 10_000, curve: defaultCurveFields('constant') });
          })}
        >
          + 分段
        </button>
      </div>
      {segments.length === 0 && (
        <p className="hint">还没有分段，点「+ 分段」添加第一段；一条分段序列至少要有一段。</p>
      )}
      {segments.map((item, index) => {
        if (!isJsonObject(item)) {
          return (
            <div className="curve-segment-invalid" key={index}>
              <span className="problems">段 {index + 1} 不是对象，无法按表单编辑</span>
              <button
                type="button"
                onClick={() => mutateSegments((items) => {
                  items.splice(index, 1);
                })}
              >
                删除
              </button>
            </div>
          );
        }
        return (
          <div className="template-card curve-segment" key={index}>
            <div className="template-card-head">
              <span className="template-label">段 {index + 1}</span>
              <label className="field field-cell">
                <span className="field-cell-name">时长 ms</span>
                <NumberField
                  value={numberField(item, 'durationMs')}
                  integer
                  onCommit={(next) => mutateSegments((items) => {
                    if (isJsonObject(items[index])) {
                      items[index].durationMs = Math.max(1, next);
                    }
                  })}
                />
              </label>
              <span className="template-badge">起点 {(starts[index] / 1_000).toFixed(1)}s</span>
              <button type="button" onClick={() => setPreviewIndex(index)} title="只看这一段：从 0 走完本段时长">
                预览
              </button>
              <button type="button" disabled={index === 0} onClick={() => move(index, -1)}>
                上移
              </button>
              <button type="button" disabled={index === segments.length - 1} onClick={() => move(index, 1)}>
                下移
              </button>
              <button
                type="button"
                onClick={() => mutateSegments((items) => {
                  items.splice(index, 1);
                })}
              >
                删除
              </button>
            </div>
            <div className="form-row curve-segment-fields">
              <CurveFields
                curve={objectField(item, 'curve')}
                mutateCurve={(fn) => mutateSegments((items) => {
                  const segment = items[index];
                  if (isJsonObject(segment)) {
                    if (!isJsonObject(segment.curve)) {
                      segment.curve = defaultCurveFields('constant');
                    }
                    fn(segment.curve as JsonObject);
                  }
                })}
              />
            </div>
          </div>
        );
      })}
      <p className="hint">
        段内时间从 0 重新开始，子曲线自己的时长/周期照常填写；走完末段停在末值，
        勾选「走完末段后从头循环」则整条重复。
      </p>
      {isJsonObject(previewSegment) && previewIndex !== null && (
        <SourcePreview
          open
          title={`段 ${previewIndex + 1}（${((numberField(previewSegment, 'durationMs') ?? 0) / 1_000).toFixed(1)}s）`}
          /* 包成只有这一段的序列，窗口恰好是本段时长 */
          def={{
            source: {
              mode: 'curve',
              clock: 'absolute',
              curve: { type: 'sequence', segments: [previewSegment] },
            },
          }}
          autoDurationMs={numberField(previewSegment, 'durationMs')}
          axisNote="横轴是段内时间（从 0 走完本段时长）"
          onClose={() => setPreviewIndex(null)}
        />
      )}
    </div>
  );
}