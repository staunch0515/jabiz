import { Combobox } from '@jabiz/ui'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { pickText } from '../meta/i18nText'
import { useLabels, useLookup } from '../meta/references'

interface Props {
  /** The dataset that looks up and labels the target entity (its default dataset). */
  datasetId: string
  defaultLocale?: string
  value?: string | null
  onChange?(value: string | null): void
  disabled?: boolean
  /** The id a <label htmlFor> names the picker by. */
  id?: string
  placeholder?: string
  'aria-label'?: string
  'aria-labelledby'?: string
  'aria-describedby'?: string
  'aria-invalid'?: boolean
  'aria-required'?: boolean
}

/** How long typing must pause before the server is asked. */
const DEBOUNCE_MS = 250

/**
 * A searchable picker of a referenced instance, shown by its display field (16 section 2): a remote Combobox whose
 * options are the server's matches for what was typed; the current value shows its label in the best language.
 */
export default function ReferenceSelect({
  datasetId,
  defaultLocale,
  value,
  onChange,
  disabled,
  id,
  placeholder,
  'aria-invalid': invalid,
  'aria-required': required,
  ...aria
}: Props) {
  const { i18n } = useTranslation()
  const [typed, setTyped] = useState('')
  const [q, setQ] = useState('')
  // Looked up once typing pauses, not on every keystroke.
  useEffect(() => {
    const timer = setTimeout(() => setQ(typed), DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [typed])
  const lookup = useLookup(datasetId, q)
  const current = useLabels(datasetId, value ? [String(value)] : [])
  const text = (label: unknown) =>
    typeof label === 'string' ? label : (pickText(label, i18n.language, defaultLocale)?.text ?? '')
  const options = (lookup.data ?? []).map((item) => ({ value: item.id, label: text(item.label) || item.id }))
  const currentLabel = value ? current.data?.[String(value)] : undefined
  return (
    <Combobox
      {...aria}
      id={id}
      value={value ?? null}
      onChange={(next) => onChange?.(next)}
      options={options}
      onSearch={setTyped}
      loading={lookup.isFetching}
      selectedLabel={currentLabel !== undefined ? text(currentLabel) || undefined : undefined}
      placeholder={placeholder}
      disabled={disabled}
      required={required}
      invalid={invalid}
      clearable
      data-testid="reference-select"
    />
  )
}
