import { Info, KeyRound } from 'lucide-react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Alert, AlertDescription } from '../components/ui/alert'
import { Button } from '../components/ui/button'
import { AuthError, AuthField, AuthSubmit } from './frame'
import { AUTH_NAMESPACE } from './messages'

export interface MfaCodeStepProps {
  /** Sends the code (trimmed); a refusal is shown by the caller through `error`. */
  onVerify(code: string): Promise<void>
  /** Back to the password. */
  onBack(): void
  error?: string | null
}

/**
 * The second step of a sign-in (docs/design/10-security.md section 9): a code from the authenticator, or a recovery
 * code. Inside a frame of the caller's (SignInForm's).
 */
export function MfaCodeStep({ onVerify, onBack, error }: MfaCodeStepProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const form = useForm<{ code: string }>({ defaultValues: { code: '' } })
  return (
    <>
      <Alert role="note">
        <Info aria-hidden />
        <AlertDescription>{t('login.codeHint')}</AlertDescription>
      </Alert>
      {error && <AuthError message={error} testId="login-error" />}
      <form noValidate className="flex flex-col gap-4" onSubmit={form.handleSubmit(({ code }) => onVerify(code.trim()))}>
        <AuthField
          icon={KeyRound}
          placeholder={t('login.code')}
          autoComplete="one-time-code"
          inputMode="numeric"
          autoFocus
          error={form.formState.errors.code?.message}
          {...form.register('code', { validate: (v) => v.trim() !== '' || t('login.codeRequired') })}
        />
        <AuthSubmit busy={form.formState.isSubmitting}>{t('login.verify')}</AuthSubmit>
      </form>
      <Button variant="link" onClick={onBack}>
        {t('login.back')}
      </Button>
    </>
  )
}
