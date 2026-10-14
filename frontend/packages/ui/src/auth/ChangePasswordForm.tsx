import { ApiError, changePassword } from '@jabiz/client'
import { LockKeyhole } from 'lucide-react'
import { useState } from 'react'
import { useForm, type UseFormReturn } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { cn } from '../lib/utils'
import { AuthError, AuthField, AuthSubmit, Outcome } from './frame'
import { NewPasswordFields, type NewPassword } from './MailLinkPage'
import { AUTH_NAMESPACE } from './messages'
import { failure } from './problems'

type Values = NewPassword & { current: string }

export interface ChangePasswordFormProps {
  /** Changes it; `changePassword` of `@jabiz/client` by default. */
  change?: (currentPassword: string, newPassword: string) => Promise<void>
  /** Called once changed. */
  onChanged?: () => void
  className?: string
}

/**
 * The signed-in user changes their own password (`POST /api/auth/account/password`, phase 16m; decision D43 item 3):
 * the current one, the new one twice. A wrong current password counts towards the lock-out; the other sessions of
 * the account end, this one is kept. Goes on a page of the caller's (a security or account page).
 */
export function ChangePasswordForm({ change = changePassword, onChanged, className }: ChangePasswordFormProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const [done, setDone] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [currentError, setCurrentError] = useState<string | null>(null)
  const [newError, setNewError] = useState<string | null>(null)
  const form = useForm<Values>({ defaultValues: { current: '', password: '', confirm: '' } })
  return (
    <div className={cn('flex flex-col gap-4', className)} data-testid="change-password">
      {done && (
        <Outcome ok testId="change-password-state">
          {t('password.changed')}
        </Outcome>
      )}
      {error && <AuthError message={error} testId="change-password-error" />}
      <form
        noValidate
        className="flex flex-col gap-4"
        onSubmit={form.handleSubmit(async ({ current, password }) => {
          setDone(false)
          setError(null)
          setCurrentError(null)
          setNewError(null)
          try {
            await change(current, password)
            form.reset()
            setDone(true)
            onChanged?.()
          } catch (e) {
            // A wrong current password is PASSWORD_INCORRECT whatever field the server names; the new one's rules
            // (length) name the new password.
            const violations = e instanceof ApiError ? e.violations : []
            const wrong = violations
              .filter((v) => v.ruleCode === 'PASSWORD_INCORRECT' || v.field === 'currentPassword')
              .map((v) => v.message)
            const weak = violations
              .filter((v) => v.ruleCode !== 'PASSWORD_INCORRECT' && (v.field === 'newPassword' || v.field === 'password'))
              .map((v) => v.message)
            if (wrong.length > 0) setCurrentError(wrong.join(' '))
            if (weak.length > 0) setNewError(weak.join(' '))
            if (wrong.length === 0 && weak.length === 0) setError(failure(e, t('account.failed')))
          }
        })}
      >
        <AuthField
          icon={LockKeyhole}
          type="password"
          placeholder={t('password.current')}
          autoComplete="current-password"
          error={form.formState.errors.current?.message ?? currentError ?? undefined}
          {...form.register('current', { validate: (v) => v !== '' || t('password.currentRequired') })}
        />
        <NewPasswordFields form={form as unknown as UseFormReturn<NewPassword>} serverError={newError} />
        <AuthSubmit busy={form.formState.isSubmitting}>{t('password.submit')}</AuthSubmit>
      </form>
    </div>
  )
}
