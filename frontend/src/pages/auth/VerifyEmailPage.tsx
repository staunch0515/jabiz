import { Spinner, useForm } from '@jabiz/ui'
import { Mail } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, unwrap } from '../../api/client'
import { SignInField, SignInFrame, SubmitButton } from '../../components/SignInFrame'
import { failure, invalidLink, useLink } from './link'
import { ContinueTo, Outcome } from './Outcome'

type State = 'working' | 'done' | 'invalid' | 'failed'

/**
 * Where the link of a verification mail leads (docs/design/10-security.md section 15; decision D36 item 3): posts the
 * link's token once and says whether the address is verified, then leads to the entry's pages. A link that is no
 * good (expired, used, superseded) offers to send a new one; the answer is the same whatever the address.
 */
export default function VerifyEmailPage() {
  const { t } = useTranslation()
  const { entry, token, appPath } = useLink()
  const [state, setState] = useState<State>(token ? 'working' : 'invalid')
  const sent = useRef(false)

  useEffect(() => {
    // Once, even when React runs effects twice in development: a second post would find the token used.
    if (!token || sent.current) return
    sent.current = true
    unwrap(api.POST('/api/auth/verify-email', { body: { token } }))
      .then(() => setState('done'))
      .catch((e: unknown) => setState(invalidLink(e) ? 'invalid' : 'failed'))
  }, [token])

  return (
    <SignInFrame subtitle={t('auth.verifyTitle')} testId="verify-email">
      {state === 'working' && (
        <p role="status" className="flex items-center gap-2" data-testid="verify-state" data-state={state}>
          <Spinner />
          {t('auth.verifying')}
        </p>
      )}
      {state === 'done' && (
        <Outcome ok testId="verify-state" next={<ContinueTo path={appPath} />}>
          <span data-state={state}>{t('auth.verified')}</span>
        </Outcome>
      )}
      {state === 'failed' && (
        <Outcome ok={false} testId="verify-state">
          <span data-state={state}>{t('auth.failed')}</span>
        </Outcome>
      )}
      {state === 'invalid' && (
        <>
          <Outcome ok={false} testId="verify-state">
            <span data-state={state}>{t('auth.invalidLink')}</span>
          </Outcome>
          <Resend entry={entry} />
        </>
      )}
    </SignInFrame>
  )
}

/** Asks for a new verification mail for an address; answered alike for every address. */
function Resend({ entry }: { entry: string }) {
  const { t } = useTranslation()
  const [done, setDone] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const form = useForm<{ email: string }>({ defaultValues: { email: '' } })
  if (done) {
    return (
      <Outcome ok testId="resend-state">
        {t('auth.resent')}
      </Outcome>
    )
  }
  return (
    <form
      noValidate
      className="flex flex-col gap-4"
      onSubmit={form.handleSubmit(async ({ email }) => {
        setError(null)
        try {
          await unwrap(api.POST('/api/auth/verify-email/resend', { body: { email: email.trim(), entry } }))
          setDone(true)
        } catch (e) {
          setError(failure(e, t('auth.failed')))
        }
      })}
    >
      <p className="text-muted-foreground text-sm">{t('auth.resendHint')}</p>
      <SignInField
        icon={Mail}
        type="email"
        placeholder={t('auth.email')}
        autoComplete="email"
        error={form.formState.errors.email?.message ?? error ?? undefined}
        {...form.register('email', { validate: (v) => v.trim() !== '' || t('auth.emailRequired') })}
      />
      <SubmitButton busy={form.formState.isSubmitting}>{t('auth.resend')}</SubmitButton>
    </form>
  )
}
