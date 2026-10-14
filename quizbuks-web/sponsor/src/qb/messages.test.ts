import { i18n } from '@jabiz/client'
import { AUTH_NAMESPACE } from '@jabiz/ui'
import { describe, expect, it } from 'vitest'
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

  it('finds the keys of nested texts', () => {
    expect(keyPaths({ a: 'x', b: { d: 'y', c: 'z' } })).toEqual(['a', 'b.c', 'b.d'])
  })
})
