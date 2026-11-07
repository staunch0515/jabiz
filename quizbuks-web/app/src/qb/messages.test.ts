import { i18n } from '@jabiz/client'
import { AUTH_NAMESPACE } from '@jabiz/ui'
import { describe, expect, it } from 'vitest'
import { BRAND } from './brand'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from './messageKeys'
import { QB } from './messages'

describe('the shared QuizBuks texts', () => {
  it('have the same keys in every language, none of them empty', () => {
    const english = keyPaths(bundle(QB, 'en'))
    expect(english.length).toBeGreaterThan(10)
    for (const lng of LANGUAGES) {
      expect(keyPaths(bundle(QB, lng)), lng).toEqual(english)
      expect(emptyTexts(QB, lng), lng).toEqual([])
    }
  })

  it('name two-step verification as the platform blocks do, so the account page reads as one', () => {
    for (const lng of LANGUAGES) {
      const heading = i18n.t('account.mfa', { ns: QB, lng })
      expect(i18n.t('mfa.on', { ns: AUTH_NAMESPACE, lng }), lng).toContain(heading)
      expect(i18n.t('mfa.off', { ns: AUTH_NAMESPACE, lng }), lng).toContain(heading)
    }
  })

  it('name the entries of the sign-in history by the names the requester decided, each in its own language', () => {
    const entry = (name: string, lng: string) => i18n.t(`signIns.entries.${name}`, { ns: QB, lng })
    expect(entry('sponsor', 'zh')).toBe(`${BRAND.names.zh}发起方`)
    expect(entry('sponsor', 'ja')).toBe(`${BRAND.names.ja} 主催者`)
    expect(entry('sponsor', 'en')).toBe(`${BRAND.names.en} for Sponsors`)
    // The administration in Chinese by the Chinese name, as in Japanese by the Japanese one.
    expect(entry('admin', 'zh')).toBe(`${BRAND.names.zh} 平台管理系统`)
    expect(entry('admin', 'ja')).toBe(`${BRAND.names.ja} 管理システム`)
    expect(entry('admin', 'en')).toBe(`${BRAND.names.en} Management System`)
    expect(entry('operator', 'ja')).toBe('運営会社管理システム')
    for (const lng of LANGUAGES) {
      for (const name of ['app', 'sponsor', 'admin', 'operator']) expect(entry(name, lng)).not.toMatch(/后台|管理画面|console/i)
    }
  })

  it('finds the keys of nested texts', () => {
    expect(keyPaths({ a: 'x', b: { d: 'y', c: 'z' } })).toEqual(['a', 'b.c', 'b.d'])
  })
})
