import type { ProColumns } from '@ant-design/pro-components'
import type { TFunction } from 'i18next'
import { fieldLabel, formatValue, optionsOf } from './kinds'
import { columnFields, searchKindOf } from './listQuery'
import type { DictItem, EntityInstance, EntityMeta, FieldMeta, ListViewMeta } from './types'

/** One table row: the entity's attributes by field name, plus the instance itself. */
export type Row = Record<string, unknown> & { __key: string; __instance: EntityInstance }

export function toRow(instance: EntityInstance): Row {
  return { ...(instance.attributes ?? {}), __key: String(instance.id), __instance: instance }
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
): ProColumns<Row>[] {
  const filters = new Set(view?.filters ?? [])
  const sorts = new Set(view?.sorts ?? [])
  const columns: ProColumns<Row>[] = columnFields(entity, view).map((field) => {
    const column: ProColumns<Row> = {
      title: fieldLabel(field, t),
      dataIndex: field.name,
      key: field.name,
      hideInSearch: true,
      sorter: sorts.has(field.name),
      render: (_, row) => formatValue(field, row[field.name], dictionaries, t, locale),
    }
    if (view?.defaultSort?.field === field.name) {
      column.defaultSortOrder = view.defaultSort.asc ? 'ascend' : 'descend'
    }
    if (filters.has(field.name)) Object.assign(column, searchProps(field, dictionaries, t))
    return column
  })
  // Filters that are not columns still get a search input.
  for (const name of view?.filters ?? []) {
    if (columns.some((c) => c.dataIndex === name)) continue
    const field = entity.fields.find((f) => f.name === name)
    if (!field || field.sensitive || !searchKindOf(field)) continue
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
    case 'like':
    case 'eq-text':
      return { hideInSearch: false, valueType: 'text' }
    default:
      return { hideInSearch: true }
  }
}
