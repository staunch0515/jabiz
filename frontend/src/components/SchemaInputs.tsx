import {
  ProFormDatePicker,
  ProFormDateTimePicker,
  ProFormDigit,
  ProFormGroup,
  ProFormList,
  ProFormSelect,
  ProFormSwitch,
  ProFormText,
  ProFormTextArea,
} from '@ant-design/pro-components'
import type { ReactNode } from 'react'
import type { InputNode } from '../meta/processForm'

/** The schema's pattern, when this browser can compile it; otherwise the server checks it alone. */
function compiled(pattern: string | undefined): RegExp[] {
  if (!pattern) return []
  try {
    return [new RegExp(pattern, 'u')]
  } catch {
    return []
  }
}

/**
 * The form inputs of a JSON Schema node (process inputs, template parameters): one ProForm field per property,
 * groups for objects, repeatable rows for lists of objects.
 */
export function renderNode(
  node: InputNode,
  t: (key: string) => string,
  path: (string | number)[] = [],
  fixed: ReadonlySet<string> = new Set(),
): ReactNode {
  const name = [...path, node.name]
  const rules = node.required ? [{ required: true, message: `${node.name}: REQUIRED` }] : []
  const pattern = compiled(node.schema.pattern).map((regex) => ({ pattern: regex, message: `${node.name}: INVALID_VALUE` }))
  const common = {
    key: node.name,
    name: name.length === 1 ? node.name : name,
    label: node.name,
    rules: [...rules, ...pattern],
    // An input filled in by the row the process acts on (16 section 3).
    disabled: path.length === 0 && fixed.has(node.name),
  }
  switch (node.kind) {
    case 'password':
      return <ProFormText.Password {...common} fieldProps={{ autoComplete: 'new-password' }} />
    case 'datetime':
      return <ProFormDateTimePicker {...common} fieldProps={{ style: { width: '100%' } }} />
    case 'date':
      return <ProFormDatePicker {...common} fieldProps={{ style: { width: '100%' } }} />
    case 'decimal':
      return <ProFormDigit {...common} fieldProps={{ stringMode: true, style: { width: '100%' } }} min={Number.MIN_SAFE_INTEGER} />
    case 'integer':
      return <ProFormDigit {...common} fieldProps={{ precision: 0, style: { width: '100%' } }} min={Number.MIN_SAFE_INTEGER} />
    case 'number':
      return <ProFormDigit {...common} fieldProps={{ style: { width: '100%' } }} min={Number.MIN_SAFE_INTEGER} />
    case 'boolean':
      return <ProFormSwitch {...common} />
    case 'enum':
      return <ProFormSelect {...common} options={(node.options ?? []).map((v) => ({ label: v, value: v }))} />
    case 'tags':
      return <ProFormSelect {...common} mode="tags" />
    case 'object':
      return (
        <ProFormGroup key={node.name} title={node.name}>
          {(node.children ?? []).map((child) => renderNode(child, t, name))}
        </ProFormGroup>
      )
    case 'list':
      return (
        <ProFormList
          key={node.name}
          name={node.name}
          label={node.name}
          creatorButtonProps={{ creatorButtonText: t('process.addItem') }}
          initialValue={node.required ? [{}] : []}
        >
          <ProFormGroup>{(node.children ?? []).map((child) => renderNode(child, t))}</ProFormGroup>
        </ProFormList>
      )
    case 'json':
      return <ProFormTextArea {...common} placeholder={t('process.json')} />
    default:
      return <ProFormText {...common} />
  }
}
