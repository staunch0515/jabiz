import {
  api,
  isProblem,
  languages,
  requestEmailLink,
  sitePath,
  unwrap,
  useOptionalAuth,
  type AuthState,
  type SignInStep,
} from '@jabiz/client'
import { Info, Mail } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Alert, AlertDescription } from '../components/ui/alert'
import { Button } from '../components/ui/button'
import { AuthField, AuthFrame, AuthSubmit, navigateTo, Outcome } from './frame'
import type { MailLinkInfo, MailLinkPageBase } from './MailLinkPage'
import { MfaCodeStep } from './MfaCodeStep'
import { MfaEnrollment } from './MfaEnrollment'
import { AUTH_NAMESPACE } from './messages'
import { failure, invalidLink, refusal } from './problems'

export interface EmailLinkFormProps {
  /** The entry the link signs in to; the frontend's when absent. */
  entry?: string
  /** The path of this frontend the link leads back to once signed in; the entry's home when absent. */
  to?: string
  /** The address to start with. */
  initialEmail?: string
  /** The text of the button; "Send me a link" when absent. */
  submitLabel?: ReactNode
  /** The request was sent (the answer is the same for every address). */
  onSent?(email: string): void
}

/**
 * Asks for a link to sign in with by mail (docs/design/10-security.md section 15.11; decision D54): the address only,
 * no password. A part of a page, not a page: the application puts it where signing in starts (a page to share, its own
 * sign-in page). The answer is the same for every address, and so is what it says.
 */
export function EmailLinkForm({ entry, to, initialEmail = '', submitLabel, onSent }: EmailLinkFormProps) {
  const { t, i18n } = useTranslation(AUTH_NAMESPACE)
  const [sent, setSent] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const form = useForm<{ email: string }>({ defaultValues: { email: initialEmail } })
  if (sent !== null) {
    return (
      <div className="flex flex-col gap-4" data-testid="email-link-form">
        <Outcome ok testId="email-link-sent">
          {t('emailLink.sent', { email: sent })}
        </Outcome>
        <Button variant="link" data-testid="email-link-again" onClick={() => setSent(null)}>
          {t('emailLink.otherAddress')}
        </Button>
      </div>
    )
  }
  return (
    <form
      noValidate
      className="flex flex-col gap-4"
      data-testid="email-link-form"
      onSubmit={form.handleSubmit(async ({ email }) => {
        setError(null)
        try {
          const locale = (languages as readonly string[]).includes(i18n.language) ? i18n.language : undefined
          await requestEmailLink(email, { to, locale }, entry)
          setSent(email.trim())
          onSent?.(email.trim())
        } catch (e) {
          setError(failure(e, t('account.failed')))
        }
      })}
    >
      <p className="text-muted-foreground text-sm">{t('emailLink.hint')}</p>
      <AuthField
        icon={Mail}
        type="email"
        placeholder={t('account.email')}
        autoComplete="email"
        data-testid="email-link-email"
        error={form.formState.errors.email?.message ?? error ?? undefined}
        {...form.register('email', { validate: (v) => v.trim() !== '' || t('account.emailRequired') })}
      />
      <AuthSubmit busy={form.formState.isSubmitting}>{submitLabel ?? t('emailLink.send')}</AuthSubmit>
    </form>
  )
}

export interface EmailLinkPanelProps extends MailLinkPageBase {
  link: MailLinkInfo
  /** The path the link leads back to (its `to`); only a path of this site is followed. */
  to?: string | null
  /** Signs in with the token and finishes the second step; the session's (`useAuth`) by default. */
  auth?: Pick<AuthState, 'signInWithEmailLink' | 'verify'>
  /** Signed in: goes to `path`; the frontend's navigation (`onNavigate`) or a page load by default. */
  onSignedIn?(path: string): void
}

type PanelState =
  | { kind: 'ready' }
  | { kind: 'code'; challenge: string }
  | { kind: 'enroll'; challenge: string }
  | { kind: 'enrolled' }
  | { kind: 'invalid' }
  | { kind: 'failed' }

const NO_AUTH: Pick<AuthState, 'signInWithEmailLink' | 'verify'> = {
  signInWithEmailLink: () => Promise.reject(new Error('EmailLinkPanel needs an AuthProvider around it, or the auth property')),
  verify: () => Promise.reject(new Error('EmailLinkPanel needs an AuthProvider around it, or the auth property')),
}

