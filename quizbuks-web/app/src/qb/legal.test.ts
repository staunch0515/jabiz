import { configureClient } from '@jabiz/client'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { learnServerClock, legalKindOf, legalPath, minuteNow, pickText, readMyConsents, resetServerClock,
  serverNow } from './legal'
import { acceptedVersions } from './TermsAgreement'

describe('the legal texts', () => {
  it('are shown in the reader\'s language, else English, else Japanese, marking another language', () => {
    expect(pickText({ en: 'Terms', ja: '規約', zh: '条款' }, 'zh')).toEqual({ text: '条款' })
    expect(pickText({ en: 'Terms', ja: '規約' }, 'zh-CN')).toEqual({ text: 'Terms', lang: 'en' })
    expect(pickText({ ja: '規約' }, 'en')).toEqual({ text: '規約', lang: 'ja' })
    expect(pickText({ en: ' ', ja: '規約' }, 'en')).toEqual({ text: '規約', lang: 'ja' })
    expect(pickText(null, 'en')).toEqual({ text: '' })
  })

  it('are read at the minute, so that the public answers can be cached', () => {
    expect(minuteNow(Date.parse('2026-10-18T09:41:59.999Z'))).toBe('2026-10-18T09:41:00.000Z')
  })

  it('have pages of their own, and nothing else is one', () => {
    expect(legalKindOf('/legal/terms')).toBe('TERMS_APP')
    expect(legalKindOf('/legal/sponsor-terms')).toBe('TERMS_SPONSOR')
    expect(legalKindOf('/legal/privacy')).toBe('PRIVACY')
    expect(legalKindOf('/legal/commerce')).toBe('COMMERCE_NOTICE')
    expect(legalKindOf('/legal/ai')).toBe('AI_NOTICE')
    expect(legalKindOf('/legal/contest-rules')).toBe('CONTEST_RULES')
    expect(legalPath('CONTEST_RULES')).toBe('/legal/contest-rules')
    expect(legalKindOf('/legal/drafts')).toBeNull()
    expect(legalKindOf('/legal/terms/1')).toBeNull()
    expect(legalPath('PRIVACY')).toBe('/legal/privacy')
  })

  it('are accepted at the versions in effect the page showed', () => {
    const row = (kind: 'TERMS_APP' | 'TERMS_SPONSOR' | 'PRIVACY', versionNo: number) => ({ kind, versionNo,
      versionId: 'v', title: {}, changeSummary: null, requiresConsent: true, effectiveAt: '', toAccept: versionNo,
      accepted: null, mustAccept: true })
    expect(acceptedVersions([row('TERMS_SPONSOR', 4), row('PRIVACY', 2)]))
      .toEqual({ termsVersionNo: 4, privacyVersionNo: 2 })
    expect(acceptedVersions([row('TERMS_APP', 1)])).toEqual({ termsVersionNo: 1, privacyVersionNo: null })
    expect(acceptedVersions(undefined)).toEqual({ termsVersionNo: null, privacyVersionNo: null })
  })
})

/**
 * Review M2: whether a version is in effect, and must be accepted, is decided at the server's time. The device's
 * clock may be off either way: the answers' `Date` header tells by how much, and a standing read at the device's
 * time is read again at the server's.
 */
describe('the caller\'s standing', () => {
  beforeAll(() => configureClient({ entry: 'app' }))
  afterEach(() => {
    vi.restoreAllMocks()
    resetServerClock()
  })

  /** The server, whose clock is `offset` ms off the device's; records the times the page asked at. */
  function server(offset: number) {
    const asked: string[] = []
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin),
        init)
      const body = (await request.clone().json()) as { params: { at: string } }
      asked.push(body.params.at)
      return new Response(JSON.stringify({ items: [], offset: 0, limit: 20 }), { status: 200, headers: {
        'Content-Type': 'application/json', Date: new Date(Date.now() + offset).toUTCString() } })
    })
    return asked
  }

  const near = (at: string, expected: number) =>
    expect(Math.abs(Date.parse(at) - expected)).toBeLessThan(3_000)

  it.each([
    ['ahead of the server by an hour', -3_600_000],
    ['behind the server by a day', 86_400_000],
  ])('is read at the server\'s time when the device\'s clock is %s', async (_, offset) => {
    const asked = server(offset)
    await readMyConsents(['TERMS_APP', 'PRIVACY'])
    expect(asked).toHaveLength(2)
    near(asked[0], Date.now())
    near(asked[1], Date.now() + offset)
    // Known from then on: one read, at the server's time; the public pages too.
    await readMyConsents(['TERMS_APP'])
    expect(asked).toHaveLength(3)
    near(asked[2], Date.now() + offset)
    expect(Math.abs(serverNow() - Date.now() - offset)).toBeLessThan(3_000)
    const minute = Date.parse(minuteNow())
    expect(minute).toBeGreaterThan(Date.now() + offset - 63_000)
    expect(minute).toBeLessThanOrEqual(Date.now() + offset + 3_000)
  })

  it('is read once when the clocks agree', async () => {
    const asked = server(0)
    await readMyConsents(['TERMS_APP'])
    expect(asked).toHaveLength(1)
    expect(serverNow(1_000)).toBe(1_000)
  })

  it('ignores what the header cannot tell', () => {
    // Whole seconds and the round trip: a second or two is no offset.
    expect(learnServerClock(new Date(Date.now() + 1_200).toUTCString(), Date.now() - 50, Date.now())).toBe(false)
    expect(learnServerClock(null, 0, 0)).toBe(false)
    expect(learnServerClock('not a date', 0, 0)).toBe(false)
    expect(serverNow(5)).toBe(5)
  })
})
