import { api, checkVerificationLink, confirmPasswordReset, confirmVerificationLink, resendVerification, sitePath,
  unwrap, useMailLink } from '@jabiz/client'
import { Info, LockKeyhole, Mail } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { useForm, type UseFormReturn } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Alert, AlertDescription } from '../components/ui/alert'
import { Button } from '../components/ui/button'
import {
  AUTH_LINK_CLASS,
  AuthField,
  AuthFrame,
  AuthLink,
  AuthSubmit,
  ContinueTo,
  Outcome,
  type AuthNavigation,
} from './frame'
import { EmailLinkPanel } from './EmailLink'
import { AUTH_NAMESPACE } from './messages'
import { PasswordResetRequest } from './PasswordResetRequest'
import { failure, invalidLink, passwordProblems, passwordRequired } from './problems'

/** A mailed link as the panels use it: its entry and token, and where the entry's frontend is. */
export interface MailLinkInfo {
  entry: string
  token: string | null
  /** The root path of the entry's frontend (`app-path`): where the panel leads once done. */
  appPath: string
}

/** What every page of a mailed link takes: the frame and the navigation. */
export interface MailLinkPageBase extends AuthNavigation {
  /** The application's name, the page's heading. */
  brand: ReactNode
  /** Offers the appearance as well as the language (default true). */
  appearance?: boolean
  /** The sign-in page, as a path of this frontend; the entry's frontend (`app-path`) when absent. */
  signInPath?: string
  /** The page to ask for a new password, as a path of this frontend; this page's path when absent. */
  passwordResetPath?: string
  /**
   * Signed in with a mailed sign-in link (`email-link`, decision D54): goes to `path`, the link's own path of this
   * frontend; the frontend's navigation (`onNavigate`) or a page load when absent.
   */
  onSignedIn?(path: string): void
}

export interface MailLinkPageProps extends MailLinkPageBase {
  /**
   * Which page of a link this is: `verify-email`, `join`, `password-reset` or `email-link`; the last segment of the address when
   * absent (links are `{entryUrl}auth/<page>?entry=…&token=…`, decision D43 item 4).
   */
  page?: string
  /** The query of the address, when a router keeps its own location; the address bar's when absent. */
  search?: string
}

/**
 * The pages mailed links lead to (docs/design/10-security.md section 15.7; decisions D36 and D43): verifying an
 * address, joining an entry with an existing account, choosing a new password (or, without a token, asking for
 * the link) and signing in with a mailed link. The token is taken out of the address bar at once and the page sends no referrer while open; nothing is
 * sent before the person clicks, so that a mail scanner opening the link changes nothing.
 */
export function MailLinkPage({ page, search, ...base }: MailLinkPageProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const link = useMailLink(search)
  const which = page ?? link.page
  if (which === 'verify-email') return <VerifyEmailPanel {...base} link={link} />
  if (which === 'join') return <JoinPanel {...base} link={link} />
  if (which === 'email-link') {
    return <EmailLinkPanel {...base} link={link} to={new URLSearchParams(search ?? window.location.search).get('to')} />
  }
  if (which === 'password-reset') {
    return link.token
      ? <PasswordResetConfirm {...base} link={link} />
      : <PasswordResetRequest {...base} entry={link.entry} />
  }
  return (
    <AuthFrame brand={base.brand} appearance={base.appearance} title={t('account.continue')}>
      <Outcome ok={false} testId="mail-link-unknown">
        {t('account.unknownPage')}
      </Outcome>
    </AuthFrame>
  )
}

type PanelProps = MailLinkPageBase & { link: MailLinkInfo }

export type NewPassword = { password: string; confirm: string }

/** A new password, twice; the server's rules (length) answer with their own message. */
export function NewPasswordFields({ form, serverError }: { form: UseFormReturn<NewPassword>; serverError?: string | null }) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const errors = form.formState.errors
  return (
    <>
      <AuthField
        icon={LockKeyhole}
        type="password"
        placeholder={t('account.newPassword')}
        autoComplete="new-password"
        error={errors.password?.message ?? serverError ?? undefined}
        {...form.register('password', { validate: (v) => v !== '' || t('account.passwordRequired') })}
      />
      <AuthField
        icon={LockKeyhole}
        type="password"
        placeholder={t('account.confirmPassword')}
        autoComplete="new-password"
        error={errors.confirm?.message}
        {...form.register('confirm', {
          validate: (v, values) => v === values.password || t('account.passwordMismatch'),
        })}
      />
    </>
  )
}

type VerifyState = 'ready' | 'password' | 'done' | 'invalid' | 'failed'

/**
 * Where the link of a verification mail leads (decision D36 item 3). The click first asks the server what the link
 * needs (without using it): an account that registered itself and was never verified needs its password, or a new
 * one from whoever owns the mailbox but did not register (which takes the account over). A link that is no good
 * (expired, used, superseded) offers to send a new one; the answer is the same whatever the address. After a failure
 * that is not the link's, the person may try again.
 */
