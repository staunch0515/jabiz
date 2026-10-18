import { describe, expect, it } from 'vitest'
import { legalKindOf, legalPath, minuteNow, pickText } from './legal'
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
