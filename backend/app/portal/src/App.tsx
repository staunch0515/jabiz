import { AuthProvider, useAuth, type SignInStep } from '@jabiz/client'
import {
  AppearanceProvider,
  AppShell,
  BottomBar,
  Button,
  GlobalCreate,
  MailLinkPage,
  MailUnsubscribe,
  OfflineBanner,
  OidcCallback,
  PageState,
  PasswordResetRequest,
  PwaInstallBanner,
  PwaUpdatePrompt,
  ShellNav,
  SignInForm,
  SignUpForm,
  StepUpProvider,
  ThemeToggle,
  VerifyEmailBanner,
  type ShellMenuItem,
} from '@jabiz/ui'
import { ShoppingCartIcon } from 'lucide-react'
import { AREAS } from './areas'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useEffect, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { PORTAL } from './messages'
import { ACCOUNT_PATH, AccountPage, rememberLinked } from './pages/AccountPage'
import { OrdersPage } from './pages/OrdersPage'
import { PlaceOrderPage } from './pages/PlaceOrderPage'
import { navigate, navigation, portalLink, useLocation } from './router'

/** Where a signed-in customer starts. */
const HOME = '/orders'

/** The second step of a sign-in through an identity provider, handed from the callback to the sign-in page. */
let pendingStep: SignInStep | undefined

const MAIL_PAGES = new Set(['/auth/verify-email', '/auth/join', '/auth/password-reset', '/auth/email-link',
  '/auth/account-deletion', '/auth/invitation', '/auth/passkey-confirm'])

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

/**
 * The pages of a signed-in customer in the platform's application frame (phase 17zj): a sidebar that collapses to
 * icons, "+ New" (an order), and on a phone a bottom menu with the page's main action as a floating button; the
 * account's and the app's banners above each page.
 */
function Shell({ path }: { path: string }) {
  const { t } = useTranslation(PORTAL)
  const { signOut, displayName } = useAuth()
  let page: ReactNode
  if (path === '/orders') page = <OrdersPage />
  else if (path === '/order') page = <PlaceOrderPage />
  else if (path === ACCOUNT_PATH) page = <AccountPage />
  else page = <PageState kind="notFound" titleAs="h1" description={t('notFound')} />
  const items: ShellMenuItem[] = (['orders', 'order', 'account'] as const).map((key) => ({
    key, label: t(`nav.${key}`), path: AREAS[key].path, icon: AREAS[key].icon,
  }))
  const isActive = (to: string) => to === path
  return (
    <AppShell
      brand={<span className="truncate px-2 py-1 font-semibold">{t('brand')}</span>}
      collapsible="icon"
      routeKey={path}
      create={<GlobalCreate
        items={[{ key: 'order', label: t('nav.order'), icon: ShoppingCartIcon, path: '/order' }]}
        renderLink={(item, props) => portalLink(item.path, props)} />}
      navigation={<ShellNav label={t('nav.label')} items={items} isActive={isActive}
        renderLink={(item, props) => portalLink(item.path, props)} />}
      bottomBar={<BottomBar label={t('nav.quick')} isActive={isActive}
        items={items.map((item) => ({ key: item.key, label: item.label, path: item.path!, icon: item.icon! }))}
        renderLink={(item, props) => portalLink(item.path, props)} />}
      header={
        <div className="ml-auto flex items-center gap-2">
          <span className="text-muted-foreground text-sm" data-testid="portal-user">{displayName}</span>
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
      }
    >
      <main className="flex flex-col gap-4">
        <OfflineBanner />
        <PwaUpdatePrompt />
        {/* Signed-in customers only: an offer to install before signing in is mostly closed unread (16zj). */}
        <PwaInstallBanner appName={t('brand')} />
        {path !== '/order' && <VerifyEmailBanner />}
        {page}
      </main>
    </AppShell>
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
      signUpPath="/signup" emailLink passkey userNameLabel="email" />)
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
