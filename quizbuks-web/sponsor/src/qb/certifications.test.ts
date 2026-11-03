import { describe, expect, it } from 'vitest'
import { certificationName, certificationNamesOf, isSponsorCertification } from './certifications'

/** Q88a: the catalog's certifications are named by the texts, a sponsor's own by its row, in the reader's language. */
describe('certification names', () => {
  const tQb = ((key: string, options?: { defaultValue?: string }) =>
    key === 'certification.names.REFERRER' ? 'May refer friends' : options?.defaultValue ?? key) as never

  it('tell a sponsor’s key from the catalog’s', () => {
    expect(isSponsorCertification('sp:0192f0a0-0000-7000-8000-00000000a001')).toBe(true)
    expect(isSponsorCertification('REFERRER')).toBe(false)
    expect(isSponsorCertification(null)).toBe(false)
  })

  it('read the names a template gives, as JSON text or an object, dropping blanks', () => {
    expect(certificationNamesOf('{"en":"Keyboard expert","ja":" "}')).toEqual({ en: 'Keyboard expert' })
    expect(certificationNamesOf({ zh: '键盘达人' })).toEqual({ zh: '键盘达人' })
    expect(certificationNamesOf('not json')).toBeNull()
    expect(certificationNamesOf(null)).toBeNull()
    expect(certificationNamesOf('{}')).toBeNull()
  })

  it('name the catalog’s by the texts and a sponsor’s by its row, falling back by language', () => {
    expect(certificationName('REFERRER', null, 'ja', tQb)).toBe('May refer friends')
    const names = { en: 'Keyboard expert', ja: 'キーボード達人' }
    expect(certificationName('sp:1', names, 'ja', tQb)).toBe('キーボード達人')
    expect(certificationName('sp:1', names, 'zh-CN', tQb)).toBe('Keyboard expert')
    expect(certificationName('sp:1', null, 'en', tQb)).toBe('sp:1')
    expect(certificationName('NO_SUCH', null, 'en', tQb)).toBe('NO_SUCH')
  })
})
