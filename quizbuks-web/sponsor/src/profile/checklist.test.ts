import { describe, expect, it } from 'vitest'
import { checklist, progress, type ChecklistFacts } from './checklist'

const APPROVED = { contactName: 'Sara', company: 'Acme', intro: null, avatar: null, reviewStatus: 'APPROVED' as const,
  submittedAt: null, approvedAt: '2026-10-14T00:00:00Z', rejectReason: null, rejectedAt: null, revision: 2 }
const PAGE = { handle: 'acme', language: 'en' as const, headline: 'Bread', cover: null, website: null,
  status: 'PUBLISHED' as const, rejectReason: null, takedownReason: null, takenDownAt: null,
  publishedAt: '2026-10-15T00:00:00Z', reviewRequired: false, revision: 3, languages: ['en' as const], translations: {},
  translationHashes: {} }
const NOTHING: ChecklistFacts = { sponsor: null, page: null, listedProducts: 0, staff: 0, mailServerVerified: false }

describe("the hub's checklist (Q79)", () => {
  it('starts with nothing done, the three extras optional', () => {
    const items = checklist(NOTHING)
    expect(items.map((i) => [i.key, i.done, i.optional])).toEqual([
      ['basics', false, false], ['page', false, false], ['products', false, true], ['staff', false, true],
      ['mailServer', false, true]])
    expect(progress(items)).toEqual({ done: 0, total: 5 })
  })

  it('counts the basics once applied with a company, the page once published, the rest once there is one', () => {
    expect(checklist({ ...NOTHING, sponsor: { ...APPROVED, company: ' ' } })[0].done).toBe(false)
    expect(checklist({ ...NOTHING, sponsor: { ...APPROVED, reviewStatus: 'PENDING' } })[0].done).toBe(true)
    expect(checklist({ ...NOTHING, page: { ...PAGE, status: 'PENDING' } })[1].done).toBe(false)
    const all = checklist({ sponsor: APPROVED, page: PAGE, listedProducts: 1, staff: 2, mailServerVerified: true })
    expect(all.every((i) => i.done)).toBe(true)
    expect(progress(all)).toEqual({ done: 5, total: 5 })
  })
})
