import {
  ApiError, PasskeyCancelledError, api, enrolPasskey, entryInfo, forgetSignInStep, isProblem, languages, lastSignInMethod,
  lastUserName, passkeyAutofill, passkeySupport, pendingSignInStep, rememberSignInMethod, requestEmailLink, sitePath, unwrap,
  useOptionalAuth, type AuthState, type SignInStep,
} from '@jabiz/client'
import { CircleCheck, Fingerprint, Info, LockKeyhole, Mail, TriangleAlert, User } from 'lucide-react'
import { Fragment, useEffect, useState, type ReactNode } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Alert, AlertDescription } from '../components/ui/alert'
import { Button } from '../components/ui/button'
import { Separator } from '../components/ui/separator'
import { EmailLinkForm } from './EmailLink'
import { EntryTabs, useRetryWait } from './entry'
import { AUTH_LINK_CLASS, AuthError, AuthField, AuthFrame, AuthLink, AuthSubmit, navigateTo, Outcome, type AuthNavigation } from './frame'
import { ResendVerification } from './MailLinkPage'
import { AUTH_NAMESPACE } from './messages'
import { MfaCodeStep } from './MfaCodeStep'
import { MfaEnrollment } from './MfaEnrollment'
import { OidcButtons, useOidcProviders, type OidcProvider } from './OidcButtons'
import { passkeyDeviceName } from './Passkeys'
import { failure, refusal } from './problems'

const missing = () => Promise.reject(new Error('SignInForm needs an AuthProvider around it, or the auth property'))
/** What the form signs in with: a password and a code; with passkeys too where given (decision D71). */
type SignInAuth = Pick<AuthState, 'signIn' | 'verify'> & Partial<Pick<AuthState, 'signInWithPasskey' | 'verifyWithPasskey'>>
const NO_AUTH: SignInAuth = { signIn: missing, verify: missing }

/**
 * The pending suggestion of passkeys in the name field (conditional mediation), stopped before any other sign-in: a
 * browser runs one passkey request at a time. One per page, as there is one sign-in form.
 */
let pendingAutofill: AbortController | null = null

function stopAutofill() {
  pendingAutofill?.abort()
  pendingAutofill = null
}

/** A way of signing in, for the order of the sign-in form (phase 17zk). */
export type SignInMethod = 'emailLink' | 'emailLinkButton' | 'passkey' | 'providers' | 'password'

type Step =
  | { kind: 'password'; notice?: 'enrolled' | 'enrolledPasskey' }
  | { kind: 'code'; challenge: string }
  | { kind: 'enroll'; challenge: string }

