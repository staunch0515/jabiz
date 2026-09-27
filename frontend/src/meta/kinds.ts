import dayjs from 'dayjs'
import type { TFunction } from 'i18next'
import { formatDecimal, toDecimal } from './decimal'
import { isFileField, type DictItem, type EntityMeta, type FieldMeta } from './types'

/**
 * Semantic kind → how a value is shown and entered (docs/design/12-frontend.md section 5). Pure functions: the
 * React components only put their results into ProTable / ProForm.
 */

/** The field's label: the server's (from message resources), else the UI's name for system fields, else its name. */
export function fieldLabel(field: Pick<FieldMeta, 'name' | 'label'>, t: TFunction): string {
  if (field.label && field.label !== field.name) return field.label
  return t(`fields.${field.name}`, { defaultValue: field.name })
}

export function findField(entity: EntityMeta, name: string): FieldMeta | undefined {
  return entity.fields.find((f) => f.name === name)
}

export type Control = 'text' | 'textarea' | 'decimal' | 'integer' | 'datetime' | 'switch' | 'select' | 'file' | 'json'

/** The input control for a field of this kind. */
export function controlOf(field: FieldMeta): Control {
  switch (field.type) {
    case 'text':
      return field.multiline ? 'textarea' : 'text'
    case 'monetary':
    case 'numeric':
      return 'decimal'
    case 'version':
      return 'integer'
    case 'temporal':
      return 'datetime'
    case 'bool':
      return 'switch'
    case 'code':
      return 'select'
    case 'custom':
      return isFileField(field) ? 'file' : 'json'
    case 'none':
      return 'json'
    default:
      return 'text'
  }
}

/** Digits after the point an input allows, when the kind fixes them. */
export function scaleOf(field: FieldMeta): number | undefined {
  if (field.type === 'monetary' || field.type === 'numeric') return field.scale
  return undefined
}

/** Options of a code field: its dictionary (enabled items, by sort order), else its fixed values. */
export function optionsOf(field: FieldMeta, dictionaries: Record<string, DictItem[]>): { label: string; value: string }[] {
  if (field.type !== 'code') return []
  const items = dictionaries[field.dictUrn]
  if (items && items.length > 0) {
    return [...items]
      .filter((i) => i.enabled)
      .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
      .map((i) => ({ label: i.label, value: i.code }))
  }
  return field.allowedValues.map((v) => ({ label: v, value: v }))
}

/** Enabled codes of every loaded dictionary, for NOT_IN_DICTIONARY checks. */
export function enabledCodes(dictionaries: Record<string, DictItem[]>): Record<string, Set<string>> {
  return Object.fromEntries(
    Object.entries(dictionaries).map(([urn, items]) => [urn, new Set(items.filter((i) => i.enabled).map((i) => i.code))]),
  )
}

/** A value for display, in the UI language (dictionary labels, amounts with currency, local times). */
export function formatValue(
  field: FieldMeta,
  value: unknown,
  dictionaries: Record<string, DictItem[]>,
  t: TFunction,
  locale: string,
): string {
  if (value === null || value === undefined || value === '') return '—'
  switch (field.type) {
    case 'monetary': {
      const d = toDecimal(value)
      if (!d) return String(value)
      const text = formatDecimal(d)
      try {
        return new Intl.NumberFormat(locale, {
          style: 'currency',
          currency: field.currency,
          minimumFractionDigits: field.scale,
          maximumFractionDigits: Math.max(field.scale, 0),
        }).format(text as unknown as number)
      } catch {
        return `${text} ${field.currency}`
      }
    }
    case 'numeric': {
      const d = toDecimal(value)
      return d ? formatDecimal(d) : String(value)
    }
    case 'temporal': {
      const time = dayjs(String(value))
      return time.isValid() ? time.format('YYYY-MM-DD HH:mm:ss') : String(value)
    }
    case 'bool':
      return value === true || value === 'true' ? t('list.yes') : t('list.no')
    case 'code': {
      const item = dictionaries[field.dictUrn]?.find((i) => i.code === String(value))
      return item ? item.label : String(value)
    }
    default:
      return typeof value === 'object' ? JSON.stringify(value) : String(value)
  }
}

/**
 * A form value in the form the server expects: decimals as exact text, times as ISO-8601 instants, empty text as
 * absent. The same conversion feeds the client validation, so both judge the value that is actually sent.
 */
export function toWireValue(field: FieldMeta, value: unknown): unknown {
  if (value === undefined) return undefined
  if (value === null) return null
  switch (controlOf(field)) {
    case 'datetime': {
      if (dayjs.isDayjs(value)) return value.toISOString()
      return value
    }
    case 'decimal':
    case 'integer':
      if (value === '') return null
      return typeof value === 'number' ? String(value) : value
    case 'file':
      return value === '' ? null : value
    case 'json':
      if (typeof value !== 'string') return value
      if (value.trim() === '') return null
      try {
        return JSON.parse(value)
      } catch {
        return value
      }
    case 'text':
    case 'textarea':
      return value
    default:
      return value
  }
}

/** A stored value as the form control wants it. */
export function toFormValue(field: FieldMeta, value: unknown): unknown {
  if (value === null || value === undefined) return undefined
  switch (controlOf(field)) {
    case 'datetime':
      return dayjs(String(value))
    case 'decimal': {
      const d = toDecimal(value)
      return d ? formatDecimal(d) : value
    }
    case 'json':
      return typeof value === 'string' ? value : JSON.stringify(value)
    default:
      return value
  }
}
