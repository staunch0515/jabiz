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
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { House, UserRound, type LucideIcon } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from './messages'
import { ACCOUNT_PATH, AccountPage, rememberLinked } from './pages/AccountPage'
import { HomePage } from './pages/HomePage'
import { PROFILE_QUERY } from './pages/ProfileForm'
import { WelcomePage } from './pages/WelcomePage'
import { readMyProfile } from './qb/api'
import { QB } from './qb/messages'
import { Link, navigate, navigation, Redirect, useLocation } from './qb/router'
import { useSessionLock } from './qb/session'

/** Where a signed-in taker starts. */
const HOME = '/'
const WELCOME = '/welcome'

/** The second step of a sign-in through an identity provider, handed from the callback to the sign-in page. */
let pendingStep: SignInStep | undefined

const MAIL_PAGES = new Set(['/auth/verify-email', '/auth/join', '/auth/password-reset'])

/** The bottom navigation's entries (docs/quizbuks/02-design.md section 7.1; history and wallet come with QP2, QP4). */
const NAV: { path: string; key: string; icon: LucideIcon }[] = [
  { path: HOME, key: 'nav.home', icon: House },
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
          onClick={() => void signOut().then(toSignIn)}>
          {t('signOut')}
        </Button>
      </div>
    </header>
  )
}

/** The bottom navigation of a phone app, kept on screen; the current page is marked for assistive technology. */
function BottomNav({ path }: { path: string }) {
  const { t } = useTranslation(APP)
  return (
    <nav aria-label={t('nav.label')}
      className="bg-background fixed inset-x-0 bottom-0 z-10 border-t pb-[env(safe-area-inset-bottom)]">
      <ul className="mx-auto flex max-w-md">
        {NAV.map((item) => {
          const Icon = item.icon
          const current = path === item.path
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
 * The pages of a signed-in taker. Without a complete profile (no country yet) the taker completes it first
 * (`/welcome`, registration's second step); the account page stays open meanwhile.
 */
function SignedIn({ path }: { path: string }) {
  const { t } = useTranslation(QB)
  // The app's entry never locks for idleness (idle-timeout 0); the hook follows whatever the server says.
  useSessionLock()
  const profile = useQuery({ queryKey: PROFILE_QUERY, queryFn: readMyProfile })
  if (profile.isPending) return <PageState kind="loading" />
  if (profile.isError) {
    return <PageState kind="error" description={t('failed')}
      action={<Button variant="outline" onClick={() => void profile.refetch()}>{t('retry')}</Button>} />
  }
  const complete = profile.data?.countryCode ? profile.data : null
  if (!complete && path !== WELCOME && path !== ACCOUNT_PATH) return <Redirect to={WELCOME} />
  if (complete && path === WELCOME) return <Redirect to={HOME} />

  let page: ReactNode
  if (path === WELCOME) page = <WelcomePage profile={profile.data} />
  else if (path === HOME && complete) page = <HomePage profile={complete} />
  else if (path === ACCOUNT_PATH) page = <AccountPage />
  else page = <PageState kind="notFound" titleAs="h1" description={t('notFound')} />
  const nav = complete !== null
  return (
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
}

/** The app's pages by path: the sign-in pages for everyone, the rest for a signed-in taker only. */
function Routes() {
  const { t } = useTranslation(QB)
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
  if (path === '/password-reset') {
    return frame(<PasswordResetRequest {...navigation} brand={brand} signInPath="/login" />)
  }
  // The form, then "look in your mailbox" with a new mail on request; the profile follows the first sign-in.
  if (path === '/signup') return frame(<SignUpForm {...navigation} brand={brand} signInPath="/login" />)
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

function newQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } })
}

/** The takers' app (docs/quizbuks/plans/QP-pages.md): an application's own SPA and PWA on the platform's packages. */
export default function App() {
  const [queryClient] = useState(newQueryClient)
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