export interface SignInFormProps extends AuthNavigation {
  /** The application's name, the page's heading. */
  brand: ReactNode
  /** Where to go once signed in: the page the user came from, else the frontend's home. */
  returnTo: string
  /** Called once signed in, with `returnTo`; goes there through the navigation by default. */
  onSignedIn?: (path: string) => void
  /** The step to continue with: back from an identity provider that wants a second factor. */
  initialStep?: SignInStep
  /** Says why the user is here: signed out after a period of inactivity. */
  notice?: 'idle'
  /** The user name filled in (after an idle lock, the one last used). */
  initialUserName?: string
  /** The page to ask for a new password; no link when absent. */
  passwordResetPath?: string
  /** The sign-up page; linked when given and the entry offers self-registration. */
  signUpPath?: string
  /** The identity providers when the caller has them; loaded otherwise. */
  providers?: readonly OidcProvider[]
  /**
   * How the providers are offered: full-width buttons (the default) or a row of icons (decision D61 item 5); since
   * phase 17zk also `responsive`, icons on a phone and buttons on a wide screen.
   */
  oidcLayout?: 'buttons' | 'icons' | 'responsive'
  /**
   * The order of the ways of signing in (phase 17zk, suggestion ③), per entry and, given as a function, per language
   * of the page: `emailLink` (the mailed link's form), `emailLinkButton` (the same behind a button, among "other
   * ways"), `passkey`, `providers`, `password`. Ways left out follow in the order of before (mailed link, passkey,
   * password, providers): none the entry offers is dropped. Absent: the order of before.
   */
  order?: readonly SignInMethod[] | ((language: string) => readonly SignInMethod[])
  /**
   * Signing in and signing up as two tabs of one page (phase 17zk, suggestion ④), in place of the link to signing up:
   * needs `signUpPath` and an entry that offers self-registration. This page's own path (`signInPath`) may be given.
   */
  tabs?: boolean
  /** This page's path, for its tab. */
  signInPath?: string
  /** Under the form, inside its card: a hint about signing in here (phase 17zk). */
  below?: ReactNode
  /**
   * Continues the second step kept in this tab by the identity provider's return (`keepSignInStep`, phase 17zk gap
   * 2), with the page to return to it kept, also after a reload; off by default (a frontend opts in). `initialStep`
   * wins, and nothing is resumed on the page after an idle lock. A kept challenge the server no longer takes
   * (expired) is dropped: the form starts over at the password with the server's message.
   */
  resumeStep?: boolean
  /**
   * Offers signing in by a mailed link first, above the password (phase 17m; decision D54): for people who sign up with
   * their address and rarely keep a password. Shown only when the frontend's entry offers it (`email-link`).
   */
  emailLink?: boolean
  /**
   * What the name field asks for: `userName` (the default), or `email` where everybody signs up with their address
   * (phase 17m). Either way the server takes a user name or an address.
   */
  userNameLabel?: 'userName' | 'email'
  /**
   * Offers passkeys (decision D71): a button above the password, the browser's suggestions in the name field, the
   * second step and the enrolment a role requires with a passkey instead of a code. Shown only when the frontend's
   * entry takes passkeys (`passkeys`) and the browser can; inside another app's browser, a note to open the browser.
   */
  passkey?: boolean
  /** Starts signing in through a provider; `startProviderSignIn` of `@jabiz/client` by default. */
  startProvider?: (providerId: string, returnTo: string) => Promise<void>
  /** Signing in and the second step; the session's (`useAuth`) by default. */
  auth?: SignInAuth
  /**
   * `continue`: signing in and signing up are one "continue" (docs/design/10-security.md section 15.19; decision D84),
   * where the frontend's entry has it (`unifiedSignIn`): the address only, then a link by mail with the password as
   * the other way on the same step; straight to the password where this device signed in with one last. The mailed
   * link, the password form, the sign-up link and the tabs give way to it; passkeys and providers stay as ordered.
   * The ordinary form where the entry does not have it.
   */
  flow?: 'continue'
  /** Offers the appearance as well as the language (default true). */
  appearance?: boolean
  /** Small print under the form on every step (the admin shows the build it runs). */
  footer?: ReactNode
}

/**
 * Signing in (docs/design/10-security.md sections 4, 9 and 15): the password, then — for users with two-step
 * verification — a code, or the enrolment a role requires before the user signs in again; the identity providers;
 * links to reset a password and to sign up; where the application asks for it and the entry offers it, a link by mail
 * above the password. An entry that requires a verified address answers 403 EMAIL_NOT_VERIFIED
 * once the password was right: the form says so and offers a new link. A refusal by the application's rules
 * (SIGN_IN_REFUSED) gets a general text only. Every other refusal is the server's (LOGIN_FAILED, alike for every
 * cause), in the language of the request.
 */
