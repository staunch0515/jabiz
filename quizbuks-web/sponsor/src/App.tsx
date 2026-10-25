import { AuthProvider, useAuth, type SignInStep } from '@jabiz/client'
import {
  AppearanceProvider,
  AppShell,
  Button,
  MailLinkPage,
  MailUnsubscribe,
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
import { QueryClientProvider, useQuery } from '@tanstack/react-query'
import { CircleHelp, FileQuestion, LayoutDashboard, Megaphone, MessagesSquare, ReceiptText, Store, UserRound,
  Wallet,
  HandCoins,
  Gift,
} from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { analyticsIdOf } from './analytics/api'
import { PublicationAnalyticsPage } from './analytics/PublicationAnalyticsPage'
import { QuizEditorPage } from './content/QuizEditorPage'
import { QuizListPage } from './content/QuizListPage'
import { quizIdOf, QUIZZES_PATH } from './content/paths'
import { SPONSOR } from './messages'
import { ACCOUNT_PATH, AccountPage } from './pages/AccountPage'
import { DashboardPage } from './pages/DashboardPage'
import { readRewardSettings, REWARD_SETTINGS_QUERY } from './publishing/api'
import { PublicationListPage } from './publishing/PublicationListPage'
import { PublicationPage } from './publishing/PublicationPage'
import { CASH_REWARDS_PATH, CashRewardsPage } from './publishing/CashRewardsPage'
import { REDEEM_PATH, RedeemPage } from './publishing/RedeemPage'
import { publicationIdOf, PUBLICATIONS_PATH } from './publishing/paths'
import { OnboardingPage, useMySponsor } from './pages/OnboardingPage'
import { MESSAGES_PATH } from './notices/api'
import { MessagesPage } from './notices/MessagesPage'
import { ConsentGate, LegalLink, newQbQueryClient } from './qb/ConsentGate'
import { Wordmark } from './qb/Illustrations'
import { HelpPage } from './qb/HelpPage'
import { LEGAL } from './qb/ids'
import { legalKindOf, type LegalKind } from './qb/legal'
import { LegalLinks } from './qb/LegalLinks'
import { LegalPage } from './qb/LegalPage'
import { QB } from './qb/messages'
import { hrefOf, navigate, navigation, Redirect, useLocation } from './qb/router'
import { useSessionLock } from './qb/session'
import { TRANSACTIONS_PATH } from './transactions/api'
import { TransactionsPage } from './transactions/TransactionsPage'
import { BankTopUpPage } from './wallet/BankTopUpPage'
import { bankTopUpIdOf, topUpIdOf, WALLET_PATH } from './wallet/api'
import { TopUpPage } from './wallet/TopUpPage'
import { WalletPage } from './wallet/WalletPage'

const DASHBOARD = '/dashboard'
const ONBOARDING = '/onboarding'
const HELP = '/help'

/** The texts the console links to (the sponsors' terms, the privacy policy and the commerce notice). */
const LINKED: readonly LegalKind[] = [...LEGAL.sponsorKinds, 'COMMERCE_NOTICE']

/** The help centre and the legal texts (Q7b): anybody reads them, signed in or not. */
function publicPage(path: string): ReactNode | null {
  const kind = legalKindOf(path)
  if (path === HELP) return <HelpPage audience="SPONSOR" />
  if (kind) return <LegalPage key={kind} kind={kind} />
  return null
}

/** The second step of a sign-in through an identity provider, handed from the callback to the sign-in page. */
let pendingStep: SignInStep | undefined
/** Set when the session was locked for idleness, for the sign-in page to say so once. */
let lockedIdle = false

const MAIL_PAGES = new Set(['/auth/verify-email', '/auth/join', '/auth/password-reset'])

/**
 * The unsubscribe page of the console's notification mail (platform decision D52 item 3):
 * `jabiz.mail.unsubscribe-paths.sponsor` is `mail/unsubscribe`, under the entry's app-path, so the links of the
 * mails declared for this entry lead here and sponsors never need the administration. Open without signing in:
 * the link's token is the credential.
 */
const UNSUBSCRIBE_PATH = '/mail/unsubscribe'

/** Locked after the entry's idle time (30 minutes): about to be signed out; the sign-in page says why. */
function lockIdle() {
  lockedIdle = true
}

function toSignIn() {
  navigate('/login', { replace: true })
}

/**
 * The pages of a signed-in sponsor in the console's frame (`AppShell`): the dashboard, the quizzes (editing content
 * needs no approval, publishing does), the publications, the wallet and the transactions, the message centre (Q7c),
 * the sponsor profile (onboarding) and the account.
 * The home page leads to the onboarding until the sponsor is approved.
 */
function SignedIn({ path }: { path: string }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const { displayName, signOut, userId } = useAuth()
  useSessionLock(lockIdle)
  const sponsor = useMySponsor()
  // Q28: a sponsor who may not pay in the platform's Kudos has no use for the wallet and its top-ups; the server
  // decides what they may publish, this only hides the pages. Until the answer is in (or without one) they show.
  const rewards = useQuery({ queryKey: REWARD_SETTINGS_QUERY, queryFn: readRewardSettings, staleTime: 60_000 })
  const kudos = rewards.data ? rewards.data.methods.includes('PLATFORM') : true
  // The transactions page starts from its address's filters: another address (the nav, back, forward) starts it anew.
  const { search } = useLocation()
  // The pages start from who is signed in (the sign-up name prefills the onboarding): wait until the server said so.
  if (!userId) return <PageState kind="loading" />

  if (path === '/' || path === '') {
    if (sponsor.isPending) return <PageState kind="loading" />
    return <Redirect to={sponsor.data?.reviewStatus === 'APPROVED' ? DASHBOARD : ONBOARDING} />
  }

  let page: ReactNode
  const quizId = quizIdOf(path)
  const topUpId = topUpIdOf(path)
  const bankTopUpId = bankTopUpIdOf(path)
  const open = publicPage(path)
  if (open) page = open
  else if (path === DASHBOARD) page = <DashboardPage />
  else if (path === QUIZZES_PATH) page = <QuizListPage />
  else if (quizId) page = <QuizEditorPage key={quizId} quizId={quizId} />
  else if (path === PUBLICATIONS_PATH) page = <PublicationListPage />
  else if (path === CASH_REWARDS_PATH) page = <CashRewardsPage />
  else if (path === REDEEM_PATH) page = <RedeemPage />
  else if (analyticsIdOf(path)) page = <PublicationAnalyticsPage key={path} publicationId={analyticsIdOf(path)!} />
  else if (publicationIdOf(path)) page = <PublicationPage key={path} publicationId={publicationIdOf(path)!} />
  else if (path === ONBOARDING) page = <OnboardingPage />
  else if (path === WALLET_PATH && kudos) page = <WalletPage />
  else if (topUpId && kudos) page = <TopUpPage key={topUpId} topUpId={topUpId} />
  else if (bankTopUpId && kudos) page = <BankTopUpPage key={bankTopUpId} topUpId={bankTopUpId} />
  else if (path === TRANSACTIONS_PATH) page = <TransactionsPage key={search} />
  else if (path === MESSAGES_PATH) page = <MessagesPage />
  else if (path === ACCOUNT_PATH) page = <AccountPage />
  else page = <PageState kind="notFound" titleAs="h1" description={tQb('notFound')} />

  const items: ShellMenuItem[] = [
    { key: 'dashboard', label: t('nav.dashboard'), path: DASHBOARD, icon: LayoutDashboard },
    { key: 'quizzes', label: t('nav.quizzes'), path: QUIZZES_PATH, icon: FileQuestion },
    { key: 'publications', label: t('publishing.nav'), path: PUBLICATIONS_PATH, icon: Megaphone },
    { key: 'cash', label: t('publishing.cash.nav'), path: CASH_REWARDS_PATH, icon: HandCoins },
    { key: 'redeem', label: t('publishing.redeem.nav'), path: REDEEM_PATH, icon: Gift },
    { key: 'onboarding', label: t('nav.onboarding'), path: ONBOARDING, icon: Store },
    ...(kudos ? [{ key: 'wallet', label: t('wallet.nav'), path: WALLET_PATH, icon: Wallet }] : []),
    { key: 'transactions', label: t('transactions.nav'), path: TRANSACTIONS_PATH, icon: ReceiptText },
    { key: 'messages', label: t('notices.nav'), path: MESSAGES_PATH, icon: MessagesSquare },
    { key: 'account', label: t('nav.account'), path: ACCOUNT_PATH, icon: UserRound },
    { key: 'help', label: t('nav.help'), path: HELP, icon: CircleHelp },
  ]
  const shell = (
    <AppShell
      brand={<Wordmark name={t('brand')} compact className="px-2 py-1" />}
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
        <LegalLinks kinds={LINKED} className="mt-8 border-t pt-4" />
      </div>
    </AppShell>
  )
  // A sponsor who applied before the texts in effect accepts them first (D-Q7-5); applying accepts them with the
  // application. The texts and the help stay readable (the prompt links to them).
  return <ConsentGate kinds={LEGAL.sponsorKinds} enabled={!open && !!sponsor.data} onSignOut={toSignIn}>
    {shell}
  </ConsentGate>
}

/** The help centre and the texts before signing in, with the way to sign in. */
function PublicPage({ page }: { page: ReactNode }) {
  const { t } = useTranslation(SPONSOR)
  return (
    <div className="flex min-h-full flex-col">
      <header className="bg-background border-b">
        <div className="mx-auto flex max-w-3xl items-center gap-2 px-4 py-2">
          <span className="mr-auto font-semibold">{t('brand')}</span>
          <ThemeToggle />
          <a href={hrefOf('/login')} className="text-primary text-sm underline" data-testid="public-back"
            onClick={(event) => {
              event.preventDefault()
              navigate('/login')
            }}>
            {t('public.signIn')}
          </a>
        </div>
      </header>
      <main className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-4">
        <OfflineBanner />
        {page}
        <LegalLinks kinds={LINKED} className="border-t pt-4" />
      </main>
    </div>
  )
}

/** The console's pages by path: the sign-in pages for everyone, the rest for a signed-in sponsor only. */
function Routes() {
  const { t } = useTranslation(SPONSOR)
  const { ready, signedIn } = useAuth()
  const { path, search } = useLocation()
  const brand = <Wordmark name={t('brand')} className="justify-center" />
  const frame = (content: ReactNode) => (
    <div className="flex min-h-full flex-col">
      <div className="mx-auto w-full max-w-md px-4 pt-4 empty:hidden">
        <OfflineBanner />
      </div>
      {content}
    </div>
  )

  // The blocks of mailed links read the address themselves, once: the token is removed from it right away.
  if (path === UNSUBSCRIBE_PATH) return frame(<MailUnsubscribe brand={brand} search={search} />)
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
  const open = signedIn ? null : publicPage(path)
  if (open) return <PublicPage page={open} />
  // Signed in, the pages of signing up and asking for a new password lead home.
  if (signedIn && (path === '/signup' || path === '/password-reset')) return <Redirect to="/" />
  if (path === '/password-reset') {
    return frame(<PasswordResetRequest {...navigation} brand={brand} signInPath="/login" />)
  }
  // The form, then "look in your mailbox"; the console takes verified addresses only, so the onboarding follows the
  // verification and the first sign-in.
  // The sponsors' terms are accepted with the application (Q7b); signing up says where to read them.
  if (path === '/signup') {
    return frame(<>
      <SignUpForm {...navigation} brand={brand} signInPath="/login" />
      <p className="text-muted-foreground mx-auto w-full max-w-md px-4 pb-6 text-center text-sm"
        data-testid="signup-terms">
        {t('public.signUpTerms')}{' '}
        <LegalLink kind="TERMS_SPONSOR" />{' · '}<LegalLink kind="PRIVACY" />
      </p>
    </>)
  }
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

/** The sponsor console (docs/quizbuks/plans/QP-pages.md): an application's own SPA on the platform's packages. */
export default function App() {
  const [queryClient] = useState(newQbQueryClient)
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
