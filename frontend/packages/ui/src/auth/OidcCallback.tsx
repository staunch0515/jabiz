import {
  finishIdentityLink,
  isProblem,
  takeBinder as takeStoredBinder,
  pendingPurpose as storedPurpose,
  takeReturnPath as takeStoredReturnPath,
  useOptionalAuth,
  type AuthState,
  type IdentityLinked,
  type OidcPurpose,
  type SignInStep,
} from '@jabiz/client'
import { TriangleAlert } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { PageState } from '../components/page-state'
import { Alert, AlertDescription } from '../components/ui/alert'
import { Button } from '../components/ui/button'
import { AUTH_NAMESPACE } from './messages'
import { refusal } from './problems'

export interface OidcCallbackProps {
  /** Where to go after the round trip when the start did not say: the frontend's home. */
  fallbackPath: string
  /** Signed in: goes to the page the sign-in started from. */
  onSignedIn(path: string): void
  /** A second factor (or its enrolment) comes next: the sign-in page continues with `step`, then goes to `path`. */
  onSecondStep(step: SignInStep, path: string): void
  /** Back to the sign-in page after a failure. */
  onBack(): void
  /** The query of the address, when a router keeps its own location; the address bar's when absent. */
  search?: string
  /** Hands state and code over; the session's (`useAuth`) by default. */
  auth?: Pick<AuthState, 'signInWithProvider'>
  /** The binder and the return path kept across the round trip; `@jabiz/client`'s by default. */
  takeBinder?: () => string | null
  takeReturnPath?: (fallback: string) => string
  /**
   * The round trip linked a provider account to the signed-in user (phase 16f): goes back to the page it started from
   * (`path`), which may say so; `onSignedIn` when absent.
   */
  onLinked?(path: string, linked: IdentityLinked): void
  /** What the round trip was for, kept when it started; `@jabiz/client`'s by default. */
  pendingPurpose?: () => OidcPurpose
  /** Hands state and code over to link the account; `finishIdentityLink` of `@jabiz/client` by default. */
  finishLink?: (state: string, code: string, binder: string) => Promise<IdentityLinked>
}

/** What Apple sends back when the person cancelled; other refusals are shown as they came. */
const CANCELLED = ['user_cancelled_authorize']

const missing = () => Promise.reject(new Error('OidcCallback needs an AuthProvider around it, or the auth property'))

/**
 * Where an identity provider sends the user back (docs/design/10-security.md section 12; decision D43 item 5): hands
 * state and code to the server once, then continues like a sign-in with a password — into the frontend, or to the
 * second step. A round trip started to link an account (`startIdentityLink`) finishes the link instead and goes back.
 * An address that belongs to an account already (403 `OIDC_ACCOUNT_EXISTS`, decision D39 item 2) says how to go on.
 */
export function OidcCallback({
  fallbackPath,
  onSignedIn,
  onSecondStep,
  onBack,
  search,
  auth,
  takeBinder = takeStoredBinder,
  takeReturnPath = takeStoredReturnPath,
  onLinked,
  pendingPurpose = storedPurpose,
  finishLink = finishIdentityLink,
}: OidcCallbackProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const session = useOptionalAuth()
  const signInWithProvider = (auth ?? session)?.signInWithProvider ?? missing
  const [failure, setFailure] = useState<string | null>(null)
  const [purpose] = useState<OidcPurpose>(() => pendingPurpose())
  const started = useRef(false)
  const [params] = useState(() => new URLSearchParams(search ?? window.location.search))
  const state = params.get('state')
  const code = params.get('code')
  // The provider refused, or the page was opened without what it needs.
  // The person cancelled at Apple (decision D67): back to signing in, no failure shown.
  const cancelled = !code && CANCELLED.includes(params.get('error') ?? '')
  useEffect(() => {
    if (cancelled) onBack()
  }, [cancelled, onBack])
  const refused = !state || !code
    ? params.get('error_description') ?? params.get('error') ?? t(purpose === 'LINK' ? 'login.linkFailed' : 'login.providerFailed')
    : null
  const error = refused ?? failure

  useEffect(() => {
    // The code works once: never send it twice (React may run effects twice in development).
    if (started.current || !state || !code) return
    started.current = true
    const binder = takeBinder() ?? ''
    if (purpose === 'LINK') {
      finishLink(state, code, binder)
        .then((linked) => (onLinked ?? onSignedIn)(takeReturnPath(fallbackPath), linked))
        .catch((e: unknown) => setFailure(refusal(e, t('common.error'))))
      return
    }
    signInWithProvider(state, code, binder)
      .then((next) => {
        const returnTo = takeReturnPath(fallbackPath)
        if (next.status === 'SIGNED_IN') onSignedIn(returnTo)
        else onSecondStep(next, returnTo)
      })
      .catch((e: unknown) => setFailure(isProblem(e, 'OIDC_ACCOUNT_EXISTS', 403) ? t('login.accountExists')
        : refusal(e, t('common.error'))))
  }, [state, code, purpose, signInWithProvider, finishLink, takeBinder, takeReturnPath, fallbackPath, onSignedIn,
    onLinked, onSecondStep, t])

  return (
    <main className="bg-background text-foreground flex min-h-screen items-start justify-center px-4 pt-20">
      {cancelled || !error ? (
        <PageState kind="loading" title={t('common.loading')} />
      ) : (
        <PageState
          kind="warning"
          titleAs="h1"
          title={t(purpose === 'LINK' ? 'login.linkFailed' : 'login.providerFailed')}
          description={
            <Alert variant="destructive" data-testid="oidc-error" className="text-left">
              <TriangleAlert aria-hidden />
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          }
          action={<Button onClick={onBack}>{t('login.back')}</Button>}
        />
      )}
    </main>
  )
}
