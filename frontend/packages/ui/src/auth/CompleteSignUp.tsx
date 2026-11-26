import { User } from 'lucide-react'
import type { ReactNode } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { AuthError, AuthField, AuthSubmit } from './frame'
import { AUTH_NAMESPACE } from './messages'

/** Longest display name the server takes. */
const MAX_DISPLAY_NAME = 200

export interface CompleteSignUpProps {
  /** Opens the account with the name and signs in; a rejection is shown through `error`. */
  onSubmit(displayName: string): Promise<void>
  /** The application's terms, under the button ("By creating an account you agree to …"). */
  note?: ReactNode
  /** The application's own fields above the button. */
  extra?: ReactNode
  /** What went wrong, from the caller. */
  error?: string | null
}

/**
 * The last step of signing up by a mailed link at an entry with one "continue" (docs/design/10-security.md section
 * 15.19; decision D84): the address is proven by the link; the account exists once its owner gives the name others see
 * and has seen the application's terms.
 */
export function CompleteSignUp({ onSubmit, note, extra, error }: CompleteSignUpProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const form = useForm<{ displayName: string }>({ defaultValues: { displayName: '' } })
  return (
    <form
      noValidate
      className="flex flex-col gap-4"
      data-testid="complete-sign-up"
      onSubmit={form.handleSubmit(({ displayName }) => onSubmit(displayName.trim()))}
    >
      <p className="text-muted-foreground text-sm">{t('continue.finishHint')}</p>
      <AuthField
        icon={User}
        placeholder={t('continue.displayName')}
        autoComplete="name"
        maxLength={MAX_DISPLAY_NAME}
        data-testid="complete-sign-up-name"
        error={form.formState.errors.displayName?.message}
        {...form.register('displayName', { validate: (v) => v.trim() !== '' || t('continue.displayNameRequired') })}
      />
      {extra}
      {error && <AuthError message={error} testId="complete-sign-up-error" />}
      <AuthSubmit busy={form.formState.isSubmitting}>{t('continue.finish')}</AuthSubmit>
      {note && <div className="text-muted-foreground text-xs" data-testid="complete-sign-up-note">{note}</div>}
    </form>
  )
}
