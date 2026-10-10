import { useForm } from '@jabiz/ui'
import { Mail } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { api, unwrap } from '../../api/client'
import { SignInField, SignInFrame, SubmitButton } from '../../components/SignInFrame'
import { failure, invalidLink, useLink } from './link'
import { ContinueTo, Outcome } from './Outcome'
import { NewPasswordFields, type NewPassword } from './NewPasswordFields'

/**
 * Resetting a forgotten password (docs/design/10-security.md section 15; decision D36 item 5). Without a token: asks
 * for the address and says the same whatever it is. With the token of the mailed link: sets the new password, which
 * ends the account's sessions; the person then signs in (no session is opened here).
 */
export default function PasswordResetPage() {
  const { token } = useLink()
  return token ? <Confirm token={token} /> : <Request />
}

function Request() {
  const { t } = useTranslation()
  const { entry } = useLink()
  const [done, setDone] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const form = useForm<{ email: string }>({ defaultValues: { email: '' } })
  return (
    <SignInFrame subtitle={t('auth.resetTitle')} testId="password-reset">
      {done ? (
        <Outcome ok testId="reset-state">
          {t('auth.resetRequested')}
        </Outcome>
      ) : (
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={form.handleSubmit(async ({ email }) => {
            setError(null)
            try {
              await unwrap(api.POST('/api/auth/password-reset/request', { body: { email: email.trim(), entry } }))
              setDone(true)
            } catch (e) {
              setError(failure(e, t('auth.failed')))
            }
          })}
        >
          <p className="text-muted-foreground text-sm">{t('auth.resetHint')}</p>
          <SignInField
            icon={Mail}
            type="email"
            placeholder={t('auth.email')}
            autoComplete="email"
            error={form.formState.errors.email?.message ?? error ?? undefined}
            {...form.register('email', { validate: (v) => v.trim() !== '' || t('auth.emailRequired') })}
          />
          <SubmitButton busy={form.formState.isSubmitting}>{t('auth.sendLink')}</SubmitButton>
        </form>
      )}
      <Link to="/login" className="text-primary self-center text-sm underline-offset-4 hover:underline">
        {t('login.back')}
      </Link>
    </SignInFrame>
  )
}

function Confirm({ token }: { token: string }) {
  const { t } = useTranslation()
  const { entry, appPath } = useLink()
  const [state, setState] = useState<'form' | 'done' | 'invalid'>('form')
  const [error, setError] = useState<string | null>(null)
  const form = useForm<NewPassword>({ defaultValues: { password: '', confirm: '' } })
  return (
    <SignInFrame subtitle={t('auth.resetTitle')} testId="password-reset">
      {state === 'form' && (
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={form.handleSubmit(async ({ password }) => {
            setError(null)
            try {
              await unwrap(api.POST('/api/auth/password-reset/confirm', { body: { token, password } }))
              setState('done')
            } catch (e) {
              if (invalidLink(e)) setState('invalid')
              else setError(failure(e, t('auth.failed')))
            }
          })}
        >
          <NewPasswordFields form={form} serverError={error} />
          <SubmitButton busy={form.formState.isSubmitting}>{t('auth.setPassword')}</SubmitButton>
        </form>
      )}
      {state === 'done' && (
        <Outcome ok testId="reset-state" next={<ContinueTo path={appPath} label={t('auth.signIn')} />}>
          {t('auth.resetDone')}
        </Outcome>
      )}
      {state === 'invalid' && (
        <Outcome
          ok={false}
          testId="reset-state"
          next={
            <Link to={`/auth/password-reset?entry=${entry}`} className="text-primary self-center text-sm underline-offset-4 hover:underline">
              {t('auth.sendLink')}
            </Link>
          }
        >
          {t('auth.invalidLink')}
        </Outcome>
      )}
    </SignInFrame>
  )
}
