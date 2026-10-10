import { useId, useRef, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'

import { UI_NAMESPACE } from '../i18n'
import { cn, UI_SCOPE } from '../lib/utils'
import { Combobox, type ComboboxOption } from './combobox'
import { DatePicker, DateTimePicker } from './date-picker'
import { DecimalInput } from './decimal-input'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { Label } from './ui/label'

/** A field of a {@link FilterBar}; `name` is the key of its value. */
export type FilterField =
  | { kind: 'text'; name: string; label: string; placeholder?: string }
  | { kind: 'select'; name: string; label: string; options: ComboboxOption[] }
  | { kind: 'number-range'; name: string; label: string }
  | { kind: 'datetime-range'; name: string; label: string }
  | { kind: 'date-range'; name: string; label: string }

/** Both ends of a range; either may be left open. Numbers are decimal text, times ISO instants, dates `YYYY-MM-DD`. */
export interface FilterRange {
  from?: string | null
  to?: string | null
}

export type FilterValue = string | FilterRange | null | undefined
export type FilterValues = Record<string, FilterValue>

export interface FilterBarProps {
  fields: FilterField[]
  /** The filters in force, shown when the bar appears (from the URL, after a reset). */
  values?: FilterValues
  /** "Query" or Enter in a field: the filled-in fields (empty ones left out). */
  onSubmit: (values: FilterValues) => void
  /** "Reset": the fields are emptied; {@link onSubmit} with no values when absent. */
  onReset?: () => void
  /** The form's accessible name; "Filters" when absent. */
  label?: string
  className?: string
}

const NO_VALUES: FilterValues = {}

/** The values without empty fields and open-on-both-ends ranges. */
export function filledFilters(values: FilterValues): FilterValues {
  const filled: FilterValues = {}
  for (const [name, value] of Object.entries(values)) {
    if (value == null || value === '') continue
    if (typeof value === 'object') {
      const from = value.from || undefined
      const to = value.to || undefined
      if (from === undefined && to === undefined) continue
      filled[name] = { ...(from !== undefined ? { from } : {}), ...(to !== undefined ? { to } : {}) }
    } else {
      filled[name] = value
    }
  }
  return filled
}

/**
 * The filters above a list: text, a choice, and ranges of numbers, times and dates (each range a `group` named by
 * its label, its two ends named "label, from" / "label, to"). "Query" or Enter in a field submits; "Reset" empties.
 * What the filters mean (operators, field names) is the caller's: the bar only collects the values.
 */
export function FilterBar({ fields, values = NO_VALUES, onSubmit, onReset, label, className }: FilterBarProps) {
  const { t } = useTranslation(UI_NAMESPACE)
  const baseId = useId()
  const [draft, setDraftState] = useState<FilterValues>(values)
  // New values from the caller replace what was being filled in.
  const [shownValues, setShownValues] = useState(values)
  const [generation, setGeneration] = useState(0)
  if (shownValues !== values) {
    setShownValues(values)
    setDraftState(values)
    setGeneration(generation + 1)
  }
  // The values as of now, also within one event: a date field reads its text on Enter just before the form
  // submits, before React has rendered the change. Stale once the caller's values replaced the draft.
  const latest = useRef({ generation, values: draft })
  const current = () => (latest.current.generation === generation ? latest.current.values : draft)
  const setDraft = (next: FilterValues) => {
    latest.current = { generation, values: next }
    setDraftState(next)
  }
  const set = (name: string, value: FilterValue) => setDraft({ ...current(), [name]: value })
  const setEnd = (name: string, end: 'from' | 'to', value: string | null) => {
    const currentValue = current()[name]
    const range = typeof currentValue === 'object' && currentValue !== null ? currentValue : {}
    set(name, { ...range, [end]: value })
  }
  const range = (name: string): FilterRange => {
    const value = draft[name]
    return typeof value === 'object' && value !== null ? value : {}
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    onSubmit(filledFilters(current()))
  }
  const reset = () => {
    setDraft({})
    if (onReset) onReset()
    else onSubmit({})
  }

  return (
    <form
      role="search"
      aria-label={label ?? t('filter.label')}
      data-slot="filter-bar"
      className={cn(UI_SCOPE, 'flex flex-wrap items-end gap-x-4 gap-y-3', className)}
      onSubmit={submit}
    >
      {fields.map((field) => {
        const id = `${baseId}-${field.name}`
        switch (field.kind) {
          case 'text':
            return (
              <div key={field.name} className="flex min-w-48 flex-col gap-1.5">
                <Label htmlFor={id}>{field.label}</Label>
                <Input
                  id={id}
                  value={(draft[field.name] as string | undefined) ?? ''}
                  placeholder={field.placeholder}
                  onChange={(event) => set(field.name, event.target.value)}
                />
              </div>
            )
          case 'select':
            return (
              <div key={field.name} className="flex min-w-48 flex-col gap-1.5">
                <Label htmlFor={id}>{field.label}</Label>
                <Combobox
                  id={id}
                  value={(draft[field.name] as string | undefined) ?? null}
                  onChange={(value) => set(field.name, value)}
                  options={field.options}
                  clearable
                />
              </div>
            )
          default: {
            const value = range(field.name)
            const fromLabel = t('filter.from', { label: field.label })
            const toLabel = t('filter.to', { label: field.label })
            return (
              <div
                key={field.name}
                role="group"
                aria-labelledby={`${id}-label`}
                className="flex flex-col gap-1.5"
              >
                <span id={`${id}-label`} className="text-sm leading-none font-medium">
                  {field.label}
                </span>
                <div className="flex items-center gap-1.5">
                  {field.kind === 'number-range' ? (
                    <>
                      <DecimalInput
                        aria-label={fromLabel}
                        value={value.from ?? ''}
                        onChange={(text) => setEnd(field.name, 'from', text)}
                        className="w-32"
                      />
                      <span aria-hidden>–</span>
                      <DecimalInput
                        aria-label={toLabel}
                        value={value.to ?? ''}
                        onChange={(text) => setEnd(field.name, 'to', text)}
                        className="w-32"
                      />
                    </>
                  ) : field.kind === 'date-range' ? (
                    <>
                      <DatePicker
                        aria-label={fromLabel}
                        value={value.from}
                        onChange={(day) => setEnd(field.name, 'from', day)}
                        clearable
                      />
                      <span aria-hidden>–</span>
                      <DatePicker
                        aria-label={toLabel}
                        value={value.to}
                        onChange={(day) => setEnd(field.name, 'to', day)}
                        clearable
                      />
                    </>
                  ) : (
                    <>
                      <DateTimePicker
                        aria-label={fromLabel}
                        value={value.from}
                        onChange={(at) => setEnd(field.name, 'from', at)}
                        clearable
                      />
                      <span aria-hidden>–</span>
                      <DateTimePicker
                        aria-label={toLabel}
                        value={value.to}
                        onChange={(at) => setEnd(field.name, 'to', at)}
                        clearable
                      />
                    </>
                  )}
                </div>
              </div>
            )
          }
        }
      })}
      <div className="flex items-center gap-2">
        <Button type="submit">{t('filter.submit')}</Button>
        <Button variant="outline" onClick={reset}>
          {t('filter.reset')}
        </Button>
      </div>
    </form>
  )
}
