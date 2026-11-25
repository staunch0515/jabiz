import { describe, expect, it } from 'vitest'
import { postAge } from './postAge'

const now = '2026-11-26T12:00:00Z'

describe('a post\'s age (Q113 §9.1, P8)', () => {
  it('says minutes under an hour, never zero', () => {
    expect(postAge('2026-11-26T11:59:40Z', now)).toEqual({ unit: 'minutes', count: 1 })
    expect(postAge('2026-11-26T11:01:00Z', now)).toEqual({ unit: 'minutes', count: 59 })
  })

  it('says hours under a day and days under a week', () => {
    expect(postAge('2026-11-26T11:00:00Z', now)).toEqual({ unit: 'hours', count: 1 })
    expect(postAge('2026-11-25T12:00:01Z', now)).toEqual({ unit: 'hours', count: 23 })
    expect(postAge('2026-11-23T12:00:00Z', now)).toEqual({ unit: 'days', count: 3 })
    expect(postAge('2026-11-19T12:00:01Z', now)).toEqual({ unit: 'days', count: 6 })
  })

  it('gives the date from a week on, with the year when it is another', () => {
    expect(postAge('2026-11-19T12:00:00Z', now)).toEqual({ date: new Date('2026-11-19T12:00:00Z'), withYear: false })
    expect(postAge('2025-12-01T00:00:00Z', now)).toMatchObject({ withYear: true })
  })

  it('takes a start a moment ahead as now, and nothing that is not a time', () => {
    expect(postAge('2026-11-26T12:00:30Z', now)).toEqual({ unit: 'minutes', count: 1 })
    expect(postAge('', now)).toBeNull()
    expect(postAge('2026-11-26T11:00:00Z', 'soon')).toBeNull()
  })
})
