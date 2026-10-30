import { describe, expect, it } from 'vitest'
import { filterFields, toFilters, type MoreFilter } from './moreFilters'

const FILTERS: MoreFilter[] = [
  { kind: 'eq', name: 'countryCode', label: 'Country', upper: true },
  { kind: 'eq', name: 'voucherNo', label: 'Voucher' },
  { kind: 'like', name: 'company', label: 'Company' },
  { kind: 'dictionary', name: 'status', label: 'State', dictionary: 'urn:jabiz:dict:quizbuks:operator-status' },
  { kind: 'choice', name: 'decision', label: 'Decision', options: [{ value: 'APPROVE', label: 'Approved' }] },
  { kind: 'yesno', name: 'banned', label: 'Banned' },
  { kind: 'amount', name: 'amount', label: 'Amount' },
  { kind: 'time', name: 'createdAt', label: 'Created' },
  { kind: 'date', name: 'periodFrom', label: 'Period' },
]

describe('moreFilters (Q53b)', () => {
  it('gives the filter bar a field of each kind, a choice with its options', () => {
    const fields = filterFields(FILTERS, { status: [{ value: 'ACTIVE', label: 'Active' }],
      banned: [{ value: 'true', label: 'Yes' }] })
    expect(fields.map((field) => field.kind)).toEqual(['text', 'text', 'text', 'select', 'select', 'select',
      'number-range', 'datetime-range', 'date-range'])
    expect(fields[3]).toMatchObject({ options: [{ value: 'ACTIVE', label: 'Active' }] })
    expect(fields[4]).toMatchObject({ options: [{ value: 'APPROVE', label: 'Approved' }] })
  })

  it('turns the values into the template\'s filters', () => {
    expect(toFilters(FILTERS, {
      countryCode: ' jp ',
      voucherNo: ' QBJ-1 ',
      company: 'Acme',
      status: 'ACTIVE',
      banned: 'false',
      amount: { from: '100', to: '500' },
      createdAt: { from: '2026-10-01T00:00:00.000Z' },
      periodFrom: { to: '2026-09-30' },
    })).toEqual([
      { field: 'countryCode', op: 'eq', value: 'JP' },
      { field: 'voucherNo', op: 'eq', value: 'QBJ-1' },
      { field: 'company', op: 'like', value: '%Acme%' },
      { field: 'status', op: 'eq', value: 'ACTIVE' },
      { field: 'banned', op: 'eq', value: 'false' },
      { field: 'amount', op: 'between', from: '100', to: '500' },
      { field: 'createdAt', op: 'gte', value: '2026-10-01T00:00:00.000Z' },
      { field: 'periodFrom', op: 'lte', value: '2026-09-30' },
    ])
  })

  it('gives no filter for empty fields and open ranges', () => {
    expect(toFilters(FILTERS, { voucherNo: '  ', company: null, amount: {}, createdAt: { from: null, to: '' } }))
      .toEqual([])
  })
})
