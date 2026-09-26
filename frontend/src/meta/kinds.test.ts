import dayjs from 'dayjs'
import { describe, expect, it } from 'vitest'
import i18n from '../i18n'
import { carrier, countries, field } from '../test/fixtures'
import { controlOf, enabledCodes, fieldLabel, formatValue, optionsOf, scaleOf, toFormValue, toWireValue } from './kinds'

const t = i18n.t.bind(i18n)

describe('semantic kinds', () => {
  it('choose the input control', () => {
    expect(carrier.fields.map((f) => [f.name, controlOf(f)])).toEqual([
      ['carrierId', 'text'],
      ['carrierCode', 'text'],
      ['note', 'textarea'],
      ['countryCode', 'select'],
      ['creditLimit', 'decimal'],
      ['ratio', 'decimal'],
      ['active', 'switch'],
      ['secret', 'text'],
      ['cell', 'json'],
      ['effectStartTime', 'datetime'],
      ['versionNo', 'integer'],
    ])
    expect(scaleOf(field('ratio'))).toBe(2)
    expect(scaleOf(field('carrierCode'))).toBeUndefined()
  })

  it('label fields by the server, else by the UI for system fields', async () => {
    await i18n.changeLanguage('en')
    expect(fieldLabel(field('creditLimit'), t)).toBe('Credit limit')
    expect(fieldLabel(field('effectStartTime'), t)).toBe('Effective from')
    expect(fieldLabel(field('note'), t)).toBe('note')
  })

  it('offer enabled dictionary items in their order, else the fixed values', () => {
    expect(optionsOf(field('countryCode'), countries)).toEqual([
      { label: 'China', value: 'CN' },
      { label: 'Japan', value: 'JP' },
    ])
    const fixed = { ...field('countryCode'), allowedValues: ['A', 'B'] } as typeof carrier.fields[number]
    expect(optionsOf(fixed, {})).toEqual([
      { label: 'A', value: 'A' },
      { label: 'B', value: 'B' },
    ])
    expect(optionsOf(field('active'), countries)).toEqual([])
    expect([...enabledCodes(countries)['urn:country']].sort()).toEqual(['CN', 'JP'])
  })

  it('format values for display', () => {
    expect(formatValue(field('creditLimit'), 1234567, {}, t, 'en')).toBe('¥1,234,567')
    expect(formatValue(field('ratio'), '1.50', {}, t, 'en')).toBe('1.50')
    expect(formatValue(field('active'), true, {}, t, 'en')).toBe('Yes')
    expect(formatValue(field('countryCode'), 'JP', countries, t, 'en')).toBe('Japan')
    expect(formatValue(field('countryCode'), 'ZZ', countries, t, 'en')).toBe('ZZ')
    expect(formatValue(field('cell'), { a: 1 }, {}, t, 'en')).toBe('{"a":1}')
    expect(formatValue(field('carrierCode'), null, {}, t, 'en')).toBe('—')
    expect(formatValue(field('effectStartTime'), '2026-01-31T09:00:00Z', {}, t, 'en')).toBe(
      dayjs('2026-01-31T09:00:00Z').format('YYYY-MM-DD HH:mm:ss'),
    )
  })

  it('convert between form and wire values', () => {
    const time = dayjs('2026-01-31T09:00:00Z')
    expect(toWireValue(field('effectStartTime'), time)).toBe('2026-01-31T09:00:00.000Z')
    expect(toWireValue(field('creditLimit'), 12)).toBe('12')
    expect(toWireValue(field('creditLimit'), '')).toBeNull()
    expect(toWireValue(field('cell'), '{"a":1}')).toEqual({ a: 1 })
    expect(toWireValue(field('cell'), 'not json')).toBe('not json')
    expect(toWireValue(field('carrierCode'), undefined)).toBeUndefined()
    expect(toFormValue(field('creditLimit'), 1e3)).toBe('1000')
    expect(dayjs.isDayjs(toFormValue(field('effectStartTime'), '2026-01-31T09:00:00Z'))).toBe(true)
    expect(toFormValue(field('cell'), { a: 1 })).toBe('{"a":1}')
    expect(toFormValue(field('carrierCode'), null)).toBeUndefined()
  })
})
