import type { HostTemplate } from '../../../api';

/** 新建主机时的模板一键填入（第三轮修订 12）。 */
export function HostTemplatePicker({
  templates,
  onPick,
}: {
  templates: ReadonlyArray<HostTemplate>;
  onPick: (template: HostTemplate) => void;
}): JSX.Element {
  return (
    <div className="form-row">
      <label className="field field-wide">
        从主机模板填入
        <select
          defaultValue=""
          aria-label="从主机模板填入"
          onChange={(event) => {
            const template = templates.find((item) => item.id === event.target.value);
            if (template !== undefined) {
              onPick(template);
            }
            event.target.value = '';
          }}
        >
          <option value="">（选择模板,可再修改）</option>
          {templates.map((template) => (
            <option key={template.id} value={template.id}>
              {template.name} · {template.host}:{template.port}
            </option>
          ))}
        </select>
      </label>
    </div>
  );
}
