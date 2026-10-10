import { afterEach, describe, expect, it } from 'vitest'
import { displayLocale, formatAmount, formatDate, formatDateTime, regionOf, setAmountUnit } from './format'

describe('regionOf', () => {
  it('accepts a language with a region, canonically', () => {
    expect(regionOf(undefined)).toBeUndefined()
    expect(regionOf('')).toBeUndefined()
    expect(regionOf('en-us')).toBe('en-US')
  })

  it('refuses a bare language or nonsense', () => {
    expect(() => regionOf('en')).toThrow(/such as en-US/)
    expect(() => regionOf('not a locale!')).toThrow(/such as en-US/)
  })
})

describe('dates and times', () => {
  it('keep the neutral forms without a region', () => {
    expect(formatDate('2026-01-31', undefined)).toBe('2026-01-31')
    expect(formatDateTime('2026-01-31T14:05:09', undefined)).toBe('2026-01-31 14:05:09')
  })

  it('follow the region: MM/DD/YYYY in the United States', () => {
    expect(formatDate('2026-01-31', 'en-US')).toBe('01/31/2026')
    expect(formatDate('2026-01-31', 'en-GB')).toBe('31/01/2026')
    expect(formatDateTime('2026-01-31T14:05:09', 'en-US')).toBe('01/31/2026, 02:05:09 PM')
  })

  it('leave what is not a date as it is', () => {
    expect(formatDate('soon', 'en-US')).toBe('soon')
    expect(formatDateTime('never', 'en-US')).toBe('never')
  })
})

describe('formatAmount', () => {
  it('groups thousands and shows a fixed number of decimals', () => {
    expect(formatAmount('1234.5', { scale: 2, locale: 'en-US' })).toBe('1,234.50')
    expect(formatAmount('0', { scale: 2, locale: 'en-US' })).toBe('0.00')
    expect(formatAmount('826012.9', { scale: 2, currency: 'USD', locale: 'en-US' })).toBe('$826,012.90')
  })

  it('shows negatives with a minus, or in parentheses for statements', () => {
    expect(formatAmount('-2000', { scale: 2, locale: 'en-US' })).toBe('-2,000.00')
    expect(formatAmount('-2000', { scale: 2, locale: 'en-US', negative: 'parentheses' })).toBe('(2,000.00)')
    expect(formatAmount('-1362.9', { scale: 2, currency: 'USD', locale: 'en-US', negative: 'parentheses' }))
      .toBe('($1,362.90)')
  })

  it('keeps every digit of large exact amounts', () => {
    expect(formatAmount('12345678901234567.89', { scale: 2, locale: 'en-US' })).toBe('12,345,678,901,234,567.89')
  })

  it('rounds half away from zero for display only', () => {
    expect(formatAmount('0.125', { scale: 2, locale: 'en-US' })).toBe('0.13')
    expect(formatAmount('-0.125', { scale: 2, locale: 'en-US', negative: 'parentheses' })).toBe('(0.13)')
  })

  it('shows what is not a number as text', () => {
    expect(formatAmount('abc', { scale: 2 })).toBe('abc')
    expect(formatAmount(null, { scale: 2 })).toBe('')
  })
})

describe('the display unit of amounts (decision D34)', () => {
  afterEach(() => setAmountUnit('JPY', undefined))

  it('replaces the symbol of the application currency by its label', () => {
    setAmountUnit('jpy', 'Kudos')
    expect(formatAmount('1234', { scale: 0, locale: 'en-US', currency: 'JPY' })).toBe('1,234\u00a0Kudos')
    expect(formatAmount('-20', { scale: 0, locale: 'en-US', currency: 'JPY', negative: 'parentheses' }))
      .toBe('(20\u00a0Kudos)')
  })

  it('leaves numbers without a currency and other currencies as they are', () => {
    setAmountUnit('JPY', 'Kudos')
    expect(formatAmount('12', { scale: 0, locale: 'en-US' })).toBe('12')
    expect(formatAmount('5', { scale: 2, locale: 'en-US', currency: 'USD' })).toBe('$5.00')
  })

  it('restores the symbol when the label is removed', () => {
    setAmountUnit('JPY', 'Kudos')
    setAmountUnit('JPY', ' ')
    expect(formatAmount('12', { scale: 0, locale: 'en-US', currency: 'JPY' })).toBe('¥12')
  })
})

describe('displayLocale', () => {
  it('is the region when there is one, else the interface language', () => {
    expect(displayLocale('en', 'en-US')).toBe('en-US')
    expect(displayLocale('ja', undefined)).toBe('ja')
  })
})
