import { Select } from 'antd'
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
}

/** A searchable picker of a referenced instance, shown by its display field (16 section 2). */
export default function ReferenceSelect({ datasetId, defaultLocale, value, onChange, disabled }: Props) {
  const { i18n } = useTranslation()
  const [typed, setTyped] = useState('')
  const [q, setQ] = useState('')
  // Looked up once typing pauses, not on every keystroke.
  useEffect(() => {
    const timer = setTimeout(() => setQ(typed), 250)
    return () => clearTimeout(timer)
  }, [typed])
  const lookup = useLookup(datasetId, q)
  const current = useLabels(datasetId, value ? [String(value)] : [])
  const text = (label: unknown) =>
    typeof label === 'string' ? label : (pickText(label, i18n.language, defaultLocale)?.text ?? '')
  const options = (lookup.data ?? []).map((item) => ({ value: item.id, label: text(item.label) || item.id }))
  if (value && !options.some((o) => o.value === String(value))) {
    const label = current.data?.[String(value)]
    options.unshift({ value: String(value), label: label !== undefined ? text(label) || String(value) : String(value) })
  }
  return (
    <Select
      showSearch
      allowClear
      filterOption={false}
      disabled={disabled}
      value={value ?? undefined}
      options={options}
      loading={lookup.isFetching}
      onSearch={setTyped}
      onChange={(next) => onChange?.(next ?? null)}
      data-testid="reference-select"
    />
  )
}