export function VerifyEmailPanel({ link, brand, appearance }: PanelProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const { entry, token, appPath } = link
  const [state, setState] = useState<VerifyState>(token ? 'ready' : 'invalid')
  const [busy, setBusy] = useState(false)
  // Checked in this frontend's session (decision D61 item 4): then used there too.
  const [inSession, setInSession] = useState(false)

  const failed = (e: unknown) => setState(invalidLink(e) ? 'invalid' : 'failed')

  const start = async () => {
    setBusy(true)
    try {
      const check = await checkVerificationLink(token!)
      setInSession(check.inSession)
      if (check.passwordRequired) {
        setState('password')
      } else {
        await confirmVerificationLink(token!, check.inSession)
        setState('done')
      }
    } catch (e) {
      failed(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthFrame brand={brand} appearance={appearance} title={t('account.verifyTitle')} testId="verify-email">
      {state === 'ready' && (
        <>
          <p className="text-sm" data-testid="verify-state" data-state={state}>
            {t('account.verifyHint')}
          </p>
          <Button size="lg" className="w-full" disabled={busy} aria-busy={busy || undefined} data-testid="verify-start"
            onClick={() => void start()}>
            {t('account.verify')}
          </Button>
        </>
      )}
      {state === 'password' && <PasswordStep token={token!} inSession={inSession} onDone={() => setState('done')}
        onFailed={failed} />}
      {state === 'done' && (
        <Outcome ok testId="verify-state" next={<ContinueTo path={appPath} />}>
          <span data-state={state}>{t('account.verified')}</span>
        </Outcome>
      )}
      {state === 'failed' && (
        <Outcome
          ok={false}
          testId="verify-state"
          next={
            <Button variant="outline" className="w-full" data-testid="verify-retry" onClick={() => setState('ready')}>
              {t('account.retry')}
            </Button>
          }
        >
          <span data-state={state}>{t('account.failed')}</span>
        </Outcome>
      )}
      {state === 'invalid' && (
        <>
          <Outcome ok={false} testId="verify-state">
            <span data-state={state}>{t('account.invalidLink')}</span>
          </Outcome>
          <p className="text-muted-foreground text-sm">{t('account.invalidLinkHint')}</p>
          <ResendVerification entry={entry} />
        </>
      )}
    </AuthFrame>
  )
}

/** The password of an account nobody verified yet: the registrant's current one, or a new one that takes it over. */
function PasswordStep({ token, inSession, onDone, onFailed }: {
  token: string
  inSession: boolean
  onDone(): void
  onFailed(e: unknown): void
}) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const [mode, setMode] = useState<'current' | 'new'>('current')
  const [fieldError, setFieldError] = useState<string | null>(null)
  const current = useForm<{ password: string }>({ defaultValues: { password: '' } })
  const fresh = useForm<NewPassword>({ defaultValues: { password: '', confirm: '' } })

  const send = async (body: { token: string; password?: string; newPassword?: string }) => {
    setFieldError(null)
    try {
      await confirmVerificationLink(token, inSession, { password: body.password, newPassword: body.newPassword })
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
        <AlertDescription data-testid="verify-password-hint">{t('account.verifyPasswordHint')}</AlertDescription>
      </Alert>
      {/* An account opened through Google, Facebook or LINE has no password (decision D61 item 4). */}
      <p className="text-muted-foreground text-sm" data-testid="verify-session-hint">{t('account.verifySessionHint')}</p>
      {mode === 'current' ? (
        <form noValidate className="flex flex-col gap-4"
          onSubmit={current.handleSubmit(({ password }) => send({ token, password }))}>
          <AuthField
            icon={LockKeyhole}
            type="password"
            placeholder={t('login.password')}
            autoComplete="current-password"
            error={current.formState.errors.password?.message ?? fieldError ?? undefined}
            {...current.register('password', { validate: (v) => v !== '' || t('login.passwordRequired') })}
          />
          <AuthSubmit busy={current.formState.isSubmitting}>{t('account.verify')}</AuthSubmit>
        </form>
      ) : (
        <form noValidate className="flex flex-col gap-4"
          onSubmit={fresh.handleSubmit(({ password }) => send({ token, newPassword: password }))}>
          <NewPasswordFields form={fresh} serverError={fieldError} />
          <AuthSubmit busy={fresh.formState.isSubmitting}>{t('account.verify')}</AuthSubmit>
        </form>
      )}
      <Button variant="link" data-testid="verify-mode" onClick={() => {
        setFieldError(null)
        setMode(mode === 'current' ? 'new' : 'current')
      }}>
        {mode === 'current' ? t('account.useNewPassword') : t('account.useCurrentPassword')}
      </Button>
    </>
  )
}

/**
 * Asks for a new verification mail for an address; answered alike for every address. For a link that is no good,
 * after signing in was refused for an unverified address, and in the banner of a signed-in user.
 */
export function ResendVerification({ entry, initialEmail = '' }: { entry?: string; initialEmail?: string }) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const [done, setDone] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const form = useForm<{ email: string }>({ defaultValues: { email: initialEmail } })
  if (done) {
    return (
      <Outcome ok testId="resend-state">
        {t('account.resent')}
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
          await resendVerification(email, entry)
          setDone(true)
        } catch (e) {
          setError(failure(e, t('account.failed')))
        }
      })}
    >
      <p className="text-muted-foreground text-sm">{t('account.resendHint')}</p>
      <AuthField
        icon={Mail}
        type="email"
        placeholder={t('account.email')}
        autoComplete="email"
        error={form.formState.errors.email?.message ?? error ?? undefined}
        {...form.register('email', { validate: (v) => v.trim() !== '' || t('account.emailRequired') })}
      />
      <AuthSubmit busy={form.formState.isSubmitting}>{t('account.resend')}</AuthSubmit>
    </form>
  )
}

