import type { IdentityLinked } from '@jabiz/client'
import { AUTH_NAMESPACE, ChangePasswordForm, LinkedIdentities, MailPreferences, MfaSettings, SessionList } from '@jabiz/ui'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { PORTAL } from '../messages'

/** The account page, where a round trip through an identity provider to link an account comes back to. */
export const ACCOUNT_PATH = '/account'

let justLinked: IdentityLinked | null = null

/** Remembers what the identity provider's return linked, for the account page to say once. */
export function rememberLinked(linked: IdentityLinked) {
  justLinked = linked
}

function takeLinked(): IdentityLinked | null {
  const linked = justLinked
  justLinked = null
  return linked
}

/**
 * The customer's own account: their password (`POST /api/auth/account/password`, decision D43 item 3), two-step
 * verification (docs/design/10-security.md section 9; the platform's `MfaSettings`, as on the admin's security page),
 * which step-up asks for, the accounts of identity providers they sign in with (section 12, phase 16f), their
 * sessions at the portal (section 15.9) and their mail: the portal's notifications, such as the shipment notice, and
 * the language (phase 16s, decision D49).
 */
export function AccountPage() {
  const { t } = useTranslation(PORTAL)
  const { t: tAuth } = useTranslation(AUTH_NAMESPACE)
  const [linked] = useState(takeLinked)
  return (
    <section className="flex max-w-md flex-col gap-8" aria-labelledby="account-title">
      <h1 id="account-title" className="text-xl font-semibold">
        {t('account.title')}
      </h1>
      <div className="flex flex-col gap-4">
        <h2 className="text-lg font-medium">{tAuth('password.changeTitle')}</h2>
        <ChangePasswordForm />
      </div>
      <div className="flex flex-col gap-4">
        <h2 className="text-lg font-medium">{t('account.mfa')}</h2>
        <MfaSettings />
      </div>
      <div className="flex flex-col gap-4">
        <h2 className="text-lg font-medium">{tAuth('identities.title')}</h2>
        <LinkedIdentities returnTo={ACCOUNT_PATH} linked={linked} />
      </div>
      <div className="flex flex-col gap-4">
        <h2 className="text-lg font-medium">{tAuth('sessions.title')}</h2>
        <SessionList />
      </div>
      <div className="flex flex-col gap-4">
        <h2 className="text-lg font-medium">{tAuth('mailPreferences.title')}</h2>
        <MailPreferences />
      </div>
    </section>
  )
}
