import { describe, expect, it } from 'vitest'
import { currencyOf, guessCountry } from './country'

const KNOWN = new Set(['JP', 'CN', 'US', 'FR', 'DE', 'GB', 'IN', 'BR'])

describe('prefilling the country', () => {
  it('takes the region of the first language that names one', () => {
    expect(guessCountry(['ja-JP', 'en-US'], 'Europe/Paris', KNOWN)).toBe('JP')
    expect(guessCountry(['en', 'fr-FR'], 'Asia/Tokyo', KNOWN)).toBe('FR')
    expect(guessCountry(['zh-Hans-CN'], undefined, KNOWN)).toBe('CN')
  })

  it('falls back to the time zone when no language names a region', () => {
    expect(guessCountry(['ja'], 'Asia/Tokyo', KNOWN)).toBe('JP')
    expect(guessCountry(['en'], 'America/Los_Angeles', KNOWN)).toBe('US')
    expect(guessCountry([], 'Europe/Berlin', KNOWN)).toBe('DE')
  })

  it('offers nothing it cannot tell, or a country the dictionary does not have', () => {
    expect(guessCountry(['en'], 'UTC', KNOWN)).toBeNull()
    expect(guessCountry(['en-AU'], 'Australia/Sydney', KNOWN)).toBeNull()
    expect(guessCountry(['not a tag!'], undefined, KNOWN)).toBeNull()
  })
})

describe('the reference currency', () => {
  const offered = ['USD', 'EUR', 'GBP', 'JPY', 'CNY', 'INR']
  it('follows the country', () => {
    expect(currencyOf('JP', offered)).toBe('JPY')
    expect(currencyOf('FR', offered)).toBe('EUR')
    expect(currencyOf('GB', offered)).toBe('GBP')
    expect(currencyOf('BR', offered)).toBe('USD')
    expect(currencyOf(null, offered)).toBe('USD')
  })

  it('stays among the offered ones', () => {
    expect(currencyOf('JP', ['USD', 'EUR'])).toBe('USD')
    expect(currencyOf('JP', ['EUR'])).toBe('EUR')
  })
})
