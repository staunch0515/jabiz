import { takeBinder as takeStoredBinder, takeReturnPath as takeStoredReturnPath, useOptionalAuth, type AuthState, type SignInStep } from '@jabiz/client'
import { TriangleAlert } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { PageState } from '../components/page-state'
import { Alert, AlertDescription } from '../components/ui/alert'
import { Button } from '../components/ui/button'
import { cn, UI_SCOPE } from '../lib/utils'
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
}

const missing = () => Promise.reject(new Error('OidcCallback needs an AuthProvider around it, or the auth property'))

/**
 * Where an identity provider sends the user back (docs/design/10-security.md section 12; decision D43 item 5): hands
 * state and code to the server once, then continues like a sign-in with a password — into the frontend, or to the
 * second step.
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
}: OidcCallbackProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const session = useOptionalAuth()
  const signInWithProvider = (auth ?? session)?.signInWithProvider ?? missing
  const [failure, setFailure] = useState<string | null>(null)
  const started = useRef(false)
  const [params] = useState(() => new URLSearchParams(search ?? window.location.search))
  const state = params.get('state')
  const code = params.get('code')
  // The provider refused, or the page was opened without what it needs.
  const refused = !state || !code
    ? params.get('error_description') ?? params.get('error') ?? t('login.providerFailed')
    : null
  const error = refused ?? failure

  useEffect(() => {
    // The code works once: never send it twice (React may run effects twice in development).
    if (started.current || !state || !code) return
    started.current = true
    signInWithProvider(state, code, takeBinder() ?? '')
      .then((next) => {
        const returnTo = takeReturnPath(fallbackPath)
        if (next.status === 'SIGNED_IN') onSignedIn(returnTo)
        else onSecondStep(next, returnTo)
      })
      .catch((e: unknown) => setFailure(refusal(e, t('common.error'))))
  }, [state, code, signInWithProvider, takeBinder, takeReturnPath, fallbackPath, onSignedIn, onSecondStep, t])

  return (
    <main className={cn(UI_SCOPE, 'bg-background text-foreground flex min-h-screen items-start justify-center px-4 pt-20')}>
      {!error ? (
        <PageState kind="loading" title={t('common.loading')} />
      ) : (
        <PageState
          kind="warning"
          titleAs="h1"
          title={t('login.providerFailed')}
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
