import {
  ProForm,
  ProFormDateTimePicker,
  ProFormDigit,
  ProFormSelect,
  ProFormSwitch,
  ProFormText,
  ProFormTextArea,
} from '@ant-design/pro-components'
import type { TFunction } from 'i18next'
import { i18nParams } from '../meta/i18nText'
import { controlOf, fieldLabel, optionsOf } from '../meta/kinds'
import type { DictItem, FieldMeta, Violation } from '../meta/types'
import FieldErrors from './FieldErrors'
import I18nTextInput from './I18nTextInput'
import ReferenceSelect from './ReferenceSelect'

interface Props {
  field: FieldMeta
  disabled: boolean
  dictionaries: Record<string, DictItem[]>
  violations: Violation[] | undefined
  t: TFunction
  /** For a reference field: the dataset to look its targets up in, when they can be picked by name. */
  referenceDatasetId?: string
  /** The platform's default language, the fallback of multilingual texts. */
  defaultLocale?: string
}

/**
 * One form input chosen by the field's semantic kind. Inputs do not truncate or round (no maxLength, no precision):
 * a value the rules reject is shown with the rule's message rather than silently changed.
 */
export default function EntityField({
  field,
  disabled,
  dictionaries,
  violations,
  t,
  referenceDatasetId,
  defaultLocale,
}: Props) {
  const label = fieldLabel(field, t)
  const formItemProps = {
    required: field.required && !disabled,
    validateStatus: violations && violations.length > 0 ? ('error' as const) : undefined,
    // ProForm fields do not pass a custom help node through; extra is rendered as given.
    extra: violations && violations.length > 0 ? <FieldErrors violations={violations} /> : undefined,
  }
  const common = { name: field.name, label, disabled, formItemProps }
  if (field.type === 'reference' && referenceDatasetId) {
    return (
      <ProForm.Item name={field.name} label={label} {...formItemProps}>
        <ReferenceSelect datasetId={referenceDatasetId} defaultLocale={defaultLocale} disabled={disabled} />
      </ProForm.Item>
    )
  }
  switch (controlOf(field)) {
    case 'i18n':
      return (
        <ProForm.Item name={field.name} label={label} {...formItemProps}>
          <I18nTextInput
            name={field.name}
            params={i18nParams(field)}
            disabled={disabled}
            invalidLanguages={(violations ?? []).map((v) => v.params?.lang).filter((l): l is string => typeof l === 'string')}
          />
        </ProForm.Item>
      )
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
    case 'json':
      return <ProFormTextArea {...common} placeholder="JSON" />
    default:
      return <ProFormText {...common} />
  }
}
