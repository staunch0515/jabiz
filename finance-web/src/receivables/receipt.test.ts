import { describe, expect, it } from 'vitest'
import type { Suggestion } from './api'
import { applied, suggest, toApplications, unapplied, unreadable } from './receipt'

const open = (invoiceId: string, openAmount: string, matched = 'OLDEST', discountOffered?: string): Suggestion => ({
  rank: 1, matched, invoiceId, invoiceNo: invoiceId, invoiceDate: '2026-01-01', openAmount, discountOffered,
})

describe('applying a receipt', () => {
  it('spreads the money over the best matches first, each up to what is open', () => {
    const entries = suggest('20,000.00', [open('INV-1003', '30000.00'), open('INV-1007', '25000.00')])
    expect(entries).toEqual({ 'INV-1003': { amount: '20000.00', discount: '' } })
    expect(unapplied('20,000.00', entries)).toBe('0.00')
    const two = suggest('40000', [open('a', '30000.00'), open('b', '25000.00')])
    expect(two).toEqual({ a: { amount: '30000.00', discount: '' }, b: { amount: '10000.00', discount: '' } })
  })

  it('takes the discount on offer when the amount matched the invoice less it', () => {
    const entries = suggest('980.00', [open('INV-9', '1000.00', 'AMOUNT_LESS_DISCOUNT', '20.00')])
    expect(entries).toEqual({ 'INV-9': { amount: '980.00', discount: '20.00' } })
    expect(toApplications(entries, ['INV-9'])).toEqual([{ invoiceId: 'INV-9', amount: '980.00', discount: '20.00' }])
  })

  it('adds what is typed exactly and shows more applied than received as negative', () => {
    const entries = { a: { amount: '0.10', discount: '' }, b: { amount: '0.20', discount: '' }, c: { amount: '', discount: '' } }
    expect(applied(entries)).toBe('0.30')
    expect(unapplied('0.25', entries)).toBe('-0.05')
    expect(toApplications(entries, ['c', 'b', 'a']).map((a) => a.invoiceId)).toEqual(['b', 'a'])
    expect(unreadable(entries)).toBe(false)
    expect(unreadable({ a: { amount: '1.234', discount: '' } })).toBe(true)
  })
})
