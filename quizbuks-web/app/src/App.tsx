import { AuthProvider, useAuth, type SignInStep } from '@jabiz/client'
import {
  AppearanceProvider,
  Button,
  MailLinkPage,
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
import { QueryClientProvider, useQuery } from '@tanstack/react-query'
import { History, House, UserRound, Wallet, type LucideIcon } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from './messages'
import { ACCOUNT_PATH, AccountPage, rememberLinked } from './pages/AccountPage'
import { FeedPage } from './pages/FeedPage'
import { HistoryPage } from './pages/HistoryPage'
import { PlayPage } from './pages/PlayPage'
import { PublicationPage } from './pages/PublicationPage'
import { PROFILE_QUERY } from './pages/ProfileForm'
import { ResultPage } from './pages/ResultPage'
import { WelcomePage } from './pages/WelcomePage'
import { AboutPage } from './pages/AboutPage'
import { forgetAttempts } from './play/storage'
import { itemPage } from './routes'
import { readMyProfile } from './qb/api'
import { ConsentGate, LegalLink, newQbQueryClient } from './qb/ConsentGate'
import { HelpPage } from './qb/HelpPage'
import { LEGAL } from './qb/ids'
import { legalKindOf } from './qb/legal'
import { LegalLinks } from './qb/LegalLinks'
import { LegalPage } from './qb/LegalPage'
import { QB } from './qb/messages'
import { Link, navigate, navigation, Redirect, useLocation } from './qb/router'
import { useSessionLock } from './qb/session'
import { PAYOUT_PATH, WALLET_PATH } from './wallet/api'
import { PayoutPage } from './wallet/PayoutPage'
import { WalletPage } from './wallet/WalletPage'

/** Where a signed-in taker starts. */
const HOME = '/'
const WELCOME = '/welcome'
const HISTORY = '/history'
const HELP = '/help'
const ABOUT = '/about'

/** The second step of a sign-in through an identity provider, handed from the callback to the sign-in page. */
let pendingStep: SignInStep | undefined

const MAIL_PAGES = new Set(['/auth/verify-email', '/auth/join', '/auth/password-reset'])

/** The bottom navigation's entries (docs/quizbuks/02-design.md section 7.1). */
const NAV: { path: string; key: string; icon: LucideIcon }[] = [
  { path: HOME, key: 'nav.home', icon: House },
  { path: HISTORY, key: 'nav.history', icon: History },
  { path: WALLET_PATH, key: 'wallet.nav', icon: Wallet },
  { path: ACCOUNT_PATH, key: 'nav.account', icon: UserRound },
]

/** Back to the sign-in page, once signed out. */
function toSignIn() {
  navigate('/login', { replace: true })
}

/** The app's header: the name, the appearance and signing out. */
function Header() {
  const { t } = useTranslation(QB)
  const { signOut } = useAuth()
  return (
    <header className="bg-background sticky top-0 z-10 border-b">
      <div className="mx-auto flex max-w-md items-center gap-2 px-4 py-2">
        <span className="mr-auto font-semibold">{t('brand')}</span>
        <ThemeToggle />
        <Button variant="ghost" size="sm" data-testid="sign-out"
          onClick={() => void signOut().then(() => {
            forgetAttempts()
            toSignIn()
          })}>
          {t('signOut')}
        </Button>
      </div>
    </header>
  )
}

/** The bottom navigation of a phone app, kept on screen; the current page is marked for assistive technology. */
function BottomNav({ path }: { path: string }) {
  const { t } = useTranslation(APP)
  // A publication's page belongs to home, an attempt's to the history, the payout account to the wallet.
  const item = itemPage(path)
  const section = item ? (item.page === 'p' ? HOME : HISTORY) : path === PAYOUT_PATH ? WALLET_PATH : path
  return (
    <nav aria-label={t('nav.label')}
      className="bg-background fixed inset-x-0 bottom-0 z-10 border-t pb-[env(safe-area-inset-bottom)]">
      <ul className="mx-auto flex max-w-md">
        {NAV.map((item) => {
          const Icon = item.icon
          const current = section === item.path
          return (
            <li key={item.path} className="flex-1">
              <Link to={item.path} current={current} data-testid={`nav-${item.path.replace('/', '') || 'home'}`}
                className={cn('flex flex-col items-center gap-0.5 py-2 text-xs',
                  current ? 'text-primary font-medium' : 'text-muted-foreground hover:text-foreground')}>
                <Icon aria-hidden className="size-5" />
                {t(item.key)}
              </Link>
            </li>
          )
        })}
      </ul>
    </nav>
  )
}

/**
 * The pages of a signed-in taker. A verified taker without a complete profile (no country yet) completes it first
 * (`/welcome`, registration's second step); the account page stays open meanwhile. A taker whose address is not
 * verified yet browses (the app's entry lets them sign in, D-Q2-1) with the banner offering the mail again: the
 * profile cannot be saved before verifying, so they are not held at the welcome page, which stays reachable.
 */
function SignedIn({ path }: { path: string }) {
  const { t } = useTranslation(QB)
  // The app's entry never locks for idleness (idle-timeout 0); the hook follows whatever the server says.
  useSessionLock()
  const { userId, emailVerified, displayName } = useAuth()
  const profile = useQuery({ queryKey: PROFILE_QUERY, queryFn: readMyProfile })
  // The pages start from who is signed in (the sign-up name prefills the profile): wait until the server said so.
  if (profile.isPending || !userId) return <PageState kind="loading" />
  if (profile.isError) {
    return <PageState kind="error" description={t('failed')}
      action={<Button variant="outline" onClick={() => void profile.refetch()}>{t('retry')}</Button>} />
  }
  const complete = profile.data?.countryCode ? profile.data : null
  if (!complete && emailVerified && path !== WELCOME && path !== ACCOUNT_PATH) return <Redirect to={WELCOME} />
  if (complete && path === WELCOME) return <Redirect to={HOME} />

  const country = complete?.countryCode ?? null
  const item = itemPage(path)
  let page: ReactNode
  if (path === WELCOME) page = <WelcomePage profile={profile.data} />
  else if (path === HOME) page = <FeedPage name={complete?.displayName || displayName || ''} country={country} />
  else if (path === HISTORY) page = <HistoryPage />
  else if (path === WALLET_PATH) page = <WalletPage />
  else if (path === PAYOUT_PATH) page = <PayoutPage />
  else if (path === ACCOUNT_PATH) page = <AccountPage />
  else if (item?.page === 'p') page = <PublicationPage key={item.id} publicationId={item.id} country={country} />
  else if (item?.page === 'play') page = <PlayPage key={item.id} attemptId={item.id} />
  else if (item?.page === 'result') page = <ResultPage key={item.id} attemptId={item.id} country={country} />
  else page = <PageState kind="notFound" titleAs="h1" description={t('notFound')} />
  // The navigation is the app's; the welcome step stands alone, and answering keeps the screen to the question.
  const nav = path !== WELCOME && item?.page !== 'play'
  const pages = (
    <div className="flex min-h-full flex-col">
      <Header />
      <main className={cn('mx-auto flex w-full max-w-md flex-col gap-4 px-4 py-4', nav && 'pb-24')}>
        <OfflineBanner />
        <PwaUpdatePrompt />
        <VerifyEmailBanner />
        {page}
      </main>
      {nav && <BottomNav path={path} />}
    </div>
  )
  // Whoever completed the profile before the texts in effect accepts them first (D-Q7-5); registration's second
  // step accepts them with the profile.
  return <ConsentGate kinds={LEGAL.appKinds} enabled={!!complete} onSignOut={() => {
    forgetAttempts()
    toSignIn()
  }}>{pages}</ConsentGate>
}

/**
 * The help centre, the legal texts and the About page (Q7b): readable without signing in, in a frame of their own
 * with the way back (to the app, or to signing in).
 */
function PublicPage({ path, signedIn }: { path: string; signedIn: boolean }) {
  const { t } = useTranslation(QB)
  const { t: tApp } = useTranslation(APP)
  const kind = legalKindOf(path)
  const page = path === HELP ? <HelpPage audience="APP" />
    : path === ABOUT ? <AboutPage />
    : kind ? <LegalPage key={kind} kind={kind} />
    : null
  return (
    <div className="flex min-h-full flex-col">
      <header className="bg-background sticky top-0 z-10 border-b">
        <div className="mx-auto flex max-w-md items-center gap-2 px-4 py-2">
          <span className="mr-auto font-semibold">{t('brand')}</span>
          <ThemeToggle />
          <Link to={signedIn ? HOME : '/login'} className="text-primary text-sm underline" data-testid="public-back">
            {signedIn ? tApp('public.back') : tApp('public.signIn')}
          </Link>
        </div>
      </header>
      <main className="mx-auto flex w-full max-w-md flex-col gap-6 px-4 py-4">
        <OfflineBanner />
        {page}
        <LegalLinks kinds={LEGAL.appKinds} className="border-t pt-4" />
      </main>
    </div>
  )
}

/** Whether a path is one of the pages anybody may read. */
function isPublic(path: string): boolean {
  return path === HELP || path === ABOUT || legalKindOf(path) !== null
}

/** The app's pages by path: the sign-in pages for everyone, the rest for a signed-in taker only. */
function Routes() {
  const { t } = useTranslation(QB)
  const { t: tApp } = useTranslation(APP)
  const { ready, signedIn } = useAuth()
  const { path } = useLocation()
  const brand = t('brand')
  const frame = (content: ReactNode) => (
    <div className="flex min-h-full flex-col">
      <div className="mx-auto w-full max-w-md px-4 pt-4 empty:hidden">
        <OfflineBanner />
        <PwaUpdatePrompt />
      </div>
      {content}
    </div>
  )

  // The blocks of mailed links and of the identity provider's return read the address themselves, once: the token
  // and the code are removed from it right away.
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
  if (isPublic(path)) return <PublicPage path={path} signedIn={signedIn} />
  // Signed in, the pages of signing up and asking for a new password lead home.
  if (signedIn && (path === '/signup' || path === '/password-reset')) return <Redirect to={HOME} />
  if (path === '/password-reset') {
    return frame(<PasswordResetRequest {...navigation} brand={brand} signInPath="/login" />)
  }
  // The form, then "look in your mailbox" with a new mail on request; the profile follows the first sign-in.
  // The texts are accepted on the welcome step (Q7-3); signing up says where to read them.
  if (path === '/signup') {
    return frame(<>
      <SignUpForm {...navigation} brand={brand} signInPath="/login" />
      <p className="text-muted-foreground mx-auto w-full max-w-md px-4 pb-6 text-center text-sm"
        data-testid="signup-terms">
        {tApp('public.signUpTerms')}{' '}
        <LegalLink kind="TERMS_APP" />{' · '}<LegalLink kind="PRIVACY" />
      </p>
    </>)
  }
  if (path === '/login') {
    if (signedIn) return <Redirect to={HOME} />
    return frame(<SignInForm {...navigation} brand={brand} returnTo={HOME} initialStep={pendingStep}
      onSignedIn={(to) => {
        pendingStep = undefined
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

/** The takers' app (docs/quizbuks/plans/QP-pages.md): an application's own SPA and PWA on the platform's packages. */
export default function App() {
  const [queryClient] = useState(newQbQueryClient)
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
