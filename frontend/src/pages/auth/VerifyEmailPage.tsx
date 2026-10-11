import { Alert, AlertDescription, Button, useForm } from '@jabiz/ui'
import { Info, LockKeyhole, Mail } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, unwrap } from '../../api/client'
import { SignInField, SignInFrame, SubmitButton } from '../../components/SignInFrame'
import { failure, invalidLink, passwordProblems, useLink } from './link'
import { ContinueTo, Outcome } from './Outcome'
import { NewPasswordFields, type NewPassword } from './NewPasswordFields'

type State = 'ready' | 'password' | 'done' | 'invalid' | 'failed'

/**
 * Where the link of a verification mail leads (docs/design/10-security.md section 15.7; decision D36 item 3). Nothing
 * is sent before the person clicks, so that a mail scanner opening the link changes nothing. The click first asks the
 * server what the link needs (without using it): an account that registered itself and was never verified needs its
 * password, or a new one from whoever owns the mailbox but did not register (which takes the account over). A link
 * that is no good (expired, used, superseded) offers to send a new one; the answer is the same whatever the address.
 * After a failure that is not the link's, the person may try again.
 */
export default function VerifyEmailPage() {
  const { t } = useTranslation()
  const { entry, token, appPath } = useLink()
  const [state, setState] = useState<State>(token ? 'ready' : 'invalid')
  const [busy, setBusy] = useState(false)

  const failed = (e: unknown) => setState(invalidLink(e) ? 'invalid' : 'failed')

  const start = async () => {
    setBusy(true)
    try {
      const check = await unwrap(api.POST('/api/auth/verify-email/check', { body: { token: token! } }))
      if (check.passwordRequired) {
        setState('password')
      } else {
        await unwrap(api.POST('/api/auth/verify-email', { body: { token: token! } }))
        setState('done')
      }
    } catch (e) {
      failed(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <SignInFrame subtitle={t('auth.verifyTitle')} testId="verify-email">
      {state === 'ready' && (
        <>
          <p className="text-sm" data-testid="verify-state" data-state={state}>
            {t('auth.verifyHint')}
          </p>
          <Button size="lg" className="w-full" disabled={busy} aria-busy={busy || undefined} data-testid="verify-start"
            onClick={() => void start()}>
            {t('auth.verify')}
          </Button>
        </>
      )}
      {state === 'password' && (
        <PasswordStep token={token!} onDone={() => setState('done')} onFailed={failed} />
      )}
      {state === 'done' && (
        <Outcome ok testId="verify-state" next={<ContinueTo path={appPath} />}>
          <span data-state={state}>{t('auth.verified')}</span>
        </Outcome>
      )}
      {state === 'failed' && (
        <Outcome
          ok={false}
          testId="verify-state"
          next={
            <Button variant="outline" className="w-full" data-testid="verify-retry" onClick={() => setState('ready')}>
              {t('auth.retry')}
            </Button>
          }
        >
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

/** The password of an account nobody verified yet: the registrant's current one, or a new one that takes it over. */
function PasswordStep({ token, onDone, onFailed }: { token: string; onDone(): void; onFailed(e: unknown): void }) {
  const { t } = useTranslation()
  const [mode, setMode] = useState<'current' | 'new'>('current')
  const [fieldError, setFieldError] = useState<string | null>(null)
  const current = useForm<{ password: string }>({ defaultValues: { password: '' } })
  const fresh = useForm<NewPassword>({ defaultValues: { password: '', confirm: '' } })

  const send = async (body: { token: string; password?: string; newPassword?: string }) => {
    setFieldError(null)
    try {
      await unwrap(api.POST('/api/auth/verify-email', { body }))
      onDone()
    } catch (e) {
      const problems = passwordProblems(e)
      if (problems.length > 0) setFieldError(problems.join(' '))
      else onFailed(e)
    }
  }

  return (
    <>
      <Alert role="note">
        <Info aria-hidden />
        <AlertDescription data-testid="verify-password-hint">{t('auth.verifyPasswordHint')}</AlertDescription>
      </Alert>
      {mode === 'current' ? (
        <form noValidate className="flex flex-col gap-4"
          onSubmit={current.handleSubmit(({ password }) => send({ token, password }))}>
          <SignInField
            icon={LockKeyhole}
            type="password"
            placeholder={t('login.password')}
            autoComplete="current-password"
            error={current.formState.errors.password?.message ?? fieldError ?? undefined}
            {...current.register('password', { validate: (v) => v !== '' || t('login.passwordRequired') })}
          />
          <SubmitButton busy={current.formState.isSubmitting}>{t('auth.verify')}</SubmitButton>
        </form>
      ) : (
        <form noValidate className="flex flex-col gap-4"
          onSubmit={fresh.handleSubmit(({ password }) => send({ token, newPassword: password }))}>
          <NewPasswordFields form={fresh} serverError={fieldError} />
          <SubmitButton busy={fresh.formState.isSubmitting}>{t('auth.verify')}</SubmitButton>
        </form>
      )}
      <Button variant="link" data-testid="verify-mode" onClick={() => {
        setFieldError(null)
        setMode(mode === 'current' ? 'new' : 'current')
      }}>
        {mode === 'current' ? t('auth.useNewPassword') : t('auth.useCurrentPassword')}
      </Button>
    </>
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
