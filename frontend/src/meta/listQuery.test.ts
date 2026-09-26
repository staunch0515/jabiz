import dayjs from 'dayjs'
import { describe, expect, it } from 'vitest'
import i18n from '../i18n'
import { carrier, countries } from '../test/fixtures'
import { buildColumns, toRow } from './columns'
import { buildFilters, buildSorts, columnFields, listViewOf, searchKindOf } from './listQuery'

const view = listViewOf(carrier, undefined)!
const t = i18n.t.bind(i18n)

describe('list view adapter', () => {
  it('shows the list view columns, never sensitive fields', () => {
    expect(columnFields(carrier, view).map((f) => f.name)).toEqual([
      'carrierCode',
      'countryCode',
      'creditLimit',
      'active',
      'effectStartTime',
    ])
    expect(listViewOf(carrier, 'other')).toBeUndefined()
    // Without a list view: the plain fields, at most eight.
    expect(columnFields(carrier, undefined).map((f) => f.name)).toEqual([
      'carrierId',
      'carrierCode',
      'note',
      'countryCode',
      'creditLimit',
      'ratio',
      'active',
      'cell',
    ])
  })

  it('searches each kind with an operator it allows', () => {
    expect(carrier.fields.map((f) => searchKindOf(f))).toEqual([
      'eq-text',
      'like',
      'like',
      'select',
      'decimal-range',
      'decimal-range',
      'bool',
      null,
      'eq-text',
      'time-range',
      null,
    ])
  })

  it('builds filters of whitelisted fields only', () => {
    const from = dayjs('2026-01-01T00:00:00Z')
    const filters = buildFilters(carrier, view, {
      carrierCode: ' AB ',
      countryCode: 'JP',
      creditLimit: [100, undefined],
      active: 'false',
      effectStartTime: [from.toISOString(), '2026-02-01T00:00:00Z'],
      ratio: [1, 2], // not whitelisted
      note: '',
      current: 1,
    })
    expect(filters).toEqual([
      { field: 'carrierCode', op: 'like', value: '%AB%' },
      { field: 'countryCode', op: 'eq', value: 'JP' },
      { field: 'creditLimit', op: 'gte', value: '100' },
      { field: 'active', op: 'eq', value: false },
      { field: 'effectStartTime', op: 'between', from: '2026-01-01T00:00:00.000Z', to: '2026-02-01T00:00:00.000Z' },
    ])
    expect(buildFilters(carrier, view, { creditLimit: [undefined, 5] })).toEqual([
      { field: 'creditLimit', op: 'lte', value: '5' },
    ])
    expect(buildFilters(carrier, undefined, { carrierCode: 'x' })).toEqual([])
  })

  it('sorts by whitelisted fields only', () => {
    expect(buildSorts(view, { creditLimit: 'descend', ratio: 'ascend', carrierCode: null })).toEqual([
      { field: 'creditLimit', asc: false },
    ])
  })

  it('generates table columns with search inputs and sorting from the view', () => {
    const columns = buildColumns(carrier, view, countries, t, 'en')
    const byKey = Object.fromEntries(columns.map((c) => [String(c.dataIndex), c]))
    expect(byKey.carrierCode).toMatchObject({ sorter: true, hideInSearch: false, valueType: 'text', defaultSortOrder: 'ascend' })
    expect(byKey.countryCode).toMatchObject({ sorter: false, valueType: 'select' })
    expect(byKey.creditLimit).toMatchObject({ valueType: 'digitRange' })
    expect(byKey.effectStartTime).toMatchObject({ valueType: 'dateTimeRange' })
    // Filters without a column still get a hidden-in-table search input.
    expect(byKey.note).toMatchObject({ hideInTable: true, hideInSearch: false })
    expect(byKey.secret).toBeUndefined()
    const row = toRow({ id: 'x1', version: 3, attributes: { countryCode: 'CN' } })
    expect(row.__key).toBe('x1')
    const render = byKey.countryCode.render as (dom: unknown, row: unknown) => unknown
    expect(render(null, row)).toBe('China')
  })
})
