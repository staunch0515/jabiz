import type { UseFormReturn } from '@jabiz/ui'
import { LockKeyhole } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { SignInField } from '../../components/SignInFrame'

export type NewPassword = { password: string; confirm: string }

/** A new password, twice; the server's rules (length) answer with their own message. */
export function NewPasswordFields({ form, serverError }: { form: UseFormReturn<NewPassword>; serverError?: string | null }) {
  const { t } = useTranslation()
  const errors = form.formState.errors
  return (
    <>
      <SignInField
        icon={LockKeyhole}
        type="password"
        placeholder={t('auth.newPassword')}
        autoComplete="new-password"
        error={errors.password?.message ?? serverError ?? undefined}
        {...form.register('password', { validate: (v) => v !== '' || t('auth.passwordRequired') })}
      />
      <SignInField
        icon={LockKeyhole}
        type="password"
        placeholder={t('auth.confirmPassword')}
        autoComplete="new-password"
        error={errors.confirm?.message}
        {...form.register('confirm', {
          validate: (v, values) => v === values.password || t('auth.passwordMismatch'),
        })}
      />
    </>
  )
}
