import { api, unwrap } from '@jabiz/client'
import { AUTH_NAMESPACE, ChangePasswordForm, MfaEnrollment, PageState } from '@jabiz/ui'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { PORTAL } from '../messages'

/**
 * The customer's own account: their password (`POST /api/auth/account/password`, decision D43 item 3) and two-step
 * verification (docs/design/10-security.md section 9), which step-up asks for.
 */
export function AccountPage() {
  const { t } = useTranslation(PORTAL)
  const { t: tAuth } = useTranslation(AUTH_NAMESPACE)
  const queryClient = useQueryClient()
  const mfa = useQuery({ queryKey: ['auth', 'mfa'], queryFn: () => unwrap(api.GET('/api/auth/mfa')) })
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
        {mfa.isPending && <PageState kind="loading" />}
        {mfa.data?.enrolled && <p data-testid="mfa-status">{t('account.mfaOn')}</p>}
        {mfa.data && !mfa.data.enrolled && (
          <>
            <p data-testid="mfa-status">{t('account.mfaOff')}</p>
            <MfaEnrollment
              begin={async () => {
                const answer = await unwrap(api.POST('/api/auth/mfa/enroll'))
                return { secret: answer.secret!, otpauthUri: answer.otpauthUri! }
              }}
              confirm={async (code) =>
                (await unwrap(api.POST('/api/auth/mfa/enroll/confirm', { body: { code } }))).recoveryCodes ?? []
              }
              onDone={() => void queryClient.invalidateQueries({ queryKey: ['auth', 'mfa'] })}
            />
          </>
        )}
      </div>
    </section>
  )
}
