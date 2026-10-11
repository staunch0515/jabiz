import { Alert, AlertDescription, Button, useForm } from '@jabiz/ui'
import { Info } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, unwrap } from '../../api/client'
import { SignInFrame, SubmitButton } from '../../components/SignInFrame'
import { failure, invalidLink, passwordProblems, passwordRequired, useLink } from './link'
import { ContinueTo, Outcome } from './Outcome'
import { NewPasswordFields, type NewPassword } from './NewPasswordFields'

type State = 'ready' | 'password' | 'done' | 'invalid'

/**
 * Where the link of the "you already have an account" mail leads (docs/design/10-security.md section 15.7; decision
 * D36 item 2 and implementation note 6): continuing adds the entry's roles to the account. Nothing happens before the
 * person clicks, so that a mail scanner opening the link changes nothing. An account somebody may have registered
 * with this address before must get a new password at the same time (the server answers 422 {@code REQUIRED} for
 * {@code password}). Only problems of the password go under its field; any other goes to the alert above the form.
 */
export default function JoinPage() {
  const { t } = useTranslation()
  const { token, appPath } = useLink()
  const [state, setState] = useState<State>(token ? 'ready' : 'invalid')
  const [error, setError] = useState<string | null>(null)
  const [fieldError, setFieldError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const form = useForm<NewPassword>({ defaultValues: { password: '', confirm: '' } })

  const join = async (password?: string) => {
    setError(null)
    setFieldError(null)
    try {
      await unwrap(api.POST('/api/auth/join', { body: password === undefined ? { token: token! } : { token: token!, password } }))
      setState('done')
    } catch (e) {
      if (invalidLink(e)) {
        setState('invalid')
      } else if (password === undefined && passwordRequired(e)) {
        setState('password')
      } else if (passwordProblems(e).length > 0) {
        setFieldError(passwordProblems(e).join(' '))
      } else {
        setError(failure(e, t('auth.failed')))
      }
    }
  }

  return (
    <SignInFrame subtitle={t('auth.joinTitle')} testId="join">
      {error && (
        <Outcome ok={false} testId="join-error">
          {error}
        </Outcome>
      )}
      {state === 'ready' && (
        <>
          <p className="text-sm">{t('auth.joinHint')}</p>
          <Button
            size="lg"
            className="w-full"
            disabled={busy}
            data-testid="join-continue"
            onClick={async () => {
              setBusy(true)
              await join()
              setBusy(false)
            }}
          >
            {t('auth.continue')}
          </Button>
        </>
      )}
      {state === 'password' && (
        <form noValidate className="flex flex-col gap-4" onSubmit={form.handleSubmit(({ password }) => join(password))}>
          <Alert role="note">
            <Info aria-hidden />
            <AlertDescription data-testid="join-password-hint">{t('auth.joinPasswordHint')}</AlertDescription>
          </Alert>
          <NewPasswordFields form={form} serverError={fieldError} />
          <SubmitButton busy={form.formState.isSubmitting}>{t('auth.continue')}</SubmitButton>
        </form>
      )}
      {state === 'done' && (
        <Outcome ok testId="join-state" next={<ContinueTo path={appPath} label={t('auth.signIn')} />}>
          {t('auth.joined')}
        </Outcome>
      )}
      {state === 'invalid' && (
        <Outcome ok={false} testId="join-state">
          {t('auth.invalidLink')}
        </Outcome>
      )}
    </SignInFrame>
  )
}
