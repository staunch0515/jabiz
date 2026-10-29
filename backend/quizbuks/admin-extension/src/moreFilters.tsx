import { useQueries } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  EXTENSION_NAMESPACE,
  FilterBar,
  filledFilters,
  type Filter,
  type FilterField,
  type FilterRange,
  type FilterValues,
} from '@jabiz/admin'
import { readDictionary } from './api'

/**
 * A filter a template declares (`list.filters`) that a page offers in its filter bar (Q53b): an exact text (`eq`, an
 * id or a code), a part of a text (`like`), a code of a dictionary or one of the page's choices, yes or no, or a range
 * of amounts, times or days.
 */
export type MoreFilter =
  | { kind: 'eq' | 'like'; name: string; label: string; placeholder?: string; /** Codes kept in capitals. */ upper?: boolean }
  | { kind: 'dictionary'; name: string; label: string; dictionary: string; only?: readonly string[] }
  | { kind: 'choice'; name: string; label: string; options: Option[] }
  | { kind: 'yesno' | 'amount' | 'time' | 'date'; name: string; label: string }

type Option = { value: string; label: string }

/** The filter bar's fields of the filters; a choice offers `options[name]`. */
export function filterFields(filters: readonly MoreFilter[], options: Readonly<Record<string, Option[]>>): FilterField[] {
  return filters.map((filter): FilterField => {
    switch (filter.kind) {
      case 'eq':
      case 'like':
        return { kind: 'text', name: filter.name, label: filter.label, placeholder: filter.placeholder }
      case 'choice':
        return { kind: 'select', name: filter.name, label: filter.label, options: filter.options }
      case 'dictionary':
      case 'yesno':
        return { kind: 'select', name: filter.name, label: filter.label, options: options[filter.name] ?? [] }
      case 'amount':
        return { kind: 'number-range', name: filter.name, label: filter.label }
      case 'time':
        return { kind: 'datetime-range', name: filter.name, label: filter.label }
      case 'date':
        return { kind: 'date-range', name: filter.name, label: filter.label }
    }
  })
}

/** The template's filters of the bar's values (empty fields and open ranges give none). */
export function toFilters(filters: readonly MoreFilter[], values: FilterValues): Filter[] {
  const result: Filter[] = []
  for (const filter of filters) {
    const value = values[filter.name]
    if (value === null || value === undefined) continue
    if (typeof value === 'string') {
      const text = filter.kind === 'eq' && filter.upper ? value.trim().toUpperCase() : value.trim()
      if (!text) continue
      result.push(filter.kind === 'like'
        ? { field: filter.name, op: 'like', value: `%${text}%` }
        : { field: filter.name, op: 'eq', value: text })
      continue
    }
    const { from, to } = value as FilterRange
    if (from && to) result.push({ field: filter.name, op: 'between', from, to })
    else if (from) result.push({ field: filter.name, op: 'gte', value: from })
    else if (to) result.push({ field: filter.name, op: 'lte', value: to })
  }
  return result
}

/**
 * A page's filter bar of the template's declared filters (Q53b): the filters in force for the reader (and the query
 * key, so that a change reads the first page again) and the bar to show. The filters are the page's own list, the
 * same at every render (a dictionary's items are read once per dictionary).
 */
export function useMoreFilters(filters: readonly MoreFilter[], testId?: string) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const [values, setValues] = useState<FilterValues>({})
  const dictionaries = filters.filter((filter) => filter.kind === 'dictionary')
  const items = useQueries({
    queries: dictionaries.map((filter) => ({
      queryKey: ['qb-ext', 'dictionary-items', filter.dictionary],
      queryFn: () => readDictionary(filter.dictionary),
      staleTime: 5 * 60_000,
    })),
  })
  const options: Record<string, Option[]> = {}
  dictionaries.forEach((filter, index) => {
    options[filter.name] = (items[index].data ?? []).filter((item) => !filter.only || filter.only.includes(item.code))
      .map((item) => ({ value: item.code, label: item.label }))
  })
  for (const filter of filters) {
    if (filter.kind === 'yesno') {
      options[filter.name] = [{ value: 'true', label: t('common.yes') }, { value: 'false', label: t('common.no') }]
    }
  }
  return {
    filters: toFilters(filters, values),
    bar: (
      <div data-testid={testId}>
        <FilterBar fields={filterFields(filters, options)} values={values} label={t('common.moreFilters')}
          onSubmit={(next) => setValues(filledFilters(next))} onReset={() => setValues({})} />
      </div>
    ),
  }
}
