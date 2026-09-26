import dayjs from 'dayjs'
import { describe, expect, it } from 'vitest'
import { carrier, field } from '../test/fixtures'
import { changedAttributes, formFieldsOf, sameValue, wireAttributes } from './entityForm'

describe('entity form', () => {
  it('offers neither system, generated nor sensitive fields; immutable ones are read-only when editing', () => {
    expect(formFieldsOf(carrier, 'create').map((f) => [f.field.name, f.disabled])).toEqual([
      ['carrierCode', false],
      ['note', false],
      ['countryCode', false],
      ['creditLimit', false],
      ['ratio', false],
      ['active', false],
      ['cell', false],
    ])
    expect(formFieldsOf(carrier, 'edit').find((f) => f.field.name === 'carrierCode')?.disabled).toBe(true)
  })

  it('sends what was entered; emptied fields as null', () => {
    const fields = formFieldsOf(carrier, 'create')
    expect(
      wireAttributes(fields, { carrierCode: 'AB', note: '', creditLimit: 5, active: false, ratio: undefined }),
    ).toEqual({ carrierCode: 'AB', note: null, creditLimit: '5', active: false })
  })

  it('sends only what changed on update', () => {
    const fields = formFieldsOf(carrier, 'edit')
    const original = { carrierCode: 'AB', note: 'x', creditLimit: 100, ratio: 1.5, active: true, countryCode: 'JP' }
    expect(
      changedAttributes(fields, original, {
        carrierCode: 'ZZ', // read-only: never sent
        note: '',
        creditLimit: '100.0',
        ratio: '1.50',
        active: false,
        countryCode: 'JP',
      }),
    ).toEqual({ note: null, active: false })
  })

  it('compares values by meaning', () => {
    expect(sameValue(field('creditLimit'), 100, '1e2')).toBe(true)
    expect(sameValue(field('effectStartTime'), '2026-01-01T09:00:00+09:00', dayjs('2026-01-01T00:00:00Z').toISOString())).toBe(true)
    expect(sameValue(field('effectStartTime'), '2026-01-01T00:00:00.123456Z', '2026-01-01T00:00:00.123Z')).toBe(true)
    expect(sameValue(field('effectStartTime'), '2026-01-01T00:00:00.124Z', '2026-01-01T00:00:00.123Z')).toBe(false)
    expect(sameValue(field('cell'), { a: 1 }, { a: 1 })).toBe(true)
    expect(sameValue(field('note'), null, undefined)).toBe(true)
    expect(sameValue(field('note'), null, '')).toBe(false)
  })
})
