import { describe, expect, it } from 'vitest'
import { enabledLanguagesOf } from './languages'

describe('enabledLanguagesOf', () => {
  it('is every platform language when the application names none', () => {
    expect(enabledLanguagesOf(undefined)).toEqual(['zh', 'ja', 'en'])
    expect(enabledLanguagesOf('  ')).toEqual(['zh', 'ja', 'en'])
  })

  it('keeps the named ones in the platform order', () => {
    expect(enabledLanguagesOf('en')).toEqual(['en'])
    expect(enabledLanguagesOf('en, zh,en')).toEqual(['zh', 'en'])
  })

  it('stops at languages the platform does not have, naming them', () => {
    expect(() => enabledLanguagesOf('en,fr,de')).toThrow(/fr, de not among the platform languages/)
    expect(() => enabledLanguagesOf(',')).toThrow(/names no language/)
  })
})
