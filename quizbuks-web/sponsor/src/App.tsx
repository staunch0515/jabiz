import { AuthProvider, useAuth, type SignInStep } from '@jabiz/client'
import {
  AppearanceProvider,
  AppShell,
  Button,
  MailLinkPage,
  OfflineBanner,
  OidcCallback,
  PageState,
  PasswordResetRequest,
  ShellNav,
  SignInForm,
  SignUpForm,
  StepUpProvider,
  ThemeToggle,
  Toaster,
  type ShellMenuItem,
} from '@jabiz/ui'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { FileQuestion, LayoutDashboard, Store, UserRound, Wallet } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { QuizEditorPage } from './content/QuizEditorPage'
import { QuizListPage } from './content/QuizListPage'
import { quizIdOf, QUIZZES_PATH } from './content/paths'
import { SPONSOR } from './messages'
import { ACCOUNT_PATH, AccountPage } from './pages/AccountPage'
import { DashboardPage } from './pages/DashboardPage'
import { OnboardingPage, useMySponsor } from './pages/OnboardingPage'
import { QB } from './qb/messages'
import { hrefOf, navigate, navigation, Redirect, useLocation } from './qb/router'
import { useSessionLock } from './qb/session'
import { topUpIdOf, WALLET_PATH } from './wallet/api'
import { TopUpPage } from './wallet/TopUpPage'
import { WalletPage } from './wallet/WalletPage'

const DASHBOARD = '/dashboard'
const ONBOARDING = '/onboarding'

/** The second step of a sign-in through an identity provider, handed from the callback to the sign-in page. */
let pendingStep: SignInStep | undefined
/** Set when the session was locked for idleness, for the sign-in page to say so once. */
let lockedIdle = false

const MAIL_PAGES = new Set(['/auth/verify-email', '/auth/join', '/auth/password-reset'])

/** Locked after the entry's idle time (30 minutes): about to be signed out; the sign-in page says why. */
function lockIdle() {
  lockedIdle = true
}

function toSignIn() {
  navigate('/login', { replace: true })
}

/**
 * The pages of a signed-in sponsor in the console's frame (`AppShell`): the dashboard, the quizzes (QP3a; editing
 * content needs no approval, publishing will), the sponsor profile (onboarding) and the account; the publishing pages
 * come with QP3b, the wallet with QP4. The home page leads to the onboarding until the sponsor is approved.
 */
function SignedIn({ path }: { path: string }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const { displayName, signOut, userId } = useAuth()
  useSessionLock(lockIdle)
  const sponsor = useMySponsor()
  // The pages start from who is signed in (the sign-up name prefills the onboarding): wait until the server said so.
  if (!userId) return <PageState kind="loading" />

  if (path === '/' || path === '') {
    if (sponsor.isPending) return <PageState kind="loading" />
    return <Redirect to={sponsor.data?.reviewStatus === 'APPROVED' ? DASHBOARD : ONBOARDING} />
  }

  let page: ReactNode
  const quizId = quizIdOf(path)
  const topUpId = topUpIdOf(path)
  if (path === DASHBOARD) page = <DashboardPage />
  else if (path === QUIZZES_PATH) page = <QuizListPage />
  else if (quizId) page = <QuizEditorPage key={quizId} quizId={quizId} />
  else if (path === ONBOARDING) page = <OnboardingPage />
  else if (path === WALLET_PATH) page = <WalletPage />
  else if (topUpId) page = <TopUpPage key={topUpId} topUpId={topUpId} />
  else if (path === ACCOUNT_PATH) page = <AccountPage />
  else page = <PageState kind="notFound" titleAs="h1" description={tQb('notFound')} />

  const items: ShellMenuItem[] = [
    { key: 'dashboard', label: t('nav.dashboard'), path: DASHBOARD, icon: LayoutDashboard },
    { key: 'quizzes', label: t('nav.quizzes'), path: QUIZZES_PATH, icon: FileQuestion },
    { key: 'onboarding', label: t('nav.onboarding'), path: ONBOARDING, icon: Store },
    { key: 'wallet', label: t('wallet.nav'), path: WALLET_PATH, icon: Wallet },
    { key: 'account', label: t('nav.account'), path: ACCOUNT_PATH, icon: UserRound },
  ]
  return (
    <AppShell
      brand={<span className="px-2 py-1 font-semibold">{t('brand')}</span>}
      navigation={
        <ShellNav label={t('nav.label')} items={items} isActive={(p) => path === p || path.startsWith(`${p}/`)}
          renderLink={(item, props) => (
            <a href={hrefOf(item.path)} onClick={(event) => {
              props.onClick()
              if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
              event.preventDefault()
              navigate(item.path)
            }}>
              {props.children}
            </a>
          )} />
      }
      header={
        <div className="ml-auto flex items-center gap-2">
          <span className="text-muted-foreground hidden text-sm sm:inline" data-testid="sponsor-user">{displayName}</span>
          <ThemeToggle />
          <Button variant="outline" size="sm" data-testid="sign-out" onClick={() => void signOut().then(toSignIn)}>
            {tQb('signOut')}
          </Button>
        </div>
      }
    >
      <div className="flex flex-col gap-4">
        <OfflineBanner />
        {page}
      </div>
    </AppShell>
  )
}

