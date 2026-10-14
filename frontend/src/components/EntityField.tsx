import {
  Combobox,
  DatePicker,
  DateTimePicker,
  DecimalInput,
  Input,
  Label,
  MoneyInput,
  Switch,
  Textarea,
} from '@jabiz/ui'
import type { TFunction } from 'i18next'
import { useId, type ReactNode } from 'react'
import { i18nParams, type Texts } from '../meta/i18nText'
import { controlOf, fieldLabel, optionsOf } from '../meta/kinds'
import { isFileField, type DictItem, type FieldMeta, type Violation } from '../meta/types'
import FieldErrors from './FieldErrors'
import { FileInput } from './FileField'
import I18nTextInput from './I18nTextInput'
import ReferenceSelect from './ReferenceSelect'

interface Props {
  field: FieldMeta
  disabled: boolean
  dictionaries: Record<string, DictItem[]>
  violations: Violation[] | undefined
  t: TFunction
  /** The form value (see `toFormValue`): text for most kinds, a boolean, texts by language, a file id. */
  value: unknown
  onChange(value: unknown): void
  /** For a reference field: the dataset to look its targets up in, when they can be picked by name. */
  referenceDatasetId?: string
  /** The platform's default language, the fallback of multilingual texts. */
  defaultLocale?: string
  /** A date field holds (true) or no longer holds (false) typed text that is no date: the form is not sent then. */
  onTypedInvalid?(invalid: boolean): void
  /** A file field starts (true) or ends (false) uploading: the form is not sent meanwhile. */
  onUploadingChange?(uploading: boolean): void
}

/**
 * One form input chosen by the field's semantic kind, with its label and its problems (tied to the input with
 * `aria-describedby`, each with its rule code). Inputs do not truncate or round (no maximum length, no decimal
 * places): a value the rules reject is shown with the rule's message rather than silently changed.
 */
export default function EntityField({
  field,
  disabled,
  dictionaries,
  violations,
  t,
  value,
  onChange,
  referenceDatasetId,
  defaultLocale,
  onTypedInvalid,
  onUploadingChange,
}: Props) {
  const id = useId()
  const label = fieldLabel(field, t)
  const errorId = `${id}-errors`
  const invalid = !!violations && violations.length > 0
  const required = field.required && !disabled
  const common = {
    id,
    disabled,
    'aria-invalid': invalid || undefined,
    'aria-describedby': invalid ? errorId : undefined,
    'aria-required': required || undefined,
  }
  const text = typeof value === 'string' ? value : value === undefined || value === null ? '' : String(value)
  const control = controlOf(field)

  let input: ReactNode
  if (field.type === 'reference' && referenceDatasetId) {
    input = (
      <ReferenceSelect
        {...common}
        datasetId={referenceDatasetId}
        defaultLocale={defaultLocale}
        value={text || null}
        onChange={(next) => onChange(next)}
      />
    )
  } else {
    switch (control) {
      case 'i18n':
        input = (
          <I18nTextInput
            name={field.name}
            label={label}
            params={i18nParams(field)}
            value={(value as Texts | undefined) ?? undefined}
            onChange={onChange}
            disabled={disabled}
            aria-describedby={common['aria-describedby']}
            invalidLanguages={(violations ?? []).map((v) => v.params?.lang).filter((l): l is string => typeof l === 'string')}
          />
        )
        break
      case 'textarea':
        input = <Textarea {...common} value={text} onChange={(e) => onChange(e.target.value)} />
        break
      case 'decimal':
        input =
          field.type === 'monetary' ? (
            <MoneyInput {...common} value={text} onChange={onChange} currency={field.currency} />
          ) : (
            <DecimalInput {...common} value={text} onChange={onChange} />
          )
        break
      case 'integer':
        // Text, not a number field: a number field drops half-typed text ("12e" reads as empty) and the mouse wheel
        // changes it. Only digits and a leading minus can be typed; a lone minus keeps the form from being sent.
        input = (
          <Input
            {...common}
            type="text"
            inputMode="numeric"
            autoComplete="off"
            value={text}
            onChange={(e) => {
              const typed = e.target.value.replace(/\s/g, '')
              if (!/^-?\d*$/.test(typed)) return
              onChange(typed)
              onTypedInvalid?.(typed !== '' && !/^-?\d+$/.test(typed))
            }}
          />
        )
        break
      case 'datetime':
        input = (
          <DateTimePicker
            {...common}
            value={text || null}
            onChange={(next) => onChange(next)}
            onInvalidChange={onTypedInvalid}
            clearable={!disabled}
          />
        )
        break
      case 'date':
        input = (
          <DatePicker
            {...common}
            value={text || null}
            onChange={(next) => onChange(next)}
            onInvalidChange={onTypedInvalid}
            clearable={!disabled}
          />
        )
        break
      case 'switch':
        input = (
          <Switch
            id={id}
            disabled={disabled}
            aria-invalid={common['aria-invalid']}
            aria-describedby={common['aria-describedby']}
            checked={value === true || value === 'true'}
            onCheckedChange={(checked) => onChange(checked)}
          />
        )
        break
      case 'select':
        input = (
          <Combobox
            id={id}
            aria-describedby={common['aria-describedby']}
            invalid={invalid}
            required={required}
            disabled={disabled}
            value={text || null}
            onChange={(next) => onChange(next)}
            options={optionsOf(field, dictionaries)}
            clearable
          />
        )
        break
      case 'file':
        input = isFileField(field) ? (
          <FileInput
            id={id}
            aria-describedby={common['aria-describedby']}
            aria-invalid={invalid}
            field={field}
            disabled={disabled}
            t={t}
            value={text || null}
            onChange={(next) => onChange(next)}
            onUploadingChange={onUploadingChange}
          />
        ) : null
        break
      case 'json':
        input = (
          <Textarea {...common} className="font-mono" placeholder="JSON" value={text} onChange={(e) => onChange(e.target.value)} />
        )
        break
      default:
        input = <Input {...common} type="text" value={text} onChange={(e) => onChange(e.target.value)} />
    }
  }

  const caption = (
    <>
      {label}
      {required && (
        <>
          <span aria-hidden className="text-destructive">
            *
          </span>
          <span className="sr-only">({t('process.required')})</span>
        </>
      )}
    </>
  )
  return (
    <div data-slot="form-item" data-field={field.name} className="flex flex-col gap-1.5">
      {control === 'i18n' ? (
        // Several inputs, one per language: the label names the group, each input its language.
        <div role="group" aria-labelledby={`${id}-label`} className="flex flex-col gap-1.5">
          <span id={`${id}-label`} className="text-sm leading-none font-medium">
            {caption}
          </span>
          {input}
        </div>
      ) : control === 'switch' ? (
        <div className="flex items-center gap-2">
          {input}
          <Label htmlFor={id}>{caption}</Label>
        </div>
      ) : (
        <>
          <Label htmlFor={id}>{caption}</Label>
          {input}
        </>
      )}
      {invalid && <FieldErrors id={errorId} violations={violations} />}
    </div>
  )
}