/**
 * Where a mailed sign-in link leads (decision D54): the click signs in (nothing is sent before it, so that a mail
 * scanner opening the link uses nothing), a second factor is asked like after a password, and the page goes back to
 * the path of the link. A link that is no good offers to send a new one for the same path.
 */
export function EmailLinkPanel({ link, to, brand, appearance, onNavigate, hrefOf, ...props }: EmailLinkPanelProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const session = useOptionalAuth()
  const auth = props.auth ?? session ?? NO_AUTH
  const { entry, token, appPath } = link
  // Only a path of this site: the path comes from an address anyone can write.
  const returnTo = sitePath(to, appPath)
  const [state, setState] = useState<PanelState>(token ? { kind: 'ready' } : { kind: 'invalid' })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const signedIn = () => (props.onSignedIn ? props.onSignedIn(returnTo) : navigateTo({ onNavigate, hrefOf }, returnTo))
  const next = (step: SignInStep) => {
    if (step.status === 'SIGNED_IN') signedIn()
    else setState({ kind: step.status === 'MFA_REQUIRED' ? 'code' : 'enroll', challenge: step.challenge })
  }

  const start = async () => {
    setBusy(true)
    setError(null)
    try {
      next(await auth.signInWithEmailLink(token!))
    } catch (e) {
      if (invalidLink(e)) setState({ kind: 'invalid' })
      else if (isProblem(e, 'SIGN_IN_REFUSED')) setError(t('login.refused'))
      else setState({ kind: 'failed' })
    } finally {
      setBusy(false)
    }
  }

  const again = (
    <EmailLinkForm entry={entry} to={to ?? undefined} />
  )

  return (
    <AuthFrame brand={brand} appearance={appearance} title={t('emailLink.title')} testId="email-link">
      {state.kind === 'ready' && (
        <>
          {error && (
            <Outcome ok={false} testId="email-link-error">
              {error}
            </Outcome>
          )}
          <p className="text-sm">{t('emailLink.signInHint')}</p>
          <Button size="lg" className="w-full" disabled={busy} aria-busy={busy || undefined}
            data-testid="email-link-start" onClick={() => void start()}>
            {t('emailLink.signIn')}
          </Button>
        </>
      )}
      {state.kind === 'code' && (
        <MfaCodeStep
          error={error}
          onBack={() => {
            setError(null)
            setState({ kind: 'invalid' })
          }}
          onVerify={async (code) => {
            setError(null)
            try {
              await auth.verify(state.challenge, code)
              signedIn()
            } catch (e) {
              setError(refusal(e, t('common.error')))
            }
          }}
        />
      )}
      {state.kind === 'enroll' && (
        <>
          <Alert role="note">
            <Info aria-hidden />
            <AlertDescription>{t('login.enrollHint')}</AlertDescription>
          </Alert>
          <MfaEnrollment
            begin={async () => {
              const answer = await unwrap(api.POST('/api/auth/challenge/enroll', { body: { challenge: state.challenge } }))
              return { secret: answer.secret!, otpauthUri: answer.otpauthUri! }
            }}
            confirm={async (code) =>
              (await unwrap(api.POST('/api/auth/challenge/enroll/confirm', { body: { challenge: state.challenge, code } })))
                .recoveryCodes ?? []
            }
            onDone={() => setState({ kind: 'enrolled' })}
          />
        </>
      )}
      {state.kind === 'enrolled' && (
        <>
          <Outcome ok testId="email-link-state">
            <span data-state="enrolled">{t('emailLink.enrolled')}</span>
          </Outcome>
          {again}
        </>
      )}
      {state.kind === 'failed' && (
        <Outcome
          ok={false}
          testId="email-link-state"
          next={
            <Button variant="outline" className="w-full" data-testid="email-link-retry"
              onClick={() => setState({ kind: 'ready' })}>
              {t('account.retry')}
            </Button>
          }
        >
          <span data-state="failed">{t('account.failed')}</span>
        </Outcome>
      )}
      {state.kind === 'invalid' && (
        <>
          <Outcome ok={false} testId="email-link-state">
            <span data-state="invalid">{t('account.invalidLink')}</span>
          </Outcome>
          <p className="text-muted-foreground text-sm">{t('emailLink.invalidHint')}</p>
          {again}
        </>
      )}
    </AuthFrame>
  )
}
