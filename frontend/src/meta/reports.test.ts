import { describe, expect, it } from 'vitest'
import { buildFilters, buildSorts, listViewOf } from './listQuery'
import { pointInTimeOf, reportEntity, reportGroups, type QueryEntry } from './reports'

const balances: QueryEntry = {
  id: 'jabiz.ledger.account_balances',
  title: 'Trial balance',
  version: 'c2741d77d343f5ede1c4130251ff5848eb9b3dabcfb1e1eb4f402d774be36e9a',
  params: { type: 'object', properties: { asOf: { type: 'string', format: 'date-time' } }, required: ['asOf'] },
  results: [
    { name: 'accountCode', label: 'Account', kind: { type: 'text', maxLength: 20 }, operators: ['EQ', 'LIKE'] },
    { name: 'balance', label: 'Balance', kind: { type: 'monetary', currency: 'JPY', scale: 0 },
      operators: ['BETWEEN', 'GTE', 'LTE'] },
  ],
  filters: ['accountCode', 'balance'],
  sorts: ['accountCode'],
  defaultSort: { field: 'accountCode', asc: true },
  timeSlice: { knownAt: 'knownAt' },
  timeTravel: false,
  report: { landscape: false },
}

describe('reports', () => {
  it('lists only reports, grouped by the first part of their id', () => {
    const groups = reportGroups([
      balances,
      { id: 'jabiz.ledger.account_activity', title: 'Account activity', report: {} },
      { id: 'commerce.stock_availability', title: 'Stock', report: {} },
      { id: 'commerce.internal', title: 'Not a report' },
    ])
    expect(groups.map((g) => g.group)).toEqual(['commerce', 'jabiz'])
    expect(groups[1].reports.map((r) => r.title)).toEqual(['Account activity', 'Trial balance'])
    expect(groups[0].reports).toHaveLength(1)
  })

  it('turns result columns into fields the list helpers search and sort by', () => {
    const entity = reportEntity(balances)
    const view = listViewOf(entity, 'default')
    expect(entity.fields.map((f) => [f.name, f.label, f.type])).toEqual([
      ['accountCode', 'Account', 'text'],
      ['balance', 'Balance', 'monetary'],
    ])
    expect(view?.defaultSort).toEqual({ field: 'accountCode', asc: true })
    expect(buildFilters(entity, view, { accountCode: '10', balance: ['0', undefined], other: 'x' })).toEqual([
      { field: 'accountCode', op: 'like', value: '%10%' },
      { field: 'balance', op: 'gte', value: '0' },
    ])
    expect(buildSorts(view, { accountCode: 'descend', balance: 'ascend' })).toEqual([{ field: 'accountCode', asc: false }])
  })

  it('knows where a run takes its point in time from', () => {
    expect(pointInTimeOf(balances)).toBe('parameters')
    expect(pointInTimeOf({ ...balances, timeSlice: undefined, timeTravel: true })).toBe('request')
    expect(pointInTimeOf({ ...balances, timeSlice: undefined, timeTravel: false })).toBe('none')
  })
})