export function SignInForm(props: SignInFormProps) {
  const { brand, initialStep, notice, appearance, footer, onNavigate, hrefOf } = props
  // The second step an identity provider's return kept in this tab, with its page to return to (gap 2).
  const [pending] = useState(() => (initialStep || !props.resumeStep || notice === 'idle' || props.initialUserName
    ? null : pendingSignInStep()))
  // Only a path of this site: the page to return to may come from an address anyone can write.
  const returnTo = sitePath(pending?.returnTo ?? props.returnTo)
  const { t, i18n } = useTranslation(AUTH_NAMESPACE)
  const session = useOptionalAuth()
  const auth = props.auth ?? session ?? NO_AUTH
  const [error, setError] = useState<string | null>(null)
  const [unverified, setUnverified] = useState(false)
  const [step, setStep] = useState<Step>(() => {
    const first = initialStep ?? pending?.step
    if (first?.status === 'MFA_REQUIRED') return { kind: 'code', challenge: first.challenge }
    if (first?.status === 'MFA_ENROLLMENT_REQUIRED') return { kind: 'enroll', challenge: first.challenge }
    return { kind: 'password' }
  })
  const [mailOpen, setMailOpen] = useState(false)
  // After "continue" (decision D84): the address, and whether its link went out or its password is asked.
  const [continued, setContinued] = useState<{ email: string; mode: 'sent' | 'password' } | null>(null)
  // A password sign-in begun from "continue": remembered on this device once it is done, second step and all.
  const [byContinuedPassword, setByContinuedPassword] = useState(false)
  const continueForm = useForm<{ email: string; password: string }>({ defaultValues: { email: '', password: '' } })
  // Too many attempts (429 with Retry-After, gap 5): the buttons wait as long as the server asks.
  const { seconds: wait, hold, message: waitMessage } = useRetryWait(t('common.error'))
  // A refusal, else what the server said with "too many requests" while the wait lasts (it goes with the wait).
  const shownError = error ?? waitMessage
  const form = useForm<{ userName: string; password: string }>({
    defaultValues: { userName: props.initialUserName ?? (notice === 'idle' ? lastUserName() : undefined) ?? '', password: '' },
  })
  const { selfRegistration, emailLink, passkeys, unifiedSignIn, loaded } = useEntryOffers(!!props.signUpPath,
    !!props.emailLink, !!props.passkey, props.flow === 'continue')
  const byEmail = props.userNameLabel === 'email'
  const support = passkeys ? passkeySupport() : 'unsupported'
  const withPasskey = passkeys && support === 'available' && !!auth.signInWithPasskey
  const providers = useOidcProviders(props.providers)

  const signedIn = () => {
    forgetSignInStep()
    // Only the way, never the address: next time this device goes straight to the password (decision D84).
    if (byContinuedPassword) rememberSignInMethod('password')
    if (props.onSignedIn) props.onSignedIn(returnTo)
    else navigateTo({ onNavigate, hrefOf }, returnTo)
  }
  /** Back to the password: the kept second step is done with. */
  const toPassword = (notice?: 'enrolled' | 'enrolledPasskey') => {
    forgetSignInStep()
    setStep(notice ? { kind: 'password', notice } : { kind: 'password' })
  }
  const failed = (e: unknown) => {
    setUnverified(false)
    if (hold(e)) {
      setError(null)
      return
    }
    if (isProblem(e, 'EMAIL_NOT_VERIFIED')) setUnverified(true)
    else if (isProblem(e, 'SIGN_IN_REFUSED')) setError(t('login.refused'))
    else setError(refusal(e, t('common.error')))
  }
  const frame = { brand, appearance, footer }
  /**
   * A failure of the second step: a resumed challenge the server no longer takes (401, expired) is dropped and the
   * form starts over at the password, saying why.
   */
  const stepFailed = (e: unknown, challenge: string) => {
    if (challenge === pending?.step.challenge && e instanceof ApiError && e.status === 401) toPassword()
    failed(e)
  }

  const passkeySignIn = async (options?: { autofill?: boolean; signal?: AbortSignal }) => {
    if (!auth.signInWithPasskey) return
    setError(null)
    try {
      await auth.signInWithPasskey(options)
      signedIn()
    } catch (e) {
      // Closed the dialog, or the suggestions were stopped: nothing to say.
      if (!(e instanceof PasskeyCancelledError)) failed(e)
    }
  }

  useEffect(() => {
    if (!withPasskey || step.kind !== 'password') return undefined
    let live = true
    void passkeyAutofill().then((available) => {
      if (!live || !available) return
      stopAutofill()
      const controller = new AbortController()
      pendingAutofill = controller
      void passkeySignIn({ autofill: true, signal: controller.signal })
    })
    return () => {
      live = false
      stopAutofill()
    }
    // Started once per password step; the sign-in's callbacks are this render's.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [withPasskey, step.kind])

  if (step.kind === 'enroll') {
    const challenge = step.challenge
    return (
      <AuthFrame {...frame} title={t('login.enrollTitle')} testId="mfa-enroll">
        <Alert role="note">
          <Info aria-hidden />
          <AlertDescription>{t('login.enrollHint')}</AlertDescription>
        </Alert>
        <MfaEnrollment
          begin={async () => {
            const answer = await unwrap(api.POST('/api/auth/challenge/enroll', { body: { challenge } }))
            return { secret: answer.secret!, otpauthUri: answer.otpauthUri! }
          }}
          confirm={async (code) =>
            (await unwrap(api.POST('/api/auth/challenge/enroll/confirm', { body: { challenge, code } })))
              .recoveryCodes ?? []
          }
          onDone={() => toPassword('enrolled')}
        />
        {withPasskey && (
          <Button variant="outline" data-testid="enroll-passkey" onClick={async () => {
            setError(null)
            try {
              await enrolPasskey(challenge, passkeyDeviceName())
              toPassword('enrolledPasskey')
            } catch (e) {
              if (!(e instanceof PasskeyCancelledError)) stepFailed(e, challenge)
            }
          }}>
            <Fingerprint aria-hidden />
            {t('login.enrollPasskey')}
          </Button>
        )}
        {shownError && <AuthError message={shownError} testId="login-error" />}
        <Button variant="link" onClick={() => toPassword()}>
          {t('login.back')}
        </Button>
      </AuthFrame>
    )
  }

  if (step.kind === 'code') {
    const challenge = step.challenge
    return (
      <AuthFrame {...frame} title={t('login.codeTitle')}>
        <MfaCodeStep
          error={shownError}
          onBack={() => {
            setError(null)
            toPassword()
          }}
          onVerify={async (code) => {
            setError(null)
            try {
              await auth.verify(challenge, code)
              signedIn()
            } catch (e) {
              stepFailed(e, challenge)
            }
          }}
        />
        {withPasskey && auth.verifyWithPasskey && (
          <Button variant="outline" data-testid="verify-passkey" onClick={async () => {
            setError(null)
            try {
              await auth.verifyWithPasskey!(challenge)
              signedIn()
            } catch (e) {
              if (e instanceof PasskeyCancelledError) return
              if (isProblem(e, 'MFA_NOT_ENROLLED')) setError(t('login.passkeyNoneForStep'))
              else stepFailed(e, challenge)
            }
          }}>
            <Fingerprint aria-hidden />
            {t('login.usePasskey')}
          </Button>
        )}
      </AuthFrame>
    )
  }

  /** Mails the link for the address (answered alike for every address), and says so. */
  const sendLink = async (email: string) => {
    setError(null)
    try {
      const locale = (languages as readonly string[]).includes(i18n.language) ? i18n.language : undefined
      await requestEmailLink(email, { to: hrefOf ? hrefOf(returnTo) : returnTo, locale })
      setContinued({ email, mode: 'sent' })
    } catch (e) {
      setError(hold(e) ? null : failure(e, t('account.failed')))
    }
  }

  if (unifiedSignIn && continued) {
    const { email, mode } = continued
    const other = (
      <Button variant="link" data-testid="continue-other-address" onClick={() => {
        setError(null)
        setUnverified(false)
        continueForm.reset({ email, password: '' })
        setContinued(null)
      }}>
        {t('continue.otherAddress')}
      </Button>
    )
    return (
      <AuthFrame {...frame} title={t(mode === 'sent' ? 'continue.sentTitle' : 'continue.passwordTitle')}
        testId={`continue-${mode}-step`} below={props.below}>
        <p className="text-sm font-medium break-all" data-testid="continue-address">{email}</p>
        {shownError && <AuthError message={shownError} testId="login-error" />}
        {unverified && (
          <>
            <Alert variant="warning" data-testid="login-email-not-verified">
              <TriangleAlert aria-hidden />
              <AlertDescription className="text-current">{t('login.emailNotVerified')}</AlertDescription>
            </Alert>
            <ResendVerification initialEmail={email} />
          </>
        )}
        {mode === 'sent' ? (
          <>
            <Outcome ok testId="continue-link-sent">{t('continue.sent', { email })}</Outcome>
            <Button size="lg" variant="outline" className="w-full" data-testid="continue-use-password" onClick={() => {
              setError(null)
              setContinued({ email, mode: 'password' })
            }}>
              <LockKeyhole aria-hidden />
              {t('continue.usePassword')}
            </Button>
            <Button variant="link" data-testid="continue-send-again" disabled={wait > 0}
              onClick={() => void sendLink(email)}>
              {t('continue.sendAgain')}
            </Button>
          </>
        ) : (
          <>
            <form
              noValidate
              className="flex flex-col gap-4"
              onSubmit={continueForm.handleSubmit(async ({ password }) => {
                setError(null)
                setUnverified(false)
                try {
                  const next = await auth.signIn(email, password)
                  if (next.status === 'SIGNED_IN') {
                    rememberSignInMethod('password')
                    signedIn()
                  } else {
                    // Remembered once the second step is done too (`signedIn`).
                    setByContinuedPassword(true)
                    setStep(next.status === 'MFA_REQUIRED'
                      ? { kind: 'code', challenge: next.challenge }
                      : { kind: 'enroll', challenge: next.challenge })
                  }
                } catch (e) {
                  failed(e)
                }
              })}
            >
              {/* The address for the browser's password manager, which pairs it with the password. */}
              <input type="email" name="username" autoComplete="username" value={email} readOnly hidden />
              <AuthField
                icon={LockKeyhole}
                type="password"
                placeholder={t('login.password')}
                autoComplete="current-password"
                data-testid="continue-password"
                error={continueForm.formState.errors.password?.message}
                {...continueForm.register('password', { validate: (v) => v !== '' || t('login.passwordRequired') })}
              />
              <AuthSubmit busy={continueForm.formState.isSubmitting} wait={wait}>{t('login.submit')}</AuthSubmit>
            </form>
            {props.passwordResetPath && (
              <Button variant="link" asChild className="self-center">
                <AuthLink to={props.passwordResetPath} onNavigate={onNavigate} hrefOf={hrefOf} data-testid="forgot-password">
                  {t('login.forgotPassword')}
                </AuthLink>
              </Button>
            )}
            <Button size="lg" variant="outline" className="w-full" data-testid="continue-use-link" disabled={wait > 0}
              onClick={() => {
                rememberSignInMethod('link')
                void sendLink(email)
              }}>
              <Mail aria-hidden />
              {t('continue.useLink')}
            </Button>
          </>
        )}
        {other}
      </AuthFrame>
    )
  }

  // Not the ordinary form first: what was typed there would go when "continue" replaces it.
  if (props.flow === 'continue' && !loaded && step.kind === 'password') {
    return (
      <AuthFrame {...frame} title={t('continue.title')} below={props.below} quietTitle>
        <p className="text-muted-foreground text-sm" role="status" data-testid="continue-loading">{t('common.loading')}</p>
      </AuthFrame>
    )
  }

  const errors = form.formState.errors
  const userName = form.getValues('userName')
  const tabs = !unifiedSignIn && props.tabs && props.signUpPath && selfRegistration
    ? <EntryTabs current="signIn" signInPath={props.signInPath} signUpPath={props.signUpPath} onNavigate={onNavigate}
      hrefOf={hrefOf} />
    : undefined
  const emailLinkGroup = (
    // Two address fields on one page: each group says which way of signing in it is.
    <div role="group" aria-label={t('login.byEmailLink')} data-testid="login-email-link">
      <EmailLinkForm to={hrefOf ? hrefOf(returnTo) : returnTo} />
    </div>
  )
  const shown = (method: SignInMethod) => method === 'password'
    || (method === 'emailLink' || method === 'emailLinkButton' ? emailLink && !unifiedSignIn
      : method === 'passkey' ? withPasskey || (passkeys && support === 'in-app-browser')
        : providers.length > 0)
  const methods = orderOf(typeof props.order === 'function' ? props.order(i18n.language) : props.order).filter(shown)
  const orPassword = (
    <div key="or" className="text-muted-foreground flex items-center gap-3 text-sm" data-testid="login-or-password">
      <Separator className="flex-1" />
      {t(unifiedSignIn ? 'continue.or' : 'login.orPassword')}
      <Separator className="flex-1" />
    </div>
  )
  const parts: ReactNode[] = []
  let saidOr = false
  methods.forEach((method, index) => {
    // "Or sign in with your password" once, where something other than a passkey comes before the password.
    if (method === 'password' && !saidOr && methods.slice(0, index).some((m) => m !== 'passkey')) {
      parts.push(orPassword)
      saidOr = true
    }
    if (method === 'emailLink') {
      parts.push(<Fragment key="emailLink">{emailLinkGroup}</Fragment>)
      if (methods[index + 1] === 'passkey' || methods[index + 1] === 'password') {
        parts.push(orPassword)
        saidOr = true
      }
    } else if (method === 'emailLinkButton') {
      parts.push(mailOpen ? <Fragment key="emailLink">{emailLinkGroup}</Fragment> : (
        <Button key="emailLink" size="lg" variant="outline" className="w-full" data-testid="login-email-link-open"
          onClick={() => setMailOpen(true)}>
          <Mail aria-hidden />
          {t('entry.emailLink')}
        </Button>
      ))
    } else if (method === 'passkey') {
      parts.push(withPasskey ? (
        <Button key="passkey" size="lg" variant="outline" className="w-full" data-testid="login-passkey"
          disabled={wait > 0} onClick={() => {
            stopAutofill()
            void passkeySignIn()
          }}>
          <Fingerprint aria-hidden />
          {t('login.passkey')}
        </Button>
      ) : (
        <p key="passkey" className="text-muted-foreground text-sm" data-testid="login-passkey-in-app">{t('login.passkeyInApp')}</p>
      ))
    } else if (method === 'providers') {
      parts.push(
        <OidcButtons
          key="providers"
          returnTo={returnTo}
          providers={providers}
          start={props.startProvider}
          layout={props.oidcLayout}
          bare={index === 0}
          onError={(e) => (e === null ? setError(null) : failed(e))}
        />,
      )
    } else if (unifiedSignIn) {
      parts.push(
        <form
          key="password"
          noValidate
          className="flex flex-col gap-4"
          data-testid="continue-form"
          onSubmit={continueForm.handleSubmit(async ({ email }) => {
            stopAutofill()
            const address = email.trim()
            if (lastSignInMethod() === 'password') {
              setError(null)
              setContinued({ email: address, mode: 'password' })
            } else {
              await sendLink(address)
            }
          })}
        >
          <AuthField
            icon={Mail}
            type="email"
            placeholder={t('account.email')}
            autoComplete={withPasskey ? 'username webauthn' : 'username'}
            inputMode="email"
            data-testid="continue-email"
            error={continueForm.formState.errors.email?.message}
            {...continueForm.register('email', { validate: (v) => v.trim() !== '' || t('account.emailRequired') })}
          />
          <AuthSubmit busy={continueForm.formState.isSubmitting} wait={wait}>{t('continue.submit')}</AuthSubmit>
        </form>,
      )
    } else {
      parts.push(
        <Fragment key="password">
          <form
            noValidate
            className="flex flex-col gap-4"
            aria-label={emailLink ? t('login.byPassword') : undefined}
            onSubmit={form.handleSubmit(async ({ userName: name, password }) => {
              stopAutofill()
              setError(null)
              setUnverified(false)
              try {
                const next = await auth.signIn(name, password)
                if (next.status === 'SIGNED_IN') {
                  signedIn()
                } else {
                  setStep(next.status === 'MFA_REQUIRED'
                    ? { kind: 'code', challenge: next.challenge }
                    : { kind: 'enroll', challenge: next.challenge })
                }
              } catch (e) {
                failed(e)
              }
            })}
          >
            <AuthField
              icon={byEmail ? Mail : User}
              placeholder={t(byEmail ? 'account.email' : 'login.userName')}
              autoComplete={withPasskey ? 'username webauthn' : 'username'}
              inputMode={byEmail ? 'email' : undefined}
              data-testid="login-user-name"
              error={errors.userName?.message}
              {...form.register('userName', {
                validate: (v) => v.trim() !== '' || t(byEmail ? 'account.emailRequired' : 'login.userNameRequired'),
              })}
            />
            <AuthField
              icon={LockKeyhole}
              type="password"
              placeholder={t('login.password')}
              autoComplete="current-password"
              error={errors.password?.message}
              {...form.register('password', { validate: (v) => v !== '' || t('login.passwordRequired') })}
            />
            <AuthSubmit busy={form.formState.isSubmitting} wait={wait}>{t('login.submit')}</AuthSubmit>
          </form>
          {props.passwordResetPath && (
            <Button variant="link" asChild className="self-center">
              <AuthLink to={props.passwordResetPath} onNavigate={onNavigate} hrefOf={hrefOf} data-testid="forgot-password">
                {t('login.forgotPassword')}
              </AuthLink>
            </Button>
          )}
          {props.signUpPath && selfRegistration && !tabs && (
            <AuthLink to={props.signUpPath} onNavigate={onNavigate} hrefOf={hrefOf} className={AUTH_LINK_CLASS}
              data-testid="sign-up-link">
              {t('login.signUp')}
            </AuthLink>
          )}
        </Fragment>,
      )
    }
  })

  return (
    <AuthFrame {...frame} title={t(unifiedSignIn ? 'continue.title' : 'login.title')} tabs={tabs} below={props.below}
      showOtherEntry quietTitle>
      {notice === 'idle' && step.notice !== 'enrolled' && (
        <Alert variant="warning" data-testid="idle-locked">
          <TriangleAlert aria-hidden />
          <AlertDescription className="text-current">{t('login.idleLocked')}</AlertDescription>
        </Alert>
      )}
      {step.notice && (
        <Alert data-testid="mfa-enrolled">
          <CircleCheck aria-hidden className="text-success" />
          <AlertDescription className="text-foreground">
            {t(step.notice === 'enrolledPasskey' ? 'login.enrolledPasskey' : 'login.enrolled')}
          </AlertDescription>
        </Alert>
      )}
      {shownError && <AuthError message={shownError} testId="login-error" />}
      {unverified && (
        <>
          <Alert variant="warning" data-testid="login-email-not-verified">
            <TriangleAlert aria-hidden />
            <AlertDescription className="text-current">{t('login.emailNotVerified')}</AlertDescription>
          </Alert>
          <ResendVerification initialEmail={userName.includes('@') ? userName : ''} />
        </>
      )}
      {parts}
    </AuthFrame>
  )
}

/** The ways of signing in, in the order of before (phase 17zk keeps it as the default). */
const DEFAULT_ORDER: readonly SignInMethod[] = ['emailLink', 'passkey', 'password', 'providers']

/**
 * The order of the ways of signing in: the given one, then whatever it left out in the default order — a way the
 * entry offers is never dropped, and the password is always there. `emailLink` and `emailLinkButton` are one way.
 */
export function orderOf(given: readonly SignInMethod[] | undefined): SignInMethod[] {
  const order: SignInMethod[] = []
  const same = (a: SignInMethod, b: SignInMethod) => a === b
    || ((a === 'emailLink' || a === 'emailLinkButton') && (b === 'emailLink' || b === 'emailLinkButton'))
  for (const method of [...(given ?? []), ...DEFAULT_ORDER]) {
    if (!order.some((m) => same(m, method))) order.push(method)
  }
  return order
}

/**
 * What the frontend's entry offers of signing up, signing in by mail and with passkeys (asked only when the page would
 * show it).
 */
export function useEntryOffers(signUp: boolean, emailLink: boolean, passkeys: boolean, unified = false):
  { selfRegistration: boolean; emailLink: boolean; passkeys: boolean; unifiedSignIn: boolean; loaded: boolean } {
  const wanted = signUp || emailLink || passkeys || unified
  const [offered, setOffered] = useState({ selfRegistration: false, emailLink: false, passkeys: false,
    unifiedSignIn: false })
  const [loaded, setLoaded] = useState(false)
  useEffect(() => {
    if (!wanted) return undefined
    let live = true
    entryInfo()
      .then((info) => {
        if (live) {
          setOffered({ selfRegistration: !!info.selfRegistration, emailLink: !!info.emailLink, passkeys: !!info.passkeys,
            unifiedSignIn: !!info.unifiedSignIn })
          setLoaded(true)
        }
      })
      // Not read: the ordinary form, which works without the entry's settings.
      .catch(() => {
        if (live) setLoaded(true)
      })
    return () => {
      live = false
    }
  }, [wanted])
  return {
    selfRegistration: signUp && offered.selfRegistration,
    emailLink: emailLink && offered.emailLink,
    passkeys: passkeys && offered.passkeys,
    unifiedSignIn: unified && offered.unifiedSignIn,
    loaded: !wanted || loaded,
  }
}
