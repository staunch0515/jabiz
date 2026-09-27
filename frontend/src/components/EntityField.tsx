import {
  ProFormDateTimePicker,
  ProFormDigit,
  ProFormSelect,
  ProFormSwitch,
  ProFormText,
  ProFormTextArea,
} from '@ant-design/pro-components'
import type { TFunction } from 'i18next'
import { controlOf, fieldLabel, optionsOf } from '../meta/kinds'
import { isFileField, type DictItem, type FieldMeta, type Violation } from '../meta/types'
import FieldErrors from './FieldErrors'
import FileField from './FileField'

interface Props {
  field: FieldMeta
  disabled: boolean
  dictionaries: Record<string, DictItem[]>
  violations: Violation[] | undefined
  t: TFunction
}

/**
 * One form input chosen by the field's semantic kind. Inputs do not truncate or round (no maxLength, no precision):
 * a value the rules reject is shown with the rule's message rather than silently changed.
 */
export default function EntityField({ field, disabled, dictionaries, violations, t }: Props) {
  const label = fieldLabel(field, t)
  const formItemProps = {
    required: field.required && !disabled,
    validateStatus: violations && violations.length > 0 ? ('error' as const) : undefined,
    // ProForm fields do not pass a custom help node through; extra is rendered as given.
    extra: violations && violations.length > 0 ? <FieldErrors violations={violations} /> : undefined,
  }
  const common = { name: field.name, label, disabled, formItemProps }
  switch (controlOf(field)) {
    case 'textarea':
      return <ProFormTextArea {...common} />
    case 'decimal':
      return (
        <ProFormDigit
          {...common}
          fieldProps={{
            stringMode: true,
            precision: undefined,
            style: { width: '100%' },
            addonAfter: field.type === 'monetary' ? field.currency : undefined,
          }}
          min={Number.MIN_SAFE_INTEGER}
          max={Number.MAX_SAFE_INTEGER}
        />
      )
    case 'integer':
      return <ProFormDigit {...common} fieldProps={{ precision: 0, style: { width: '100%' } }} />
    case 'datetime':
      return <ProFormDateTimePicker {...common} fieldProps={{ style: { width: '100%' } }} />
    case 'switch':
      return <ProFormSwitch {...common} />
    case 'select':
      return <ProFormSelect {...common} options={optionsOf(field, dictionaries)} fieldProps={{ allowClear: true }} />
    case 'file':
      return isFileField(field) ? (
        <FileField field={field} label={label} disabled={disabled} formItemProps={formItemProps} t={t} />
      ) : null
    case 'json':
      return <ProFormTextArea {...common} placeholder="JSON" />
    default:
      return <ProFormText {...common} />
  }
}
