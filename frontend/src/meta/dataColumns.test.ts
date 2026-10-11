import { isValidElement } from 'react'
import { describe, expect, it } from 'vitest'
import i18n from '../i18n'
import { carrier, countries } from '../test/fixtures'
import { toRow, type Row } from './cells'
import { dataColumns, defaultSorting, filterFields } from './dataColumns'
import { buildFilters, buildSorts, listViewOf } from './listQuery'
import type { EntityMeta, FieldMeta, ListViewMeta } from './types'

const view = listViewOf(carrier, undefined)!
const t = i18n.t.bind(i18n)

/** What a column's cell shows for a row (the cell function with the context it reads). */
function cellOf(column: ReturnType<typeof dataColumns>[number], row: Row): unknown {
  const cell = column.cell as (context: { row: { original: Row } }) => unknown
  return cell({ row: { original: row } })
}

const base = { immutable: false, required: false, generated: false, systemManaged: false, sensitive: false, processOnly: false, operators: ['EQ', 'LIKE'], rules: [] }
const account = { ...base, name: 'bankAccount', label: 'Account', type: 'text', multiline: false, masked: { permission: 'carrier.bank', style: 'LAST4' } } as FieldMeta
const supplier = {
  ...carrier,
  entity: 'Supplier',
  fields: [{ ...base, name: 'name', label: 'Name', type: 'text', multiline: false } as FieldMeta, account],
} as EntityMeta
const supplierView: ListViewMeta = {
  name: 'default',
  columns: ['name', 'bankAccount'],
  filters: ['name', 'bankAccount'],
  sorts: ['name', 'bankAccount'],
  defaultSort: { field: 'bankAccount', asc: false },
}
const holder = (permission: string) => permission === 'carrier.bank'
const nobody = () => false

describe('DataTable columns of a list view', () => {
  it('are the view columns, sortable only when whitelisted, starting ascending', async () => {
    await i18n.changeLanguage('en')
    const columns = dataColumns(carrier, view, countries, t, 'en')
    expect(columns.map((c) => c.id)).toEqual(['carrierCode', 'countryCode', 'creditLimit', 'active', 'effectStartTime'])
    const byId = Object.fromEntries(columns.map((c) => [c.id, c]))
    expect(byId.carrierCode).toMatchObject({ header: 'Code', enableSorting: true, sortDescFirst: false })
    expect(byId.creditLimit.enableSorting).toBe(true)
    expect(byId.countryCode.enableSorting).toBe(false)
    expect(byId.effectStartTime.enableSorting).toBe(false)
    // Cells show what renderCell shows: a code by its dictionary label.
    const row = toRow({ id: 'x1', version: 3, attributes: { countryCode: 'CN', active: true } })
    expect(cellOf(byId.countryCode, row)).toBe('China')
    expect(cellOf(byId.active, row)).toBe('Yes')
  })

  it('sort and filter by a masked field for holders of its permission only, and show it in plain to them', () => {
    const without = dataColumns(supplier, supplierView, {}, t, 'en', { can: nobody, datasetId: 'urn:d' })
    expect(without.find((c) => c.id === 'bankAccount')!.enableSorting).toBe(false)
    expect(without.find((c) => c.id === 'name')!.enableSorting).toBe(true)
    const withIt = dataColumns(supplier, supplierView, {}, t, 'en', { can: holder, datasetId: 'urn:d' })
    const column = withIt.find((c) => c.id === 'bankAccount')!
    expect(column.enableSorting).toBe(true)
    const row = toRow({ id: 's1', version: 1, attributes: { bankAccount: '****4931' } })
    expect(isValidElement(cellOf(column, row))).toBe(true)
    expect(cellOf(without.find((c) => c.id === 'bankAccount')!, row)).toBe('****4931')

    expect(filterFields(supplier, supplierView, {}, t, nobody).map((f) => f.name)).toEqual(['name'])
    expect(filterFields(supplier, supplierView, {}, t, holder).map((f) => f.name)).toEqual(['name', 'bankAccount'])
  })

  it('start with the view default sort, unless the user may not sort by its field', () => {
    expect(defaultSorting(carrier, view)).toEqual([{ id: 'carrierCode', desc: false }])
    expect(defaultSorting(supplier, supplierView, holder)).toEqual([{ id: 'bankAccount', desc: true }])
    expect(defaultSorting(supplier, supplierView, nobody)).toEqual([])
    expect(defaultSorting(carrier, undefined)).toEqual([])
  })

  it('filter by the whitelisted fields with a control for what their kind allows, never sensitive ones', async () => {
    await i18n.changeLanguage('en')
    const fields = filterFields(carrier, { ...view, filters: [...view.filters!, 'secret'] }, countries, t)
    expect(fields.map((f) => [f.name, f.kind])).toEqual([
      ['carrierCode', 'text'],
      ['countryCode', 'select'],
      ['creditLimit', 'number-range'],
      ['active', 'select'],
      ['effectStartTime', 'datetime-range'],
      ['note', 'text'],
      ['cell', 'text'],
    ])
    const byName = Object.fromEntries(fields.map((f) => [f.name, f]))
    // Enabled dictionary items, by their order.
    expect(byName.countryCode).toMatchObject({ label: 'Country', options: [{ value: 'CN', label: 'China' }, { value: 'JP', label: 'Japan' }] })
    expect(byName.active).toMatchObject({ options: [{ value: 'true', label: 'Yes' }, { value: 'false', label: 'No' }] })
  })

  it('give the query what the filter bar and the table hold', () => {
    expect(
      buildFilters(carrier, view, {
        carrierCode: 'AB',
        active: 'false',
        creditLimit: { from: '1500', to: '2500' },
        effectStartTime: { from: '2026-01-01T00:00:00.000Z' },
      }),
    ).toEqual([
      { field: 'carrierCode', op: 'like', value: '%AB%' },
      { field: 'creditLimit', op: 'between', from: '1500', to: '2500' },
      { field: 'active', op: 'eq', value: false },
      { field: 'effectStartTime', op: 'gte', value: '2026-01-01T00:00:00.000Z' },
    ])
    expect(buildFilters(carrier, view, { creditLimit: { to: '10' } })).toEqual([{ field: 'creditLimit', op: 'lte', value: '10' }])
    // Only whitelisted fields, whatever the table asked for.
    expect(buildSorts(view, [{ id: 'creditLimit', desc: true }])).toEqual([{ field: 'creditLimit', asc: false }])
    expect(buildSorts(view, [{ id: 'ratio', desc: false }])).toEqual([])
    expect(buildSorts(view, [])).toEqual([])
  })
})
