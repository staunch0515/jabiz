import { useAuth, type IdentityLinked } from '@jabiz/client'
import {
  AccountDeletion,
  AUTH_NAMESPACE,
  Badge,
  ChangePasswordForm,
  DescriptionList,
  DetailPage,
  LinkedIdentities,
  MailPreferences,
  Passkeys,
  MfaSettings,
  SessionList,
} from '@jabiz/ui'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AREAS } from '../areas'
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
 * sessions at the portal (section 15.9), their mail: the portal's notifications, such as the shipment notice, and
 * the language (phase 16s, decision D49), and asking for the account to be deleted (section 17, decision D62). On
 * the platform's detail template (phase 17zj): who is signed in and whether the address is verified stand beside the
 * settings (before them on a phone).
 */
export function AccountPage() {
  const { t } = useTranslation(PORTAL)
  const { t: tAuth } = useTranslation(AUTH_NAMESPACE)
  const { displayName, email, emailVerified } = useAuth()
  const [linked] = useState(takeLinked)
  const summary = (
    <DescriptionList layout="horizontal" items={[
      { key: 'name', label: t('account.summary'), value: displayName },
      { key: 'email', label: t('account.email'), value: (
        <span className="flex flex-wrap items-center gap-2">
          {email ?? '—'}
          <Badge variant={emailVerified ? 'secondary' : 'outline'} data-testid="account-email-state">
            {emailVerified ? t('account.verified') : t('account.unverified')}
          </Badge>
        </span>
      ) },
    ]} />
  )
  return (
    <DetailPage title={t('account.title')} icon={AREAS.account.icon} aside={summary}
      asideLabel={t('account.summary')}>
      <div className="flex flex-col gap-4">
        <h2 className="text-lg font-medium">{tAuth('password.changeTitle')}</h2>
        <ChangePasswordForm />
      </div>
      <div className="flex flex-col gap-4">
        <h2 className="text-lg font-medium">{tAuth('passkeys.title')}</h2>
        <Passkeys />
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
      <div className="flex flex-col gap-4">
        <h2 className="text-lg font-medium">{t('account.deletion')}</h2>
        <AccountDeletion />
      </div>
    </DetailPage>
  )
}
