import { AuthProvider, useAuth, type SignInStep } from '@jabiz/client'
import {
  AppearanceProvider,
  Button,
  MailLinkPage,
  MailUnsubscribe,
  OfflineBanner,
  OidcCallback,
  PageState,
  PasswordResetRequest,
  PwaUpdatePrompt,
  SignInForm,
  SignUpForm,
  StepUpProvider,
  ThemeToggle,
  VerifyEmailBanner,
  cn,
} from '@jabiz/ui'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useEffect, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { PORTAL } from './messages'
import { ACCOUNT_PATH, AccountPage, rememberLinked } from './pages/AccountPage'
import { OrdersPage } from './pages/OrdersPage'
import { PlaceOrderPage } from './pages/PlaceOrderPage'
import { hrefOf, navigate, navigation, useLocation } from './router'

/** Where a signed-in customer starts. */
const HOME = '/orders'

/** The second step of a sign-in through an identity provider, handed from the callback to the sign-in page. */
let pendingStep: SignInStep | undefined

const MAIL_PAGES = new Set(['/auth/verify-email', '/auth/join', '/auth/password-reset'])

/**
 * The unsubscribe page of the portal's notifications (decision D52): `jabiz.mail.unsubscribe-paths.portal` is
 * `mail/unsubscribe`, so the links of `commerce.order-shipped` lead here, not into the administration.
 */
export const UNSUBSCRIBE_PATH = '/mail/unsubscribe'

/** Goes to `path` once rendered (a redirect). */
function Redirect({ to }: { to: string }) {
  useEffect(() => navigate(to, { replace: true }), [to])
  return null
}

function NavLink({ to, current, children }: { to: string; current: string; children: ReactNode }) {
  return (
    <a
      href={hrefOf(to)}
      aria-current={current === to ? 'page' : undefined}
      className={cn('rounded-md px-3 py-2 text-sm font-medium hover:bg-accent', current === to && 'bg-accent')}
      onClick={(event) => {
        if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
        event.preventDefault()
        navigate(to)
      }}
    >
      {children}
    </a>
  )
}

/** The pages of a signed-in customer, with the portal's header and the banners of the account and the app. */
function Shell({ path }: { path: string }) {
  const { t } = useTranslation(PORTAL)
  const { signOut, displayName } = useAuth()
  let page: ReactNode
  if (path === '/orders') page = <OrdersPage />
  else if (path === '/order') page = <PlaceOrderPage />
  else if (path === ACCOUNT_PATH) page = <AccountPage />
  else page = <PageState kind="notFound" titleAs="h1" description={t('notFound')} />
  return (
    <div className="flex min-h-full flex-col">
      <header className="border-b">
        <div className="mx-auto flex max-w-4xl flex-wrap items-center gap-2 px-4 py-3">
          <span className="mr-4 font-semibold">{t('brand')}</span>
          <nav aria-label={t('nav.label')} className="flex flex-wrap gap-1">
            <NavLink to="/orders" current={path}>{t('nav.orders')}</NavLink>
            <NavLink to="/order" current={path}>{t('nav.order')}</NavLink>
            <NavLink to="/account" current={path}>{t('nav.account')}</NavLink>
          </nav>
          <span className="text-muted-foreground ml-auto text-sm" data-testid="portal-user">{displayName}</span>
          <ThemeToggle />
          <Button
            variant="outline"
            size="sm"
            data-testid="portal-sign-out"
            onClick={() => void signOut().then(() => navigate('/login', { replace: true }))}
          >
            {t('nav.signOut')}
          </Button>
        </div>
      </header>
      <main className="mx-auto flex w-full max-w-4xl flex-col gap-4 px-4 py-6">
        <OfflineBanner />
        <PwaUpdatePrompt />
        {path !== '/order' && <VerifyEmailBanner />}
        {page}
      </main>
    </div>
  )
}

/** The portal's pages by path: the sign-in pages for everyone, the rest for a signed-in customer only. */
function Routes() {
  const { t } = useTranslation(PORTAL)
  const { ready, signedIn } = useAuth()
  // The blocks of mailed links and of the identity provider's return read the address themselves, once: the token
  // and the code are removed from it right away.
  const { path, search } = useLocation()
  const brand = t('brand')
  const frame = (content: ReactNode) => (
    <div className="flex min-h-full flex-col">
      <div className="mx-auto w-full max-w-md px-4 pt-4">
        <OfflineBanner />
        <PwaUpdatePrompt />
      </div>
      {content}
    </div>
  )

  if (path === UNSUBSCRIBE_PATH) return frame(<MailUnsubscribe brand={brand} search={search} />)
  if (MAIL_PAGES.has(path)) {
    return frame(<MailLinkPage {...navigation} page={path.slice('/auth/'.length)} brand={brand}
      signInPath="/login" passwordResetPath="/password-reset" />)
  }
  if (path === '/login/oidc') {
    return frame(<OidcCallback fallbackPath={HOME}
      onSignedIn={(to) => navigate(to, { replace: true })}
      onLinked={(to, linked) => {
        rememberLinked(linked)
        navigate(to || ACCOUNT_PATH, { replace: true })
      }}
      onSecondStep={(step) => {
        pendingStep = step
        navigate('/login', { replace: true })
      }}
      onBack={() => navigate('/login', { replace: true })} />)
  }
  if (!ready) return <PageState kind="loading" />
  if (path === '/password-reset') {
    return frame(<PasswordResetRequest {...navigation} brand={brand} signInPath="/login" />)
  }
  // The form, then "look in your mailbox" with a new mail on request.
  if (path === '/signup') return frame(<SignUpForm {...navigation} brand={brand} signInPath="/login" />)
  if (path === '/login') {
    if (signedIn) return <Redirect to={HOME} />
    return frame(<SignInForm {...navigation} brand={brand} returnTo={HOME} initialStep={pendingStep}
      onSignedIn={(to) => {
        pendingStep = undefined
        navigate(to, { replace: true })
      }} passwordResetPath="/password-reset"
      signUpPath="/signup" />)
  }
  if (!signedIn) return <Redirect to="/login" />
  if (path === '/' || path === '') return <Redirect to={HOME} />
  return (
    <StepUpProvider {...navigation} securityPath="/account">
      <Shell path={path} />
    </StepUpProvider>
  )
}

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } })

/** The customer portal: an application's own SPA on the platform's packages (docs/guide/app-spa.md). */
export default function App() {
  return (
    <AppearanceProvider>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <div className="bg-background text-foreground min-h-full">
            <Routes />
          </div>
        </AuthProvider>
      </QueryClientProvider>
    </AppearanceProvider>
  )
}
