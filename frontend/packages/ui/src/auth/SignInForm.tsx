import { api, entryInfo, isProblem, lastUserName, sitePath, unwrap, useOptionalAuth, type AuthState, type SignInStep } from '@jabiz/client'
import { CircleCheck, Info, LockKeyhole, Mail, TriangleAlert, User } from 'lucide-react'
import { useEffect, useState, type ReactNode } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Alert, AlertDescription } from '../components/ui/alert'
import { Button } from '../components/ui/button'
import { Separator } from '../components/ui/separator'
import { EmailLinkForm } from './EmailLink'
import { AUTH_LINK_CLASS, AuthError, AuthField, AuthFrame, AuthLink, AuthSubmit, navigateTo, type AuthNavigation } from './frame'
import { ResendVerification } from './MailLinkPage'
import { AUTH_NAMESPACE } from './messages'
import { MfaCodeStep } from './MfaCodeStep'
import { MfaEnrollment } from './MfaEnrollment'
import { OidcButtons, type OidcProvider } from './OidcButtons'
import { refusal } from './problems'

const missing = () => Promise.reject(new Error('SignInForm needs an AuthProvider around it, or the auth property'))
const NO_AUTH: Pick<AuthState, 'signIn' | 'verify'> = { signIn: missing, verify: missing }

type Step =
  | { kind: 'password'; notice?: 'enrolled' }
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
  /** How the providers are offered: full-width buttons (the default) or a row of icons (decision D61 item 5). */
  oidcLayout?: 'buttons' | 'icons'
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
  /** Starts signing in through a provider; `startProviderSignIn` of `@jabiz/client` by default. */
  startProvider?: (providerId: string, returnTo: string) => Promise<void>
  /** Signing in and the second step; the session's (`useAuth`) by default. */
  auth?: Pick<AuthState, 'signIn' | 'verify'>
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
  // Only a path of this site: the page to return to may come from an address anyone can write.
  const returnTo = sitePath(props.returnTo)
  const { t } = useTranslation(AUTH_NAMESPACE)
  const session = useOptionalAuth()
  const auth = props.auth ?? session ?? NO_AUTH
  const [error, setError] = useState<string | null>(null)
  const [unverified, setUnverified] = useState(false)
  const [step, setStep] = useState<Step>(() => {
    if (initialStep?.status === 'MFA_REQUIRED') return { kind: 'code', challenge: initialStep.challenge }
    if (initialStep?.status === 'MFA_ENROLLMENT_REQUIRED') return { kind: 'enroll', challenge: initialStep.challenge }
    return { kind: 'password' }
  })
  const form = useForm<{ userName: string; password: string }>({
    defaultValues: { userName: props.initialUserName ?? (notice === 'idle' ? lastUserName() : undefined) ?? '', password: '' },
  })
  const { selfRegistration, emailLink } = useEntryOffers(!!props.signUpPath, !!props.emailLink)
  const byEmail = props.userNameLabel === 'email'

  const signedIn = () => (props.onSignedIn ? props.onSignedIn(returnTo) : navigateTo({ onNavigate, hrefOf }, returnTo))
  const failed = (e: unknown) => {
    setUnverified(false)
    if (isProblem(e, 'EMAIL_NOT_VERIFIED')) setUnverified(true)
    else if (isProblem(e, 'SIGN_IN_REFUSED')) setError(t('login.refused'))
    else setError(refusal(e, t('common.error')))
  }
  const frame = { brand, appearance, footer }

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
          onDone={() => setStep({ kind: 'password', notice: 'enrolled' })}
        />
        <Button variant="link" onClick={() => setStep({ kind: 'password' })}>
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
          error={error}
          onBack={() => {
            setError(null)
            setStep({ kind: 'password' })
          }}
          onVerify={async (code) => {
            setError(null)
            try {
              await auth.verify(challenge, code)
              signedIn()
            } catch (e) {
              failed(e)
            }
          }}
        />
      </AuthFrame>
    )
  }

  const errors = form.formState.errors
  const userName = form.getValues('userName')
  return (
    <AuthFrame {...frame} title={t('login.title')}>
      {notice === 'idle' && step.notice !== 'enrolled' && (
        <Alert variant="warning" data-testid="idle-locked">
          <TriangleAlert aria-hidden />
          <AlertDescription className="text-current">{t('login.idleLocked')}</AlertDescription>
        </Alert>
      )}
      {step.notice === 'enrolled' && (
        <Alert data-testid="mfa-enrolled">
          <CircleCheck aria-hidden className="text-success" />
          <AlertDescription className="text-foreground">{t('login.enrolled')}</AlertDescription>
        </Alert>
      )}
      {error && <AuthError message={error} testId="login-error" />}
      {unverified && (
        <>
          <Alert variant="warning" data-testid="login-email-not-verified">
            <TriangleAlert aria-hidden />
            <AlertDescription className="text-current">{t('login.emailNotVerified')}</AlertDescription>
          </Alert>
          <ResendVerification initialEmail={userName.includes('@') ? userName : ''} />
        </>
      )}
      {emailLink && (
        <>
          {/* Two address fields on one page: each group says which way of signing in it is. */}
          <div role="group" aria-label={t('login.byEmailLink')} data-testid="login-email-link">
            <EmailLinkForm to={hrefOf ? hrefOf(returnTo) : returnTo} />
          </div>
          <div className="text-muted-foreground flex items-center gap-3 text-sm" data-testid="login-or-password">
            <Separator className="flex-1" />
            {t('login.orPassword')}
            <Separator className="flex-1" />
          </div>
        </>
      )}
      <form
        noValidate
        className="flex flex-col gap-4"
        aria-label={emailLink ? t('login.byPassword') : undefined}
        onSubmit={form.handleSubmit(async ({ userName: name, password }) => {
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
          autoComplete="username"
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
        <AuthSubmit busy={form.formState.isSubmitting}>{t('login.submit')}</AuthSubmit>
      </form>
      {props.passwordResetPath && (
        <Button variant="link" asChild className="self-center">
          <AuthLink to={props.passwordResetPath} onNavigate={onNavigate} hrefOf={hrefOf} data-testid="forgot-password">
            {t('login.forgotPassword')}
          </AuthLink>
        </Button>
      )}
      {props.signUpPath && selfRegistration && (
        <AuthLink to={props.signUpPath} onNavigate={onNavigate} hrefOf={hrefOf} className={AUTH_LINK_CLASS} data-testid="sign-up-link">
          {t('login.signUp')}
        </AuthLink>
      )}
      <OidcButtons
        returnTo={returnTo}
        providers={props.providers}
        start={props.startProvider}
        layout={props.oidcLayout}
        onError={(e) => (e === null ? setError(null) : failed(e))}
      />
    </AuthFrame>
  )
}

/** What the frontend's entry offers of signing up and signing in by mail (asked only when the page would show it). */
function useEntryOffers(signUp: boolean, emailLink: boolean): { selfRegistration: boolean; emailLink: boolean } {
  const wanted = signUp || emailLink
  const [offered, setOffered] = useState({ selfRegistration: false, emailLink: false })
  useEffect(() => {
    if (!wanted) return undefined
    let live = true
    entryInfo()
      .then((info) => {
        if (live) setOffered({ selfRegistration: !!info.selfRegistration, emailLink: !!info.emailLink })
      })
      .catch(() => undefined)
    return () => {
      live = false
    }
  }, [wanted])
  return { selfRegistration: signUp && offered.selfRegistration, emailLink: emailLink && offered.emailLink }
}