/** The console's pages by path: the sign-in pages for everyone, the rest for a signed-in sponsor only. */
function Routes() {
  const { t } = useTranslation(SPONSOR)
  const { ready, signedIn } = useAuth()
  const { path } = useLocation()
  const brand = t('brand')
  const frame = (content: ReactNode) => (
    <div className="flex min-h-full flex-col">
      <div className="mx-auto w-full max-w-md px-4 pt-4 empty:hidden">
        <OfflineBanner />
      </div>
      {content}
    </div>
  )

  // The blocks of mailed links read the address themselves, once: the token is removed from it right away.
  if (MAIL_PAGES.has(path)) {
    return frame(<MailLinkPage {...navigation} page={path.slice('/auth/'.length)} brand={brand}
      signInPath="/login" passwordResetPath="/password-reset" />)
  }
  // No identity provider is offered to sponsors by default (Q-6); a deployment that configures one gets its return.
  if (path === '/login/oidc') {
    return frame(<OidcCallback fallbackPath="/"
      onSignedIn={(to) => navigate(to, { replace: true })}
      onLinked={(to) => navigate(to || ACCOUNT_PATH, { replace: true })}
      onSecondStep={(step) => {
        pendingStep = step
        navigate('/login', { replace: true })
      }}
      onBack={() => navigate('/login', { replace: true })} />)
  }
  if (!ready) return <PageState kind="loading" />
  // Signed in, the pages of signing up and asking for a new password lead home.
  if (signedIn && (path === '/signup' || path === '/password-reset')) return <Redirect to="/" />
  if (path === '/password-reset') {
    return frame(<PasswordResetRequest {...navigation} brand={brand} signInPath="/login" />)
  }
  // The form, then "look in your mailbox"; the console takes verified addresses only, so the onboarding follows the
  // verification and the first sign-in.
  if (path === '/signup') return frame(<SignUpForm {...navigation} brand={brand} signInPath="/login" />)
  if (path === '/login') {
    if (signedIn) return <Redirect to="/" />
    const notice = lockedIdle ? 'idle' : undefined
    return frame(<SignInForm {...navigation} brand={brand} returnTo="/" initialStep={pendingStep} notice={notice}
      onSignedIn={(to) => {
        pendingStep = undefined
        lockedIdle = false
        navigate(to, { replace: true })
      }} passwordResetPath="/password-reset" signUpPath="/signup" />)
  }
  if (!signedIn) return <Redirect to="/login" />
  return (
    <StepUpProvider {...navigation} securityPath={ACCOUNT_PATH}>
      <SignedIn path={path} />
    </StepUpProvider>
  )
}

function newQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } })
}

/** The sponsor console (docs/quizbuks/plans/QP-pages.md): an application's own SPA on the platform's packages. */
export default function App() {
  const [queryClient] = useState(newQueryClient)
  return (
    <AppearanceProvider>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <div className="bg-background text-foreground min-h-full">
            <Routes />
          </div>
          <Toaster />
        </AuthProvider>
      </QueryClientProvider>
    </AppearanceProvider>
  )
}
