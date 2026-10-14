import { formatAmount } from '@jabiz/client'
import { describe, expect, it } from 'vitest'
import { formatKudos } from './kudos'

describe('Kudos', () => {
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

  it('keeps an empty value empty', () => {
    expect(formatKudos(null)).toBe('')
    expect(formatKudos(undefined)).toBe('')
  })
})
