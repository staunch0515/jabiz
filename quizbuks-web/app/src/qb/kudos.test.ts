import { changeLanguage, formatAmount } from '@jabiz/client'
import { afterEach, describe, expect, it } from 'vitest'
import { formatKudos, kudosName } from './kudos'

describe('Kudos', () => {
  afterEach(async () => {
    await changeLanguage('en')
  })

  it('shows ledger amounts as Kudos with grouping and no decimals', () => {
    expect(formatKudos(1234)).toBe('1,234 Kudos')
    expect(formatKudos('1234567')).toBe('1,234,567 Kudos')
    expect(formatKudos(0)).toBe('0 Kudos')
    expect(formatKudos(-50)).toBe('-50 Kudos')
  })

  it('never names the ledger currency, also where the platform formats a yen amount', () => {
    expect(formatKudos(500)).not.toMatch(/JPY|¥|￥/)
    expect(formatAmount(500, { currency: 'JPY', scale: 0 })).toBe('500 Kudos')
  })

  it('names the points in the language of the interface, and follows it when it changes (Q61)', async () => {
    expect(kudosName('zh-CN')).toBe('学分')
    expect(kudosName('ja')).toBe('クドス')
    expect(kudosName('fr')).toBe('Kudos')
    await changeLanguage('zh')
    // The space before the unit may be a no-break space, as the locale writes it.
    expect(formatKudos(1234)).toMatch(/^1,234\s学分$/)
    expect(formatAmount(500, { currency: 'JPY', scale: 0 })).toMatch(/^500\s学分$/)
    await changeLanguage('ja')
    expect(formatKudos(1234)).toMatch(/^1,234\sクドス$/)
  })

  it('keeps an empty value empty', () => {
    expect(formatKudos(null)).toBe('')
    expect(formatKudos(undefined)).toBe('')
  })
})
