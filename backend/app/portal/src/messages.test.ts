import { i18n } from '@jabiz/client'
import { AUTH_NAMESPACE } from '@jabiz/ui'
import { describe, expect, it } from 'vitest'
import { PORTAL } from './messages'

describe('the portal texts', () => {
  it('name two-step verification as the platform blocks do, so the account page reads as one', () => {
    for (const lng of ['en', 'zh', 'ja']) {
      const heading = i18n.t('account.mfa', { ns: PORTAL, lng })
      expect(i18n.t('mfa.on', { ns: AUTH_NAMESPACE, lng }), lng).toContain(heading)
      expect(i18n.t('mfa.off', { ns: AUTH_NAMESPACE, lng }), lng).toContain(heading)
    }
  })
})
