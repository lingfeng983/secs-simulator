import type { HostTemplate } from '../../../state/hostTemplates';
import { FieldLabel } from '../../../shared/ui/FieldLabel';

/**
 * 新建主机时的模板选择：选的是「方案包」，不是目标地址。
 *
 * <p>主机模板只装 Trace / S1F3 采集方案；地址、端口、Device ID 属于
 * 主机实例，仍由新建弹框自己填。说明默认收起，悬浮在标题上展示。</p>
 */
export function HostTemplatePicker({
  templates,
  value,
  onChange,
}: {
  templates: ReadonlyArray<HostTemplate>;
  value: string;
  onChange: (template: HostTemplate | null) => void;
}): JSX.Element {
  const selected = templates.find((item) => item.id === value);
  const help = selected === undefined
    ? '模板只带 Trace 与采集方案；目标地址、端口、Device ID 是这台主机自己的设置，在下面填写。'
    : `将把「${selected.name}」的 ${selected.tracePlans.length} 份 Trace 方案、${selected.collectPlans.length} 份采集方案复制到这台主机，之后可各自修改。`;
  return (
    <label className="field modal-field">
      <FieldLabel text="从主机模板套用方案" help={help} />
      <select
        value={value}
        aria-label="从主机模板套用方案"
        onChange={(event) => {
          onChange(templates.find((item) => item.id === event.target.value) ?? null);
        }}
      >
        <option value="">（不套用模板）</option>
        {templates.map((template) => (
          <option key={template.id} value={template.id}>
            {template.name} · Trace {template.tracePlans.length} 份 / 采集 {template.collectPlans.length} 份
          </option>
        ))}
      </select>
    </label>
  );
}
