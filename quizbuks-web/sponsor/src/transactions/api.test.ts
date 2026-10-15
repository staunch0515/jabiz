import { describe, expect, it } from 'vitest'
import { SPONSOR } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { filtersOf, NO_FILTERS, paramsOf, startOfDay, toTransaction, transactionsOf } from './api'
import { transactionsMessages } from './messages'

describe('the transactions parameters', () => {
  it('are only the filters set, the text trimmed', () => {
    expect(paramsOf(NO_FILTERS)).toEqual({})
    expect(paramsOf({ ...NO_FILTERS, q: '   ' })).toEqual({})
    expect(paramsOf({ ...NO_FILTERS, type: 'REWARD', status: 'FAILED', publicationId: 'p1', userKey: 'k1',
      q: ' Mia ' })).toEqual({ type: 'REWARD', status: 'FAILED', publicationId: 'p1', userKey: 'k1', q: 'Mia' })
  })

  it('make the period whole days in the browser\'s time zone, the last day included', () => {
    const params = paramsOf({ ...NO_FILTERS, from: '2026-10-01', to: '2026-10-31' })
    expect(params.from).toBe(new Date(2026, 9, 1).toISOString())
    expect(params.to).toBe(new Date(2026, 10, 1).toISOString())
    // An open end stays open; a day that is no day is no bound.
    expect(paramsOf({ ...NO_FILTERS, to: '2026-12-31' })).toEqual({ to: new Date(2027, 0, 1).toISOString() })
    expect(startOfDay('31/12/2026')).toBeNull()
    expect(startOfDay(null)).toBeNull()
  })

  it('come from the address for one publication only', () => {
    expect(filtersOf('?publication=p%201')).toEqual({ ...NO_FILTERS, publicationId: 'p 1' })
    expect(filtersOf('?publication=&user=u1&type=TOPUP')).toEqual(NO_FILTERS)
    expect(transactionsOf('p 1')).toBe('/transactions?publication=p%201')
  })
})

describe('a transaction row', () => {
  it('keeps the amounts as the server sends them and the score as numbers', () => {
    expect(toTransaction({ entryId: 'a1', type: 'REWARD', status: 'PROCESSING', amount: '600', refunded: null,
      occurredAt: '2026-10-01T00:00:00Z', publicationId: 'p1', publicationName: 'Big prize', userKey: 'k1',
      userName: 'Mia', score: '5', fullScore: '5' })).toEqual({ entryId: 'a1', type: 'REWARD', status: 'PROCESSING',
      amount: '600', refunded: null, occurredAt: '2026-10-01T00:00:00Z', publicationId: 'p1',
      publicationName: 'Big prize', userKey: 'k1', userName: 'Mia', score: 5, fullScore: 5 })
    expect(toTransaction({ entryId: 't1', type: 'TOPUP', status: 'COMPLETED', amount: 1000, refunded: '200',
      occurredAt: '2026-10-01T00:00:00Z' })).toMatchObject({ amount: '1000', refunded: '200', publicationId: null,
      userKey: null, userName: null, score: null, fullScore: null })
  })
})

describe('the transactions texts', () => {
  it('have the same keys in every language, none of them empty, and name no currency but Kudos', () => {
    const english = keyPaths(transactionsMessages.en)
    expect(english.length).toBeGreaterThan(10)
    for (const lng of LANGUAGES) {
      expect(keyPaths(transactionsMessages[lng]), lng).toEqual(english)
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
      expect(JSON.stringify(transactionsMessages[lng]), lng).not.toMatch(/JPY|yen|円|日元|USD|\$/i)
    }
  })
})