type JoinState = 'ready' | 'password' | 'done' | 'invalid'

/**
 * Where the link of the "you already have an account" mail leads (decision D36 item 2 and implementation note 6):
 * continuing adds the entry's roles to the account. An account somebody may have registered with this address before
 * must get a new password at the same time (the server answers 422 REQUIRED for `password`). Only problems of the
 * password go under its field; any other goes to the alert above the form.
 */
export function JoinPanel({ link, brand, appearance }: PanelProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const { token, appPath } = link
  const [state, setState] = useState<JoinState>(token ? 'ready' : 'invalid')
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
        setError(failure(e, t('account.failed')))
      }
    }
  }

  return (
    <AuthFrame brand={brand} appearance={appearance} title={t('account.joinTitle')} testId="join">
      {error && (
        <Outcome ok={false} testId="join-error">
          {error}
        </Outcome>
      )}
      {state === 'ready' && (
        <>
          <p className="text-sm">{t('account.joinHint')}</p>
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
            {t('account.continue')}
          </Button>
        </>
      )}
      {state === 'password' && (
        <form noValidate className="flex flex-col gap-4" onSubmit={form.handleSubmit(({ password }) => join(password))}>
          <Alert role="note">
            <Info aria-hidden />
            <AlertDescription data-testid="join-password-hint">{t('account.joinPasswordHint')}</AlertDescription>
          </Alert>
          <NewPasswordFields form={form} serverError={fieldError} />
          <AuthSubmit busy={form.formState.isSubmitting}>{t('account.continue')}</AuthSubmit>
        </form>
      )}
      {state === 'done' && (
        <Outcome ok testId="join-state" next={<ContinueTo path={appPath} label={t('account.signIn')} />}>
          {t('account.joined')}
        </Outcome>
      )}
      {state === 'invalid' && (
        <Outcome ok={false} testId="join-state">
          {t('account.invalidLink')}
        </Outcome>
      )}
    </AuthFrame>
  )
}

/**
 * Sets the new password with the token of a mailed link (decision D36 item 5), which ends the account's sessions; the
 * person then signs in (no session is opened here). A link that is no good leads to asking for a new one.
 */
export function PasswordResetConfirm({ link, brand, appearance, passwordResetPath, onNavigate, hrefOf }: PanelProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const { entry, token, appPath } = link
  const [state, setState] = useState<'form' | 'done' | 'invalid'>('form')
  const [error, setError] = useState<string | null>(null)
  const [fieldError, setFieldError] = useState<string | null>(null)
  const form = useForm<NewPassword>({ defaultValues: { password: '', confirm: '' } })
  // Only a path of this site: an address like //host/… would be another site's.
  const again = `${passwordResetPath ?? sitePath(window.location.pathname)}?entry=${encodeURIComponent(entry)}`
  return (
    <AuthFrame brand={brand} appearance={appearance} title={t('account.resetTitle')} testId="password-reset">
      {state === 'form' && (
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={form.handleSubmit(async ({ password }) => {
            setError(null)
            setFieldError(null)
            try {
              await confirmPasswordReset(token ?? '', password)
              setState('done')
            } catch (e) {
              if (invalidLink(e)) setState('invalid')
              else if (passwordProblems(e).length > 0) setFieldError(passwordProblems(e).join(' '))
              else setError(failure(e, t('account.failed')))
            }
          })}
        >
          {error && (
            <Outcome ok={false} testId="reset-error">
              {error}
            </Outcome>
          )}
          <NewPasswordFields form={form} serverError={fieldError} />
          <AuthSubmit busy={form.formState.isSubmitting}>{t('account.setPassword')}</AuthSubmit>
        </form>
      )}
      {state === 'done' && (
        <Outcome ok testId="reset-state" next={<ContinueTo path={appPath} label={t('account.signIn')} />}>
          {t('account.resetDone')}
        </Outcome>
      )}
      {state === 'invalid' && (
        <Outcome
          ok={false}
          testId="reset-state"
          next={
            passwordResetPath ? (
              <AuthLink to={again} onNavigate={onNavigate} hrefOf={hrefOf} className={AUTH_LINK_CLASS}>
                {t('account.sendLink')}
              </AuthLink>
            ) : (
              <a href={again} className={AUTH_LINK_CLASS}>
                {t('account.sendLink')}
              </a>
            )
          }
        >
          {t('account.invalidLink')}
        </Outcome>
      )}
    </AuthFrame>
  )
}
