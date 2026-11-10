import { AuthProvider, useAuth, type SignInStep } from '@jabiz/client'
import {
  AppearanceProvider,
  EntryLayoutProvider,
  EnvironmentBanner,
  MAIL_LINK_PAGES,
  MailLinkPage,
  MailUnsubscribe,
  OfflineBanner,
  OidcCallback,
  PageState,
  PasswordResetRequest,
  SignInForm,
  SignUpForm,
  StepUpProvider,
  ThemeToggle,
  Toaster,
  VerifyEmailBanner,
  useEntryNotices,
} from '@jabiz/ui'
import { QueryClientProvider, useQuery } from '@tanstack/react-query'
import { useEffect, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { BENEFITS_QUERY, readAccess } from './benefits/api'
import { SPONSOR } from './messages'
import { ACCOUNT_PATH } from './pages/AccountPage'
import { readRewardSettings, REWARD_SETTINGS_QUERY } from './publishing/api'
import { APP_BASE } from './publishing/paths'
import { rememberSource } from './trial/claim'
import TrialPage, { trialKeyOf } from './trial/TrialPage'
import { useMySponsor } from './profile/api'
import { profilePath } from './profile/paths'
import { useUnreadConversations } from './conversations/ConversationsPages'
import { prizesBadge } from './home/todo'
import { useTodoCounts } from './home/useTodo'
import { ConsentGate, LegalLink, newQbQueryClient } from './qb/ConsentGate'
import { GrowthArt, Wordmark } from './qb/Illustrations'
import { HelpPage } from './qb/HelpPage'
import { HelpPanel } from './help/HelpPanel'
import { LEGAL } from './qb/ids'
import { legalKindOf, type LegalKind } from './qb/legal'
import { EntryLegalLinks, LegalLinks } from './qb/LegalLinks'
import { LegalPage } from './qb/LegalPage'
import { QB } from './qb/messages'
import { hrefOf, navigate, navigation, Redirect, useLocation } from './qb/router'
import { useSessionLock } from './qb/session'
import { useSoundSetting } from './qb/SoundSettings'
import { legacyTarget } from './shell/legacy'
import { HOME_PATH } from './shell/paths'
import { pageOf } from './shell/routes'
import { SponsorShell } from './shell/SponsorShell'

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
 * The pages of a signed-in sponsor in the Sponsor app's frame (F-F3: the home and eight areas, `shell/`): old
 * addresses lead to their new ones first (`shell/legacy-paths.json`), then the route table (`shell/routes.tsx`).
 * The home page leads to the onboarding until the sponsor is approved.
 */
function SignedIn({ path }: { path: string }) {
  const { t: tQb } = useTranslation(QB)
  const { displayName, signOut, userId } = useAuth()
  useSessionLock(lockIdle)
  const sponsor = useMySponsor()
  // Q28: a sponsor who may not pay in the platform's Kudos has no use for the Kudos wallet and its top-ups; the server
  // decides what they may publish, this only hides the tab. Until the answer is in (or without one) it shows.
  // Q57: the sound effects follow the sponsor's switch.
  useSoundSetting()
  const rewards = useQuery({ queryKey: REWARD_SETTINGS_QUERY, queryFn: readRewardSettings, staleTime: 60_000 })
  const kudos = rewards.data ? rewards.data.methods.includes('PLATFORM') : true
  // F-F3: the area of sponsored benefits only for a holder of one of the benefit roles (until the answer is in, none).
  const access = useQuery({ queryKey: [...BENEFITS_QUERY, 'access'], queryFn: readAccess, staleTime: 60_000,
    enabled: !!userId })
  const benefits = access.data?.some((row) => row.open) ?? false
  const { search } = useLocation()
  // Q82: the menu's badge of the takers' conversations.
  const unreadConversations = useUnreadConversations(!!userId)
  // F-F3b: the prizes area's badge, the claims and digital prizes waiting for the Sponsor (`qb.sponsor.todo`).
  const todo = useTodoCounts(!!userId && sponsor.data?.reviewStatus === 'APPROVED')
  // The pages start from who is signed in (the sign-up name prefills the onboarding): wait until the server said so.
  if (!userId) return <PageState kind="loading" />

  if (path === '/' || path === '') {
    if (sponsor.isPending) return <PageState kind="loading" />
    // Q79: a sponsor not yet approved starts at the hub's basics (the application); approved, at the home.
    return <Redirect to={sponsor.data?.reviewStatus === 'APPROVED' ? HOME_PATH : profilePath('basics')} />
  }

  const open = publicPage(path)
  const legacy = open ? null : legacyTarget(path, search)
  if (legacy) return <Redirect to={legacy} />
  const state = { kudos, benefits }
  const page = open ?? pageOf(path, search, state)
    ?? <PageState kind="notFound" titleAs="h1" description={tQb('notFound')} />

  const shell = (
    <SponsorShell path={path} state={state} badges={{ messages: unreadConversations, prizes: prizesBadge(todo) }} displayName={displayName}
      helpPath={HELP} onSignOut={() => void signOut().then(toSignIn)}
      tools={<HelpPanel path={path} search={search} />}>
      <div className="flex flex-col gap-4">
        <OfflineBanner />
        {/* Signed in unverified (Q40): every write waits for the address, so say so and offer the mail again. */}
        <VerifyEmailBanner />
        {page}
        <LegalLinks kinds={LINKED} className="mt-8 border-t pt-4" />
      </div>
    </SponsorShell>
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
