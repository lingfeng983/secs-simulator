import { useMemo, type ReactNode } from 'react';
import { DEVICE_CONFIG_PAGES, DEVICE_CONFIG_PAGE_LABELS, type DeviceConfigPage } from '../../app/router';
import type { ParamTemplate } from '../../api/paramTemplates';
import type { BackendController } from '../../state/useBackend';
import { SubTabs } from '../../shared/ui/zone';
import { JsonEditor } from '../../shared/ui/JsonEditor';
import { TextField } from '../../shared/ui/CommitFields';
import { SessionField, SessionForm, EquipmentForm } from './ConfigFormEditor';
import { editDocument, isJsonObject, objectField, parseConfigDocument, stringField, type JsonObject } from './templateConfig';
import { SvidTable } from '../panels/SvidConfigPanel';
import { ConstantsTable } from '../panels/ConstantsConfigPanel';
import { ContextTable } from '../panels/ContextConfigPanel';
import { CeidReportEditor } from '../panels/CeidReportConfigPanel';
import { PlanEditor } from '../panels/PlanConfigPanel';
import { MissingEquipment } from '../panels/ConfigEditShell';

export type TemplateConfigPage = Exclude<DeviceConfigPage, 'revisions'>;

const pages = DEVICE_CONFIG_PAGES.filter((page): page is TemplateConfigPage => page !== 'revisions')
  .map((id) => ({ id, label: DEVICE_CONFIG_PAGE_LABELS[id] }));

export function TemplateConfigEditor({ text, onChange, page, onSelect, backend, library, overview }: {
  text: string;
  onChange: (text: string) => void;
  page: TemplateConfigPage;
  onSelect: (page: TemplateConfigPage) => void;
  backend: BackendController;
  library: ParamTemplate[];
  overview?: ReactNode;
}): JSX.Element {
  const parsed = useMemo(() => parseConfigDocument(text), [text]);
  const edit = (mutate: (root: JsonObject) => void) => {
    const result = editDocument(text, mutate);
    if (result.ok) onChange(result.text);
  };

  const renderContent = () => {
    if (page === 'document') return <JsonEditor value={text} onChange={onChange} ariaLabel="模板 JSON 原文" />;
    if (!parsed.ok) {
      return <p className="problems">配置原文不是合法 JSON，请到「配置原文」页修正：{parsed.error}</p>;
    }
    const root = parsed.config.root;
    switch (page) {
      case 'overview':
        return (
          <div className="session-form">
            <div className="session-fields">
              {overview}
              <SessionField label="设备名称" help="用此模板创建的设备名称，可在创建后分别修改。">
                <TextField value={stringField(objectField(root, 'equipment'), 'name')}
                  onCommit={(name) => edit((draft) => {
                    if (!isJsonObject(draft.equipment)) draft.equipment = {};
                    (draft.equipment as JsonObject).name = name;
                  })} />
                </SessionField>
              </div>
            </div>
          );
        case 'connection': return <SessionForm root={root} edit={edit} />;
      case 'structure': return <EquipmentForm root={root} edit={edit} />;
      case 'constants': return isJsonObject(root.equipment)
        ? <ConstantsTable equipment={root.equipment} edit={edit} /> : <MissingEquipment edit={edit} />;
      case 'context': return Array.isArray(root.context) || root.context === undefined
        ? <ContextTable root={root} edit={edit} /> : <p className="problems">context 必须是数组</p>;
      case 'svids': return isJsonObject(root.equipment)
        ? <SvidTable equipment={root.equipment} edit={edit} library={library} /> : <MissingEquipment edit={edit} />;
      case 'events': return <CeidReportEditor root={root} edit={edit} />;
      case 'plans': return <PlanEditor root={root} edit={edit} text={text} backend={backend} />;
    }
  };

  return (
    <div className="template-config-editor">
      <SubTabs pages={pages} active={page} onSelect={(next) => onSelect(next as TemplateConfigPage)} />
      <div className="template-config-content" key={page}>{renderContent()}</div>
    </div>
  );
}
