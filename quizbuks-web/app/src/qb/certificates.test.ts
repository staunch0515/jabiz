import { i18n } from '@jabiz/client'
import { describe, expect, it } from 'vitest'
import { canDownload, certificateLanguage, certificateTitle, isRevoked, SOURCE_CERTIFICATION, SOURCE_NODE } from './certificates'
import { QB } from './messages'

const node = { source: SOURCE_NODE, certification: null, names: { en: 'Coffee', ja: 'コーヒー' }, revokedAt: null,
  runId: null }

describe('the caller\'s certificates (F-S5b)', () => {
  it('names a node\'s certificate in the reader\'s language, else English, and a Q77 one by its certification', () => {
    const t = i18n.getFixedT('en', QB)
    expect(certificateTitle(node, 'ja-JP', t)).toBe('コーヒー')
    expect(certificateTitle(node, 'zh', t)).toBe('Coffee')
    expect(certificateTitle({ source: SOURCE_CERTIFICATION, certification: 'SPONSOR_APPLY', names: null }, 'en', t))
      .toContain('Sponsor Certification')
    expect(certificateTitle({ source: SOURCE_CERTIFICATION, certification: 'sp:1', names: { en: 'Keyboard' } }, 'en', t))
      .toBe('Keyboard')
  })

  it('issues in the interface language, English for any other', () => {
    expect(certificateLanguage('ja')).toBe('ja')
    expect(certificateLanguage('zh-TW')).toBe('zh')
    expect(certificateLanguage('de')).toBe('en')
    expect(certificateLanguage(undefined)).toBe('en')
  })

  it('offers no first PDF of a revoked node\'s certificate, but the one issued before, and Q77\'s as ever', () => {
    const revoked = { ...node, revokedAt: '2026-11-01T00:00:00Z' }
    expect(isRevoked(node)).toBe(false)
    expect(isRevoked(revoked)).toBe(true)
    expect(canDownload(node)).toBe(true)
    expect(canDownload(revoked)).toBe(false)
    expect(canDownload({ ...revoked, runId: 'r1' })).toBe(true)
    expect(canDownload({ ...revoked, source: SOURCE_CERTIFICATION })).toBe(true)
  })
})
