import type { ProColumns } from '@ant-design/pro-components'
import type { TFunction } from 'i18next'
import { createElement, type ReactNode } from 'react'
import FilePreview from '../components/FilePreview'
import MaskedValue from '../components/MaskedValue'
import { isI18nText, pickText } from './i18nText'
import { fieldLabel, formatValue, optionsOf } from './kinds'
import { columnFields, searchKindOf } from './listQuery'
import { isFileField, type DictItem, type EntityInstance, type EntityMeta, type FieldMeta, type ListViewMeta } from './types'

/** One table row: the entity's attributes by field name, plus the instance itself. */
export type Row = Record<string, unknown> & { __key: string; __instance: EntityInstance }

export function toRow(instance: EntityInstance): Row {
  return { ...(instance.attributes ?? {}), __key: String(instance.id), __instance: instance }
}

/** What a cell needs beyond the field: labels of referenced instances, the default language of texts. */
export interface CellContext {
  /** Display texts of referenced instances, by reference field, then key. */
  labels?: Record<string, Record<string, unknown>>
  defaultLocale?: string
  /** Whether the user holds a permission: masked fields are searched, sorted and shown in plain by holders only. */
  can?: (permission: string) => boolean
  /** The dataset the rows come from, through which holders show a masked value (section 13.1). */
  datasetId?: string
}

/** Whether the user may filter and sort by the field: a masked one needs its permission. */
export function mayCompare(field: FieldMeta, can?: (permission: string) => boolean): boolean {
  return !field.masked || !!can?.(field.masked.permission)
}

/**
 * A cell: references by their label (the key when there is none), multilingual texts in the best language, marked
 * with lang when it is not the interface language (docs/design/16-content-authoring.md sections 1.3 and 2).
 */
export function renderCell(
  field: FieldMeta,
  value: unknown,
  dictionaries: Record<string, DictItem[]>,
  t: TFunction,
  locale: string,
  context: CellContext & { row?: Row } = {},
): ReactNode {
  if (field.type === 'reference' && value !== null && value !== undefined) {
    const label = context.labels?.[field.name]?.[String(value)]
    if (typeof label === 'string' && label !== '') return label
    if (label && typeof label === 'object') return renderCell({ ...field, type: 'custom', kindId: 'jabiz.i18n-text' } as FieldMeta,
      label, dictionaries, t, locale, context)
    return String(value)
  }
  // A file is shown, not its id: images as a small thumbnail, anything else as a download.
  if (isFileField(field) && typeof value === 'string' && value) {
    return createElement(FilePreview, { fileId: value, field, width: 48 })
  }
  if (field.masked && value !== null && value !== undefined) {
    const id = context.row?.__instance.id
    if (context.datasetId && id !== undefined && context.can?.(field.masked.permission)) {
      return createElement(MaskedValue, { datasetId: context.datasetId, id, field: field.name, masked: String(value) })
    }
    return String(value)
  }
  if (isI18nText(field)) {
    const picked = pickText(value, locale, context.defaultLocale)
    if (!picked) return '—'
    return picked.fallback ? createElement('span', { lang: picked.lang }, picked.text) : picked.text
  }
  return formatValue(field, value, dictionaries, t, locale)
}

/**
 * List view metadata → ProTable columns (docs/design/12-frontend.md section 5). A column is searchable and sortable
 * only when the list view whitelists it; the search control follows the operators the field's kind allows.
 */
export function buildColumns(
  entity: EntityMeta,
  view: ListViewMeta | undefined,
  dictionaries: Record<string, DictItem[]>,
  t: TFunction,
  locale: string,
  context: CellContext = {},
): ProColumns<Row>[] {
  const filters = new Set(view?.filters ?? [])
  const sorts = new Set(view?.sorts ?? [])
  const columns: ProColumns<Row>[] = columnFields(entity, view).map((field) => {
    const column: ProColumns<Row> = {
      title: fieldLabel(field, t),
      dataIndex: field.name,
      key: field.name,
      hideInSearch: true,
      sorter: sorts.has(field.name) && mayCompare(field, context.can),
      render: (_, row) => renderCell(field, row[field.name], dictionaries, t, locale, { ...context, row }),
    }
    if (view?.defaultSort?.field === field.name) {
      column.defaultSortOrder = view.defaultSort.asc ? 'ascend' : 'descend'
    }
    if (filters.has(field.name) && mayCompare(field, context.can)) Object.assign(column, searchProps(field, dictionaries, t))
    return column
  })
  // Filters that are not columns still get a search input.
  for (const name of view?.filters ?? []) {
    if (columns.some((c) => c.dataIndex === name)) continue
    const field = entity.fields.find((f) => f.name === name)
    if (!field || field.sensitive || !mayCompare(field, context.can) || !searchKindOf(field)) continue
    columns.push({ title: fieldLabel(field, t), dataIndex: name, hideInTable: true, ...searchProps(field, dictionaries, t) })
  }
  return columns
}

/** The search input of a whitelisted field; none when its kind allows no suitable operator. */
function searchProps(field: FieldMeta, dictionaries: Record<string, DictItem[]>, t: TFunction): Partial<ProColumns<Row>> {
  switch (searchKindOf(field)) {
    case 'select':
      return { hideInSearch: false, valueType: 'select', fieldProps: { options: optionsOf(field, dictionaries), allowClear: true } }
    case 'bool':
      return {
        hideInSearch: false,
        valueType: 'select',
        fieldProps: {
          options: [
            { label: t('list.yes'), value: 'true' },
            { label: t('list.no'), value: 'false' },
          ],
          allowClear: true,
        },
      }
    case 'decimal-range':
      return { hideInSearch: false, valueType: 'digitRange' }
    case 'time-range':
      return { hideInSearch: false, valueType: 'dateTimeRange' }
    case 'date-range':
      return { hideInSearch: false, valueType: 'dateRange' }
    case 'like':
    case 'eq-text':
      return { hideInSearch: false, valueType: 'text' }
    default:
      return { hideInSearch: true }
  }
}
