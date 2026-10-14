import type { TFunction } from 'i18next'
import { createElement, type ReactNode } from 'react'
import { FilePreview } from '@jabiz/ui'
import MaskedValue from '../components/MaskedValue'
import { isI18nText, pickText } from './i18nText'
import { formatValue } from './kinds'
import { isFileField, type DictItem, type EntityInstance, type FieldMeta } from './types'

/*
 * The cells of generated tables, independent of the table component (docs/design/12-frontend.md section 5): rows,
 * what a cell shows, and who may compare by a field. Used by the DataTable columns (dataColumns.tsx).
 */

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
