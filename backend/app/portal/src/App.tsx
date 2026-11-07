import { AuthProvider, useAuth } from '@jabiz/client'
import {
  AppearanceProvider,
  AppShell,
  BottomBar,
  Button,
  EntryLayoutProvider,
  GlobalCreate,
  MAIL_LINK_PAGES,
  MailLinkPage,
  MailUnsubscribe,
  OfflineBanner,
  OidcCallback,
  useEntryNotices,
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

/** Every page of a mailed link, each at `/auth/<page>`. */
const MAIL_PAGES = new Set(MAIL_LINK_PAGES.map((page) => `/auth/${page}`))

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

/**
 * The pages before a session in the platform's entry pages' template (phase 17zk): the portal's name, a column about
 * it on a wide screen (one line on a phone), the offline and new-version notices over the page.
 */
function EntryPages({ children }: { children: ReactNode }) {
  const { t } = useTranslation(PORTAL)
  const notices = useEntryNotices()
  return (
    <EntryLayoutProvider
      brand={t('brand')}
      intro={{
        sentence: t('entry.sentence'),
        points: [t('entry.point1'), t('entry.point2'), t('entry.point3')],
      }}
      notices={notices}
    >
      {children}
    </EntryLayoutProvider>
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

  if (path === UNSUBSCRIBE_PATH) return <EntryPages><MailUnsubscribe brand={brand} search={search} /></EntryPages>
  if (MAIL_PAGES.has(path)) {
    return (
      <EntryPages>
        <MailLinkPage {...navigation} page={path.slice('/auth/'.length)} brand={brand} signInPath="/login"
          passwordResetPath="/password-reset" />
      </EntryPages>
    )
  }
  if (path === '/login/oidc') {
    return (
      <EntryPages>
        <OidcCallback brand={brand} fallbackPath={HOME}
          onSignedIn={(to) => navigate(to, { replace: true })}
          onLinked={(to, linked) => {
            rememberLinked(linked)
            navigate(to || ACCOUNT_PATH, { replace: true })
          }}
          // The step and the page to return to are kept in this tab: the sign-in page continues with them (gap 2).
          onSecondStep={() => navigate('/login', { replace: true })}
          onBack={() => navigate('/login', { replace: true })} />
      </EntryPages>
    )
  }
  if (!ready) return <PageState kind="loading" />
  // Signed in already: the pages of signing in, signing up and asking for a new password lead home (gap 9).
  if (signedIn && (path === '/login' || path === '/signup' || path === '/password-reset')) return <Redirect to={HOME} />
  if (path === '/password-reset') {
    return <EntryPages><PasswordResetRequest {...navigation} brand={brand} signInPath="/login" /></EntryPages>
  }
  // Signing up and signing in are two tabs of one page; each keeps its own address (suggestion ④).
  if (path === '/signup') {
    return (
      <EntryPages>
        <SignUpForm {...navigation} brand={brand} signInPath="/login" signUpPath="/signup" tabs
          oidc={{ returnTo: HOME }} note={t('entry.terms')} />
      </EntryPages>
    )
  }
  if (path === '/login') {
    return (
      <EntryPages>
        <SignInForm {...navigation} brand={brand} returnTo={HOME}
          onSignedIn={(to) => navigate(to, { replace: true })} passwordResetPath="/password-reset"
          signInPath="/login" signUpPath="/signup" tabs emailLink passkey userNameLabel="email" />
      </EntryPages>
    )
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
