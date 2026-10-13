import { AUTH_NAMESPACE, ChangePasswordForm, MfaSettings, SessionList } from '@jabiz/ui'
import { useTranslation } from 'react-i18next'
import { PORTAL } from '../messages'

/**
 * The customer's own account: their password (`POST /api/auth/account/password`, decision D43 item 3), two-step
 * verification (docs/design/10-security.md section 9; the platform's `MfaSettings`, as on the admin's security page),
 * which step-up asks for, and their sessions at the portal (section 15.9).
 */
export function AccountPage() {
  const { t } = useTranslation(PORTAL)
  const { t: tAuth } = useTranslation(AUTH_NAMESPACE)
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
        <h2 className="text-lg font-medium">{tAuth('sessions.title')}</h2>
        <SessionList />
      </div>
    </section>
  )
}
