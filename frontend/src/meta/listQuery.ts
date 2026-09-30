import dayjs from 'dayjs'
import type { components } from '../api/schema'
import { findField } from './kinds'
import type { EntityMeta, FieldMeta, ListViewMeta } from './types'

type Filter = components['schemas']['Filter']
type Sort = components['schemas']['Sort']

/**
 * The list view of a dataset (docs/design/02-metamodel.md section 7): its name, else "default". Without one the
 * server allows no filtering and sorting, and the columns are the plain fields.
 */
export function listViewOf(entity: EntityMeta, name: string | undefined): ListViewMeta | undefined {
  return entity.listViews.find((v) => v.name === (name ?? 'default'))
}

const DEFAULT_COLUMN_COUNT = 8

/** Fields shown as columns: the list view's, else the first visible fields. Never sensitive fields. */
export function columnFields(entity: EntityMeta, view: ListViewMeta | undefined): FieldMeta[] {
  const names = view?.columns ?? entity.fields.filter((f) => !f.systemManaged).map((f) => f.name)
  return names
    .map((name) => findField(entity, name))
    .filter((f): f is FieldMeta => !!f && !f.sensitive)
    .slice(0, view ? undefined : DEFAULT_COLUMN_COUNT)
}

/** How a whitelisted field is searched: the operator follows what its kind allows. */
export type SearchKind = 'like' | 'eq-text' | 'select' | 'bool' | 'decimal-range' | 'time-range' | 'date-range'

export function searchKindOf(field: FieldMeta): SearchKind | null {
  const ops = new Set(field.operators)
  switch (field.type) {
    case 'text':
      return ops.has('LIKE') ? 'like' : ops.has('EQ') ? 'eq-text' : null
    case 'code':
      return ops.has('EQ') ? 'select' : null
    case 'bool':
      return ops.has('EQ') ? 'bool' : null
    case 'monetary':
    case 'numeric':
    case 'version':
      return ops.has('BETWEEN') || ops.has('GTE') ? 'decimal-range' : null
    case 'temporal':
      return ops.has('BETWEEN') || ops.has('GTE') ? 'time-range' : null
    case 'date':
      return ops.has('BETWEEN') || ops.has('GTE') ? 'date-range' : null
    default:
      return ops.has('EQ') ? 'eq-text' : null
  }
}

function blank(value: unknown): boolean {
  return value === undefined || value === null || value === ''
}

/** A day of a date filter: both ends included, as `YYYY-MM-DD`. */
function day(value: unknown): string | undefined {
  if (blank(value)) return undefined
  const date = dayjs.isDayjs(value) ? value : dayjs(String(value))
  return date.isValid() ? date.format('YYYY-MM-DD') : undefined
}

function instant(value: unknown): string | undefined {
  if (blank(value)) return undefined
  const time = dayjs.isDayjs(value) ? value : dayjs(String(value))
  return time.isValid() ? time.toISOString() : undefined
}

/**
 * Filters of the dataset query for the values of the search form, keyed by field name. Only whitelisted fields are
 * used (the server refuses others with FILTER_NOT_ALLOWED); several filters combine with AND.
 */
export function buildFilters(
  entity: EntityMeta,
  view: ListViewMeta | undefined,
  values: Record<string, unknown>,
): Filter[] {
  const filters: Filter[] = []
  for (const name of view?.filters ?? []) {
    const field = findField(entity, name)
    const value = values[name]
    if (!field || field.sensitive || blank(value)) continue
    switch (searchKindOf(field)) {
      case 'like':
        filters.push({ field: name, op: 'like', value: `%${String(value).trim()}%` })
        break
      case 'eq-text':
      case 'select':
        filters.push({ field: name, op: 'eq', value: String(value).trim() })
        break
      case 'bool':
        filters.push({ field: name, op: 'eq', value: value === true || value === 'true' })
        break
      case 'decimal-range':
      case 'time-range':
      case 'date-range': {
        const [from, to] = Array.isArray(value) ? value : [value, undefined]
        const kind = searchKindOf(field)
        const convert = kind === 'time-range' ? instant : kind === 'date-range' ? day : (v: unknown) => (blank(v) ? undefined : String(v))
        const lower = convert(from)
        const upper = convert(to)
        if (lower !== undefined && upper !== undefined) filters.push({ field: name, op: 'between', from: lower, to: upper })
        else if (lower !== undefined) filters.push({ field: name, op: 'gte', value: lower })
        else if (upper !== undefined) filters.push({ field: name, op: 'lte', value: upper })
        break
      }
      default:
        break
    }
  }
  return filters
}

/** Sorts of the dataset query from the table's sorter; only whitelisted fields, otherwise the view's default. */
export function buildSorts(view: ListViewMeta | undefined, sorter: Record<string, string | null | undefined>): Sort[] {
  const allowed = new Set(view?.sorts ?? [])
  return Object.entries(sorter)
    .filter(([field, order]) => allowed.has(field) && (order === 'ascend' || order === 'descend'))
    .map(([field, order]) => ({ field, asc: order === 'ascend' }))
}
