import type { ProColumns } from '@ant-design/pro-components'
import type { TFunction } from 'i18next'
import { mayCompare, renderCell, type CellContext, type Row } from './cells'
import { fieldLabel, optionsOf } from './kinds'
import { columnFields, searchKindOf } from './listQuery'
import type { DictItem, EntityMeta, FieldMeta, ListViewMeta } from './types'

/*
 * The ProTable columns of the pages still on Ant Design (the report page, until phase 15c moves it and deletes this
 * file). The generated list pages use meta/dataColumns.tsx; the cells both show are in meta/cells.ts.
 */
export { mayCompare, renderCell, toRow } from './cells'
export type { CellContext, Row } from './cells'

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
