import type { ColumnDef, FilterField, SortingState } from '@jabiz/ui'
import type { TFunction } from 'i18next'
import { mayCompare, renderCell, type CellContext, type Row } from './cells'
import { fieldLabel, findField, optionsOf } from './kinds'
import { columnFields, searchKindOf } from './listQuery'
import type { DictItem, EntityMeta, FieldMeta, ListViewMeta } from './types'

/**
 * List view metadata → DataTable columns (docs/design/12-frontend.md section 5). A column is sortable only when the
 * list view whitelists it and the user may compare by the field (a masked one needs its permission); cells show what
 * {@link renderCell} shows. Sorting starts ascending, as the server's default sort reads.
 */
export function dataColumns(
  entity: EntityMeta,
  view: ListViewMeta | undefined,
  dictionaries: Record<string, DictItem[]>,
  t: TFunction,
  locale: string,
  context: CellContext = {},
): ColumnDef<Row, unknown>[] {
  const sorts = new Set(view?.sorts ?? [])
  return columnFields(entity, view).map((field) => ({
    id: field.name,
    // TanStack Table sorts (here: reports the sorting of) accessor columns only.
    accessorFn: (row) => row[field.name],
    header: fieldLabel(field, t),
    enableSorting: sorts.has(field.name) && mayCompare(field, context.can),
    sortDescFirst: false,
    cell: ({ row }) => renderCell(field, row.original[field.name], dictionaries, t, locale, { ...context, row: row.original }),
  }))
}

/** The sorting a list starts with: the list view's default sort, when the user may sort by it. */
export function defaultSorting(
  entity: EntityMeta,
  view: ListViewMeta | undefined,
  can?: (permission: string) => boolean,
): SortingState {
  const sort = view?.defaultSort
  if (!sort || !(view?.sorts ?? []).includes(sort.field)) return []
  const field = findField(entity, sort.field)
  if (!field || !mayCompare(field, can)) return []
  return [{ id: sort.field, desc: !sort.asc }]
}

/**
 * The filters of a list (a FilterBar's fields): the list view's whitelisted fields whose kind allows a suitable
 * operator, never sensitive fields, masked fields only for holders of their permission. The values the bar collects
 * become the query's filters through {@link buildFilters}.
 */
export function filterFields(
  entity: EntityMeta,
  view: ListViewMeta | undefined,
  dictionaries: Record<string, DictItem[]>,
  t: TFunction,
  can?: (permission: string) => boolean,
): FilterField[] {
  const fields: FilterField[] = []
  for (const name of view?.filters ?? []) {
    const field = findField(entity, name)
    if (!field || field.sensitive || !mayCompare(field, can)) continue
    const filter = filterOf(field, dictionaries, t)
    if (filter) fields.push(filter)
  }
  return fields
}

function filterOf(field: FieldMeta, dictionaries: Record<string, DictItem[]>, t: TFunction): FilterField | null {
  const common = { name: field.name, label: fieldLabel(field, t) }
  switch (searchKindOf(field)) {
    case 'like':
    case 'eq-text':
      return { ...common, kind: 'text' }
    case 'select':
      return { ...common, kind: 'select', options: optionsOf(field, dictionaries) }
    case 'bool':
      return {
        ...common,
        kind: 'select',
        options: [
          { label: t('list.yes'), value: 'true' },
          { label: t('list.no'), value: 'false' },
        ],
      }
    case 'decimal-range':
      return { ...common, kind: 'number-range' }
    case 'time-range':
      return { ...common, kind: 'datetime-range' }
    case 'date-range':
      return { ...common, kind: 'date-range' }
    default:
      return null
  }
}
