import { AuthProvider, useAuth, type SignInStep } from '@jabiz/client'
import {
  AppearanceProvider,
  AppShell,
  Button,
  EntryLayoutProvider,
  EnvironmentBanner,
  MAIL_LINK_PAGES,
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
  VerifyEmailBanner,
  useEntryNotices,
  type ShellMenuGroup,
} from '@jabiz/ui'
import { QueryClientProvider, useQuery } from '@tanstack/react-query'
import { CircleHelp, FileQuestion, LayoutDashboard, Megaphone, MessagesSquare, Network, ReceiptText, Store, UserRound,
  Wallet,
  HandCoins,
  Gift,
  ChartColumn,
  Ticket,
  Handshake,
  Package,
  MessageCircle,
  ClipboardList,
  Library,
} from 'lucide-react'
import { useEffect, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { analyticsIdOf } from './analytics/api'
import { ASSETS_PATH } from './assets/api'
import { AssetsPage, tabOf } from './assets/AssetsPage'
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
import { DIGITAL_PRIZES_PATH, DigitalPrizesPage } from './publishing/DigitalPrizesPage'
import { REDEEM_PATH, RedeemPage } from './publishing/RedeemPage'
import { StaffPage } from './publishing/StaffPage'
import { MailServerPage } from './publishing/MailServerPage'
import { APP_BASE, publicationIdOf, PUBLICATIONS_PATH } from './publishing/paths'
import { PreviewPage } from './preview/PreviewPage'
import { TreePage } from './tree/TreePage'
import { TREE_PATH } from './tree/api'
import { previewOf } from './preview/paths'
import { rememberSource } from './trial/claim'
import TrialPage, { trialKeyOf } from './trial/TrialPage'
import { useMySponsor } from './profile/api'
import { BasicsPage } from './profile/BasicsPage'
import { OverviewPage } from './profile/OverviewPage'
import { LEGACY_PROFILE_PATHS, MOVED_PATHS, PROFILE_PATH, profilePath, profileSectionOf } from './profile/paths'
import { ProfileLayout } from './profile/ProfileLayout'
import { SponsorPagePage } from './page/SponsorPageEditor'
import { STATS_PATH, StatsPage } from './stats/StatsPage'
import { MESSAGES_PATH } from './notices/api'
import { MessagesPage } from './notices/MessagesPage'
import { conversationOf } from './conversations/api'
import { ConversationPage, useUnreadConversations } from './conversations/ConversationsPages'
import {
  BENEFIT_CONVERSATIONS_PATH,
  BENEFITS_PATH,
  idOf,
  NEW_PURCHASE,
  OFFERS_PATH,
  PURCHASES_PATH,
  sideOf,
} from './benefits/api'
import {
  BenefitConversationPage,
  BenefitConversationsPage,
  CatalogPage,
  OffersPage,
  PurchasePage,
  PurchasesPage,
} from './benefits/BenefitsPages'
import { ConsentGate, LegalLink, newQbQueryClient } from './qb/ConsentGate'
import { GrowthArt, Wordmark } from './qb/Illustrations'
import { HelpPage } from './qb/HelpPage'
import { LEGAL } from './qb/ids'
import { legalKindOf, type LegalKind } from './qb/legal'
import { EntryLegalLinks, LegalLinks } from './qb/LegalLinks'
import { LegalPage } from './qb/LegalPage'
import { QB } from './qb/messages'
import { hrefOf, navigate, navigation, Redirect, useLocation } from './qb/router'
import { useSessionLock } from './qb/session'
import { useSoundSetting } from './qb/SoundSettings'
import { TRANSACTIONS_PATH } from './transactions/api'
import { TransactionsPage } from './transactions/TransactionsPage'
import { BankTopUpPage } from './wallet/BankTopUpPage'
import { bankTopUpIdOf, topUpIdOf, WALLET_PATH } from './wallet/api'
import { TopUpPage } from './wallet/TopUpPage'
import { WalletPage } from './wallet/WalletPage'

const DASHBOARD = '/dashboard'
const HELP = '/help'

/** The texts the console links to (the sponsors' terms, the privacy policy and the commerce notice). */
const LINKED: readonly LegalKind[] = [...LEGAL.sponsorKinds, 'COMMERCE_NOTICE']

/** The help centre and the legal texts (Q7b): anybody reads them, signed in or not. */
function publicPage(path: string): ReactNode | null {
  const kind = legalKindOf(path)
  if (path === HELP) return <HelpPage audience="SPONSOR" />
  // Q48c: the trial quiz a prospecting mail links to.
  const trialKey = trialKeyOf(path)
  if (trialKey) return <TrialPage key={trialKey} trialKey={trialKey} />
  if (kind) return <LegalPage key={kind} kind={kind} />
  return null
}

/**
 * The second step of a sign-in through an identity provider, with the page to go to afterwards (gap 2), handed from
 * the callback to the sign-in page in memory; the sign-in page also resumes it from this tab's storage after a reload
 * (platform 17zk, `resumeStep`).
 */
let pendingStep: { step: SignInStep; to: string } | undefined
/** Set when the session was locked for idleness, for the sign-in page to say so once. */
let lockedIdle = false

/**
 * Every page of a mailed link the platform has (`MAIL_LINK_PAGES`, gap 3), each at `/auth/<page>`: the account's
 * deletion among them, for a deployment that turns it on for sponsors (QB_SPONSOR_ACCOUNT_DELETION).
 */
const MAIL_PAGES = new Set(MAIL_LINK_PAGES.map((page) => `/auth/${page}`))

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
  // Q57: the sound effects follow the sponsor's switch.
  useSoundSetting()
  const rewards = useQuery({ queryKey: REWARD_SETTINGS_QUERY, queryFn: readRewardSettings, staleTime: 60_000 })
  const kudos = rewards.data ? rewards.data.methods.includes('PLATFORM') : true
  // The transactions page starts from its address's filters: another address (the nav, back, forward) starts it anew.
  const { search } = useLocation()
  // Q82: the menu's badge of the takers' conversations.
  const unreadConversations = useUnreadConversations(!!userId)
  // The pages start from who is signed in (the sign-up name prefills the onboarding): wait until the server said so.
  if (!userId) return <PageState kind="loading" />

  if (path === '/' || path === '') {
    if (sponsor.isPending) return <PageState kind="loading" />
    // Q79: a sponsor not yet approved starts at the hub's basics (the application); approved, at the dashboard.
    return <Redirect to={sponsor.data?.reviewStatus === 'APPROVED' ? DASHBOARD : profilePath('basics')} />
  }

  let page: ReactNode
  const quizId = quizIdOf(path)
  const topUpId = topUpIdOf(path)
  const bankTopUpId = bankTopUpIdOf(path)
  const open = publicPage(path)
  const preview = previewOf(path)
  const profileSection = profileSectionOf(path)
  const legacyProfile = LEGACY_PROFILE_PATHS[path]
  // F-C1c: the products and the certifications are tabs of the asset library now.
  const moved = MOVED_PATHS[path]
  if (open) page = open
  else if (moved) page = <Redirect to={moved} />
  else if (preview) page = <PreviewPage key={path} target={preview} />
  else if (path === DASHBOARD) page = <DashboardPage />
  else if (path === QUIZZES_PATH) page = <QuizListPage />
  else if (quizId) page = <QuizEditorPage key={quizId} quizId={quizId} />
  else if (path === PUBLICATIONS_PATH) page = <PublicationListPage />
  else if (path === TREE_PATH) page = <TreePage key={search} />
  // F-C1: the asset library (in the content group until the new frame's "library" area, F-F3).
  else if (path === ASSETS_PATH) page = <AssetsPage tab={tabOf(search)} />
  else if (path === CASH_REWARDS_PATH) page = <CashRewardsPage />
  else if (path === DIGITAL_PRIZES_PATH) page = <DigitalPrizesPage />
  else if (path === REDEEM_PATH) page = <RedeemPage />
  // Q89a: sponsored benefits between sponsors (the catalog, one's own, inquiries and purchases, by side).
  else if (path === BENEFITS_PATH) page = <CatalogPage />
  else if (path === OFFERS_PATH) page = <OffersPage />
  else if (path === BENEFIT_CONVERSATIONS_PATH) page = <BenefitConversationsPage key={search} side={sideOf(search)} />
  else if (idOf(path, BENEFIT_CONVERSATIONS_PATH)) {
    page = <BenefitConversationPage key={path + search} conversationId={idOf(path, BENEFIT_CONVERSATIONS_PATH)!}
      side={sideOf(search)} />
  } else if (path === PURCHASES_PATH) page = <PurchasesPage key={search} side={sideOf(search)} />
  else if (idOf(path, PURCHASES_PATH)) {
    const purchaseId = idOf(path, PURCHASES_PATH)!
    page = purchaseId === NEW_PURCHASE
      ? <PurchasePage key={path + search} purchaseId={null} side="SPONSOR"
        offerId={new URLSearchParams(search).get('offer')} />
      : <PurchasePage key={path + search} purchaseId={purchaseId} side={sideOf(search)} />
  }
  else if (analyticsIdOf(path)) page = <PublicationAnalyticsPage key={path} publicationId={analyticsIdOf(path)!} />
  else if (publicationIdOf(path)) page = <PublicationPage key={path} publicationId={publicationIdOf(path)!} />
  // Q79: the sponsor hub and its sections; the pages' old addresses lead to them.
  else if (profileSection) {
    page = (
      <ProfileLayout section={profileSection}>
        {profileSection === 'overview' ? <OverviewPage />
          : profileSection === 'basics' ? <BasicsPage />
            : profileSection === 'page' ? <SponsorPagePage />
                : profileSection === 'staff' ? <StaffPage />
                  : <MailServerPage />}
      </ProfileLayout>
    )
  } else if (legacyProfile) page = <Redirect to={profilePath(legacyProfile)} />
  else if (path === STATS_PATH) page = <StatsPage />
  else if (path === WALLET_PATH && kudos) page = <WalletPage />
  else if (topUpId && kudos) page = <TopUpPage key={topUpId} topUpId={topUpId} />
  else if (bankTopUpId && kudos) page = <BankTopUpPage key={bankTopUpId} topUpId={bankTopUpId} />
  else if (path === TRANSACTIONS_PATH) page = <TransactionsPage key={search} />
  else if (path === MESSAGES_PATH) page = <MessagesPage tab={new URLSearchParams(search).get('tab')} />
  else if (conversationOf(path)) page = <ConversationPage key={path} conversationId={conversationOf(path)!} />
  else if (path === ACCOUNT_PATH) page = <AccountPage />
  else page = <PageState kind="notFound" titleAs="h1" description={tQb('notFound')} />

  // Q34b: the menu in sections by what they are for, with a rule between them (platform 16zi).
  const groups: ShellMenuGroup[] = [
    { key: 'overview', label: t('nav.groups.overview'), items: [
      { key: 'dashboard', label: t('nav.dashboard'), path: DASHBOARD, icon: LayoutDashboard },
    ] },
    { key: 'content', label: t('nav.groups.content'), items: [
      { key: 'quizzes', label: t('nav.quizzes'), path: QUIZZES_PATH, icon: FileQuestion },
      { key: 'publications', label: t('publishing.nav'), path: PUBLICATIONS_PATH, icon: Megaphone },
      // Q85: the publications as a tree, with the customers on it.
      { key: 'tree', label: t('tree.nav'), path: TREE_PATH, icon: Network },
      // F-C1: the asset library (moves to the new frame's "library" area in F-F3).
      { key: 'assets', label: t('assets.nav'), path: ASSETS_PATH, icon: Library },
    ] },
    { key: 'rewards', label: t('nav.groups.rewards'), items: [
      { key: 'cash', label: t('publishing.cash.nav'), path: CASH_REWARDS_PATH, icon: HandCoins },
      { key: 'digital', label: t('publishing.digital.nav'), path: DIGITAL_PRIZES_PATH, icon: Ticket },
      { key: 'redeem', label: t('publishing.redeem.nav'), path: REDEEM_PATH, icon: Gift },
    ] },
    // Q89a: buying other sponsors' benefits for one's takers, and offering one's own.
    { key: 'benefits', label: t('benefits.nav.group'), items: [
      { key: 'benefit-catalog', label: t('benefits.nav.catalog'), path: BENEFITS_PATH, icon: Handshake },
      { key: 'benefit-offers', label: t('benefits.nav.offers'), path: OFFERS_PATH, icon: Package },
      { key: 'benefit-conversations', label: t('benefits.nav.conversations'), path: BENEFIT_CONVERSATIONS_PATH,
        icon: MessageCircle },
      { key: 'benefit-purchases', label: t('benefits.nav.purchases'), path: PURCHASES_PATH, icon: ClipboardList },
    ] },
    { key: 'money', label: t('nav.groups.money'), items: [
      ...(kudos ? [{ key: 'wallet', label: t('wallet.nav'), path: WALLET_PATH, icon: Wallet }] : []),
      { key: 'transactions', label: t('transactions.nav'), path: TRANSACTIONS_PATH, icon: ReceiptText },
    ] },
    // Q79: the sponsor's own things (profile, page, products, staff, mail server) are the hub's sections.
    { key: 'settings', label: t('nav.groups.settings'), items: [
      { key: 'onboarding', label: t('nav.onboarding'), path: PROFILE_PATH, icon: Store },
      { key: 'stats', label: t('stats.nav'), path: STATS_PATH, icon: ChartColumn },
      // Q82: the badge counts the takers' conversations with something unread.
      { key: 'messages', label: t('notices.nav'), path: MESSAGES_PATH, icon: MessagesSquare,
        badge: unreadConversations },
      { key: 'account', label: t('nav.account'), path: ACCOUNT_PATH, icon: UserRound },
      { key: 'help', label: t('nav.help'), path: HELP, icon: CircleHelp },
    ] },
  ]
  const shell = (
    <AppShell
      brand={<Wordmark name={t('brand')} compact className="px-2 py-1" />}
      navigation={
        <ShellNav label={t('nav.label')} groups={groups} isActive={(p) => path === p || (p !== BENEFITS_PATH && path.startsWith(`${p}/`))}
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
        {/* Signed in unverified (Q40): every write waits for the address, so say so and offer the mail again. */}
        <VerifyEmailBanner />
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
        {/* A test system says so before signing in too (platform 16zd; gap 6), as the app's public pages do. */}
        <EnvironmentBanner />
        <div className="mx-auto flex max-w-3xl items-center gap-2 px-4 py-2">
          <Wordmark name={t('brand')} compact className="mr-auto" />
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

/**
 * The pages before a session in the platform's entry pages' template (phase 17zk; P4, docs/design/12-frontend.md
 * section 14): the Sponsor app's wordmark, a column about it on a wide screen (one line on a phone), the offline notice
 * over the page (the Sponsor app is no PWA: no new-version notice; the test system's banner is the template's own),
 * the help and the texts in the footer, and the way to the Takers' app.
 */
function EntryPages({ children }: { children: ReactNode }) {
  const { t } = useTranslation(SPONSOR)
  const notices = useEntryNotices({ update: false })
  return (
    <EntryLayoutProvider
      brand={<Wordmark name={t('brand')} />}
      intro={{
        sentence: t('entry.sentence'),
        points: [t('entry.point1'), t('entry.point2'), t('entry.point3')],
        illustration: <GrowthArt className="h-56 w-full max-w-md" />,
      }}
      notices={notices}
      links={<EntryLegalLinks kinds={LINKED} />}
      otherEntry={{ text: t('entry.toTaker'), href: `${APP_BASE}login`, link: t('entry.toTakerLink') }}
    >
      {children}
    </EntryLayoutProvider>
  )
}

/** The Sponsor app's pages by path: the sign-in pages for everyone, the rest for a signed-in sponsor only. */
function Routes() {
  const { t, i18n } = useTranslation(SPONSOR)
  // The tab says the Sponsor app's name in the page's language (index.html has the English one until then).
  useEffect(() => {
    document.title = t('brand')
  }, [t, i18n.language])
  const { ready, signedIn } = useAuth()
  const { path, search } = useLocation()
  const brand = <Wordmark name={t('brand')} />
  // Every entry page in the template, with the offline notice that was over the card before.
  const frame = (content: ReactNode) => <EntryPages>{content}</EntryPages>

  // The blocks of mailed links read the address themselves, once: the token is removed from it right away.
  if (path === UNSUBSCRIBE_PATH) return frame(<MailUnsubscribe brand={brand} search={search} />)
  if (MAIL_PAGES.has(path)) {
    return frame(<MailLinkPage {...navigation} page={path.slice('/auth/'.length)} brand={brand}
      signInPath="/login" passwordResetPath="/password-reset" />)
  }
  // No identity provider is offered to sponsors by default (Q-6); a deployment that configures one gets its return.
  if (path === '/login/oidc') {
    return frame(<OidcCallback brand={brand} fallbackPath="/"
      onSignedIn={(to) => navigate(to, { replace: true })}
      onLinked={(to) => navigate(to || ACCOUNT_PATH, { replace: true })}
      // Handed over in memory with the page to go to; kept in this tab's storage too, for a reload (gap 2).
      onSecondStep={(step, to) => {
        pendingStep = { step, to }
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
  // The form, then "look in your mailbox"; the console signs unverified sponsors in (Q40), but applying waits for the
  // verification. Google, Facebook and LINE open an account at their first sign-in, so they are offered here too.
  // The sponsors' terms are accepted with the application (Q7b); signing up says where to read them.
  if (path === '/signup') {
    // Q48d: a sign-up from a prospecting trial keeps its key until the sponsor applies.
    rememberSource(search)
    return frame(<>
      <SignUpForm {...navigation} brand={brand} signInPath="/login" signUpPath="/signup" tabs
        oidc={{ returnTo: '/', layout: 'icons' }}
        note={<span data-testid="signup-terms">
          {t('public.signUpTerms')}{' '}
          <LegalLink kind="TERMS_SPONSOR" />{' · '}<LegalLink kind="PRIVACY" />
        </span>} />
    </>)
  }
  if (path === '/login') {
    if (signedIn) return <Redirect to="/" />
    const notice = lockedIdle ? 'idle' : undefined
    const pending = pendingStep
    // A taker's account (with a password or Google alone) has no sponsor role yet: the Sponsor app refuses it until
    // the account joins by signing up with its address (Q37). Said on every step of signing in, as before 17zk: the
    // template's footer of this page (a form's `below` is the password step's only).
    return frame(
      <EntryLayoutProvider footer={<span className="text-sm" data-testid="signin-taker-account">
        {t('public.takerAccount')}</span>}>
      <SignInForm {...navigation} brand={brand} returnTo={pending?.to ?? '/'} initialStep={pending?.step}
        notice={notice} resumeStep
        onSignedIn={(to) => {
          pendingStep = undefined
          lockedIdle = false
          navigate(to, { replace: true })
        }} passwordResetPath="/password-reset" signInPath="/login" signUpPath="/signup" tabs
        // Suggestion ③: passkeys first, then the providers (icons on a phone), the password last.
        order={['passkey', 'providers', 'password']} oidcLayout="responsive" userNameLabel="email" passkey />
      </EntryLayoutProvider>,
    )
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
