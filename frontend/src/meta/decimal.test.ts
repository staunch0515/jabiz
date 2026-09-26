import { describe, expect, it } from 'vitest'
import { compareDecimal, formatDecimal, parseDecimal, precision, stripTrailingZeros, toDecimal } from './decimal'

const d = (text: string) => parseDecimal(text)!

describe('decimal', () => {
  it('parses what new BigDecimal(String) accepts', () => {
    expect(d('12.340')).toEqual({ unscaled: 12340n, scale: 3 })
    expect(d('-0.5')).toEqual({ unscaled: -5n, scale: 1 })
    expect(d('.5')).toEqual({ unscaled: 5n, scale: 1 })
    expect(d('1.')).toEqual({ unscaled: 1n, scale: 0 })
    expect(d('+7')).toEqual({ unscaled: 7n, scale: 0 })
    expect(d('1e3')).toEqual({ unscaled: 1n, scale: -3 })
    expect(d('1.5E-3')).toEqual({ unscaled: 15n, scale: 4 })
    for (const bad of ['', '.', '-', 'abc', '1,000', '1e', '1.2.3', ' 1']) expect(parseDecimal(bad)).toBeNull()
  })

  it('compares by value regardless of scale', () => {
    expect(compareDecimal(d('1.0'), d('1'))).toBe(0)
    expect(compareDecimal(d('0.1'), d('0.09'))).toBe(1)
    expect(compareDecimal(d('-1'), d('0'))).toBe(-1)
    expect(compareDecimal(d('1e3'), d('999.999'))).toBe(1)
  })

  it('strips trailing zeros and counts precision like BigDecimal', () => {
    expect(stripTrailingZeros(d('12.3400'))).toEqual({ unscaled: 1234n, scale: 2 })
    expect(stripTrailingZeros(d('100'))).toEqual({ unscaled: 1n, scale: -2 })
    expect(stripTrailingZeros(d('0.000'))).toEqual({ unscaled: 0n, scale: 0 })
    expect(precision(d('123.45'))).toBe(5)
    expect(precision(d('0'))).toBe(1)
  })

  it('converts numbers and text parameters', () => {
    expect(toDecimal(0.1)).toEqual({ unscaled: 1n, scale: 1 })
    expect(toDecimal(' 2 ')).toEqual({ unscaled: 2n, scale: 0 })
    expect(toDecimal(Number.NaN)).toBeNull()
    expect(toDecimal(true)).toBeNull()
  })

  it('formats as plain text', () => {
    expect(formatDecimal(d('1e3'))).toBe('1000')
    expect(formatDecimal(d('-0.05'))).toBe('-0.05')
    expect(formatDecimal(d('12.30'))).toBe('12.30')
    expect(formatDecimal(d('0'))).toBe('0')
  })
})
