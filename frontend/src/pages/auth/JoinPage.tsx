import { Alert, AlertDescription, Button, useForm } from '@jabiz/ui'
import { Info } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, unwrap } from '../../api/client'
import { ApiError } from '../../api/problem'
import { SignInFrame, SubmitButton } from '../../components/SignInFrame'
import { failure, invalidLink, useLink } from './link'
import { ContinueTo, Outcome } from './Outcome'
import { NewPasswordFields, type NewPassword } from './NewPasswordFields'

type State = 'ready' | 'password' | 'done' | 'invalid'

/**
 * Where the link of the "you already have an account" mail leads (docs/design/10-security.md section 15; decision D36
 * item 2 and implementation note 6): continuing adds the entry's roles to the account. Nothing happens before the
 * person clicks, so that a mail scanner opening the link changes nothing. An account whose address was never verified
 * must get a new password at the same time (the server answers 422 {@code REQUIRED} for {@code password}): whoever
 * registered the address before may know the old one.
 */
export default function JoinPage() {
  const { t } = useTranslation()
  const { token, appPath } = useLink()
  const [state, setState] = useState<State>(token ? 'ready' : 'invalid')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const form = useForm<NewPassword>({ defaultValues: { password: '', confirm: '' } })

  const join = async (password?: string) => {
    setError(null)
    try {
      await unwrap(api.POST('/api/auth/join', { body: password === undefined ? { token: token! } : { token: token!, password } }))
      setState('done')
    } catch (e) {
      if (invalidLink(e)) {
        setState('invalid')
      } else if (e instanceof ApiError && e.status === 422 && e.forField('password').some((v) => v.ruleCode === 'REQUIRED')) {
        setState('password')
      } else {
        setError(failure(e, t('auth.failed')))
      }
    }
  }

  return (
    <SignInFrame subtitle={t('auth.joinTitle')} testId="join">
      {state === 'ready' && (
        <>
          <p className="text-sm">{t('auth.joinHint')}</p>
          {error && (
            <Outcome ok={false} testId="join-error">
              {error}
            </Outcome>
          )}
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
          <NewPasswordFields form={form} serverError={error} />
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
