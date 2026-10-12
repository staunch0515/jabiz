import { requestPasswordReset, sitePath, entryInfo } from '@jabiz/client'
import { Mail } from 'lucide-react'
import { useEffect, useState, type ReactNode } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { AUTH_LINK_CLASS, AuthField, AuthFrame, AuthLink, AuthSubmit, Outcome, type AuthNavigation } from './frame'
import { AUTH_NAMESPACE } from './messages'
import { failure } from './problems'

export interface PasswordResetRequestProps extends AuthNavigation {
  /** The application's name, the page's heading. */
  brand: ReactNode
  appearance?: boolean
  /** The entry whose mails and pages the link is for; the frontend's when absent. */
  entry?: string
  /** The sign-in page, as a path of this frontend; the entry's frontend (`app-path`) when absent. */
  signInPath?: string
}

/**
 * Asking for a link to choose a new password (docs/design/10-security.md section 15; decision D36 item 5): the
 * address of the account; the answer is the same whatever it is.
 */
export function PasswordResetRequest({ brand, appearance, entry, signInPath, onNavigate, hrefOf }: PasswordResetRequestProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const [done, setDone] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const form = useForm<{ email: string }>({ defaultValues: { email: '' } })
  const appPath = useAppPath(entry, !signInPath)
  return (
    <AuthFrame brand={brand} appearance={appearance} title={t('account.resetTitle')} testId="password-reset">
      {done ? (
        <Outcome ok testId="reset-state">
          {t('account.resetRequested')}
        </Outcome>
      ) : (
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={form.handleSubmit(async ({ email }) => {
            setError(null)
            try {
              await requestPasswordReset(email, entry)
              setDone(true)
            } catch (e) {
              setError(failure(e, t('account.failed')))
            }
          })}
        >
          <p className="text-muted-foreground text-sm">{t('account.resetHint')}</p>
          <AuthField
            icon={Mail}
            type="email"
            placeholder={t('account.email')}
            autoComplete="email"
            error={form.formState.errors.email?.message ?? error ?? undefined}
            {...form.register('email', { validate: (v) => v.trim() !== '' || t('account.emailRequired') })}
          />
          <AuthSubmit busy={form.formState.isSubmitting}>{t('account.sendLink')}</AuthSubmit>
        </form>
      )}
      {signInPath ? (
        <AuthLink to={signInPath} onNavigate={onNavigate} hrefOf={hrefOf} className={AUTH_LINK_CLASS}>
          {t('login.back')}
        </AuthLink>
      ) : (
        <a href={appPath} className={AUTH_LINK_CLASS}>
          {t('login.back')}
        </a>
      )}
    </AuthFrame>
  )
}

/** The root path of the entry's frontend, asked only when wanted. */
function useAppPath(entry: string | undefined, wanted: boolean): string {
  const [path, setPath] = useState('/')
  useEffect(() => {
    if (!wanted) return undefined
    let live = true
    entryInfo(entry)
      .then((info) => {
        if (live) setPath(sitePath(info.appPath))
      })
      .catch(() => undefined)
    return () => {
      live = false
    }
  }, [entry, wanted])
  return path
}
