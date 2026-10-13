import dayjs from 'dayjs'
import { describe, expect, it } from 'vitest'
import type { FieldMeta } from './types'
import { carrier } from '../test/fixtures'
import { buildFilters, buildSorts, columnFields, listViewOf, searchKindOf } from './listQuery'

const view = listViewOf(carrier, undefined)!
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

  it('filters dates by day, both ends included', () => {
    const due = { ...carrier.fields[9], name: 'dueDate', type: 'date', systemManaged: false } as FieldMeta
    const dated = { ...carrier, fields: [...carrier.fields, due] }
    const dueView = { ...view, filters: ['dueDate'] }
    expect(searchKindOf(due)).toBe('date-range')
    expect(buildFilters(dated, dueView, { dueDate: [dayjs('2026-01-01T23:00:00'), '2026-01-31'] })).toEqual([
      { field: 'dueDate', op: 'between', from: '2026-01-01', to: '2026-01-31' },
    ])
    expect(buildFilters(dated, dueView, { dueDate: [undefined, '2026-01-31'] })).toEqual([
      { field: 'dueDate', op: 'lte', value: '2026-01-31' },
    ])
  })

  it('sorts by whitelisted fields only', () => {
    expect(buildSorts(view, [
      { id: 'creditLimit', desc: true },
      { id: 'ratio', desc: false },
    ])).toEqual([{ field: 'creditLimit', asc: false }])
  })
})
