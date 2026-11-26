import { AuthProvider, useAuth, type SignInStep } from '@jabiz/client'
import {
  AppearanceProvider,
  Button,
  EntryLayoutProvider,
  EnvironmentBadge,
  MAIL_LINK_PAGES,
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
  Toaster,
  VerifyEmailBanner,
  cn,
  useEntryNotices,
} from '@jabiz/ui'
import { QueryClientProvider, useQuery } from '@tanstack/react-query'
import { ArrowLeft, LogIn, LogOut, Store } from 'lucide-react'
import { useEffect, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from './messages'
import { takerHandleOf, TakerPage, verifyKeyOf, VerifyPage } from './certificates/PublicCertificates'
import { ACCOUNT_SECURITY_PATH, AccountPage, isAccountPath, rememberLinked } from './pages/AccountPage'
import { VideoStage } from './video/stage'
import { FeedControlsProvider, FeedFiltersButton, FeedPage } from './pages/FeedPage'
import { SearchPage } from './pages/SearchPage'
import { HistoryPage } from './pages/HistoryPage'
import { PlayPage } from './pages/PlayPage'
import { PublicationPage } from './pages/PublicationPage'
import { PROFILE_QUERY } from './pages/ProfileForm'
import { ResultPage } from './pages/ResultPage'
import { RedeemPage } from './pages/RedeemPage'
import { WelcomePage } from './pages/WelcomePage'
import { AboutPage } from './pages/AboutPage'
import { IntroPage, introSeen, rememberSeen } from './pages/IntroPage'
import { BrandMark, Wordmark } from './qb/Illustrations'
import { StickyHead, SubHead, TakerAvatar, usePageHasOwnHead } from './frame/Header'
import { inboxItemOf, INBOX_PATH } from './inbox/api'
import { MY_SPONSOR_PATH, MY_SPONSOR_QUERY, readFigures, useMySponsor } from './mysponsor/api'
import { MySponsorPage, SPONSOR_CONSOLE } from './mysponsor/MySponsorPage'
import { ReferralPage, ReferralsPage } from './referral/ReferralsPage'
import { ConversationPage, ConversationsPage } from './conversation/ConversationPages'
import { conversationOf, CONVERSATIONS_PATH } from './conversation/api'
import { REFERRALS_PATH } from './referral/api'
import { InboxItemPage } from './inbox/InboxItemPage'
import { InboxPage } from './inbox/InboxPage'
import { forgetAttempts } from './play/storage'
import { itemPage } from './routes'
import { AppFrame } from './frame/AppFrame'
import { INBOX_REWARDS_PATH, legacyTarget, MY_PASSES_PATH, PRIZES_PATH, SEARCH_PATH, UNFINISHED_PATH } from './frame/areas'
import { useSignOut } from './frame/signOut'
import { PrizesPage } from './pages/PrizesPage'
import { MyPassesPage } from './pass/MyPassesPage'
import { pendingRedeem, redeemPath, rememberRedeem } from './redeemReturn'
import { signInOrder } from './signin'
import { InAppBrowserNotice, useInAppNotice } from './install'
import { readMyProfile } from './qb/api'
import { ConsentGate, LegalLink, newQbQueryClient } from './qb/ConsentGate'
import { HelpPage } from './qb/HelpPage'
import { AskPanel } from './help/AskPanel'
import { notePage } from './help/pages'
import { LEGAL } from './qb/ids'
import { legalKindOf } from './qb/legal'
import { EntryLegalLinks, LegalLinks } from './qb/LegalLinks'
import { LegalPage } from './qb/LegalPage'
import { QB } from './qb/messages'
import { canGoBack, currentPath, Link, navigate, navigation, Redirect, useLocation } from './qb/router'
import { resultPublication } from './pages/resultPublication'
import { useSoundSetting } from './qb/SoundSettings'
import { useSessionLock } from './qb/session'
import { BANK_PATH, isWalletPath, PAYOUT_PATH } from './wallet/api'
import { kudosMethodOf } from './wallet/kudos'
import { KudosMethodPage } from './wallet/KudosOverview'
import { BankPage } from './wallet/BankPage'
import { rememberShareNext } from './share/share'
import { SharePage } from './share/SharePage'
import { SponsorPage, sponsorPageOf } from './share/SponsorPage'
import { HANDLED_PATH, STAFF_PATH } from './staff/api'
import { SCAN_PATH, ScanPage } from './scan/ScanPage'
import { StaffDeskPage } from './staff/StaffDeskPage'
import { PassPage } from './pass/PassPage'
import { StaffHandledPage } from './staff/StaffHandledPage'
import { PayoutPage } from './wallet/PayoutPage'
import { WalletPage } from './wallet/WalletPage'
import { MaintenanceRedirect } from './maintenance'
import { MapPage, MapsPage } from './maps/MapsPage'
import { upOf } from './frame/up'
import './frame/messages'
import { SETTINGS_PATH } from './pages/accountPaths'
import { SettingsPage } from './frame/SettingsPage'
import { mapOf, MAPS_PATH } from './maps/paths'

/** Where a signed-in taker starts. */
const HOME = '/'
const WELCOME = '/welcome'
const HISTORY = '/history'
const HELP = '/help'
const ABOUT = '/about'

/**
 * The second step of a sign-in through an identity provider, with the page to go to afterwards (gap 2), handed from
 * the callback to the sign-in page in memory; the sign-in page also resumes it from this tab's storage after a reload
 * (platform 17zk, `resumeStep`).
 */
let pendingStep: { step: SignInStep; to: string } | undefined

/** Every page of a mailed link the platform has (`MAIL_LINK_PAGES`, gap 3), each at `/auth/<page>`. */
const MAIL_PAGES = new Set(MAIL_LINK_PAGES.map((page) => `/auth/${page}`))

/**
 * The unsubscribe page of the app's notification mail (platform decision D52 item 3):
 * `jabiz.mail.unsubscribe-paths.app` is `mail/unsubscribe`, under the entry's app-path, so the links of the
 * mails declared for this entry lead here and takers never need the administration. Open without signing in:
 * the link's token is the credential.
 */
const UNSUBSCRIBE_PATH = '/mail/unsubscribe'

/** Back to the sign-in page, once signed out. */
function toSignIn() {
  navigate('/login', { replace: true })
}

/** The pages the header offers a way back from (Q64): a quiz's start page and a result. */
function backable(path: string): boolean {
  const item = itemPage(path)
  return item?.page === 'p' || item?.page === 'result'
}

/** Back to the page before, within the app; home when the page was opened first (a shared link, a mail). */
function goBack() {
  if (canGoBack()) window.history.back()
  else navigate(backFallback(currentPath()))
}

/** Where back goes from a page opened first (Q65): a result to its quiz's page, a quiz's page home. */
function backFallback(path: string): string {
  const item = itemPage(path)
  if (item?.page === 'result') {
    const publicationId = resultPublication.get(item.id)
    if (publicationId) return `/p/${encodeURIComponent(publicationId)}`
  }
  return HOME
}

/**
 * The header's way to the sponsor's own page (Q68): only for a taker who is a sponsor too. A dot says that a
 * publication is short of funds; the figures come from the page's own (shared, five minutes) query.
 */
function SponsorEntry({ current }: { current: boolean }) {
  const { t } = useTranslation(APP)
  const sponsor = useMySponsor().data
  const approved = sponsor?.reviewStatus === 'APPROVED'
  const figures = useQuery({ queryKey: [...MY_SPONSOR_QUERY, 'attention'], enabled: approved, staleTime: 5 * 60_000,
    queryFn: () => readFigures(new Date().toISOString()) })
  if (!sponsor) return null
  const attention = (figures.data?.fundsShort ?? 0) > 0
  return (
    <Button asChild variant="ghost" size="icon-sm" className="relative">
      <Link to={MY_SPONSOR_PATH} current={current} data-testid="header-my-sponsor"
        aria-label={attention ? `${t('mySponsor.nav')}: ${t('mySponsor.attention')}` : t('mySponsor.nav')}>
        <Store aria-hidden />
        {attention && <span aria-hidden data-testid="header-my-sponsor-dot"
          className="bg-destructive absolute top-1 right-1 size-2 rounded-full" />}
      </Link>
    </Button>
  )
}

/**
 * The frame's head (Q62; Q113 §9.1, P4) for the pages that draw none of their own: the home's (the avatar, the mark in
 * the middle, the filters, a Sponsor's own page and the search at the right), a quiz's start page and a result (the
 * way back and the page's name), the welcome step (the mark, and signing out: it has no "Me"), and the other pages
 * (the way back). A page with an `AreaHeader` draws its own, and this one stands back. The menu that was at its right
 * end is gone (F-F4): scanning is in the bottom menu, the referrals, the appearance and signing out are in "Me".
 */
function Header({ search = false, welcome = false }: { search?: boolean; welcome?: boolean }) {
  const { t } = useTranslation(QB)
  const { t: tApp } = useTranslation(APP)
  const signOut = useSignOut()
  const { path } = useLocation()
  const own = usePageHasOwnHead()
  if (own) return null
  const mark = (
    <span className={cn('pointer-events-none absolute inset-x-0 flex justify-center', search && 'md:hidden')} data-testid="head-mark">
      <BrandMark className="size-(--x-head-mark)" />
      <span className="sr-only">{t('brand')}</span>
    </span>
  )
  if (path === HOME || welcome) {
    return (
      <StickyHead testId="header">
        <div className="relative flex w-full items-center gap-1 pr-(--x-back-inset) pl-(--x-side)" data-head="home">
          {search && <TakerAvatar />}
          {mark}
          <span className="mr-auto" />
          {/* Q64: on the home, its filters (the way a reward is paid and the others) beside the search. */}
          {search && <FeedFiltersButton />}
          {/* Q68: a sponsor's own account, for a sponsor only; a dot when something needs them. */}
          {search && <SponsorEntry current={false} />}
          {/* F-F4: the welcome step has no bottom menu and so no "Me": signing out is here. */}
          {welcome && (
            <Button variant="ghost" size="sm" onClick={signOut} data-testid="sign-out">
              <LogOut aria-hidden />
              {t('signOut')}
            </Button>
          )}
        </div>
      </StickyHead>
    )
  }
  // Q64: a page opened from another (a quiz's start page, a result) goes back in the history; a page under "Me" up to
  // the page above it (`upOf`): one back button, always in this place.
  const item = itemPage(path)
  const up = upOf(path)
  const back = backable(path) || !up
    ? { onClick: goBack, label: tApp('nav.back') }
    : { to: up.path, label: tApp('nav.backTo', { name: tApp(up.name) }) }
  const title = item?.page === 'p' ? tApp('frame.heads.publication')
    : item?.page === 'result' ? tApp('frame.heads.result') : undefined
  return (
    <SubHead claim={false} back={back} title={title} id="frame-head-title" />
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
  // Q57: the sound effects follow the taker's switch.
  useSoundSetting()
  const { userId, emailVerified, displayName } = useAuth()
  const profile = useQuery({ queryKey: PROFILE_QUERY, queryFn: readMyProfile })
  // The pages start from who is signed in (the sign-up name prefills the profile): wait until the server said so.
  if (profile.isPending || !userId) return <PageState kind="loading" />
  if (profile.isError) {
    return <PageState kind="error" description={t('failed')}
      action={<Button variant="outline" onClick={() => void profile.refetch()}>{t('retry')}</Button>} />
  }
  const complete = profile.data?.countryCode ? profile.data : null
  if (!complete && emailVerified && path !== WELCOME && !isAccountPath(path) && path !== SETTINGS_PATH) {
    // From a share link (Q15): the short welcome step, then back to the publication.
    if (itemPage(path)?.page === 'p') rememberShareNext(path)
    return <Redirect to={WELCOME} />
  }
  if (complete && path === WELCOME) return <Redirect to={HOME} />
  // A redeem code scanned before signing in, whose page the sign-in did not return to: back to it (the page forgets it).
  const scanned = complete ? pendingRedeem() : null
  if (scanned && itemPage(path)?.page !== 'redeem') return <Redirect to={redeemPath(scanned)} />

  const country = complete?.countryCode ?? null
  const item = itemPage(path)
  let page: ReactNode
  if (path === WELCOME) page = <WelcomePage profile={profile.data} />
  else if (path === HOME) page = <FeedPage name={complete?.displayName || displayName || ''} country={country} />
  else if (path === SEARCH_PATH) page = <SearchPage name={complete?.displayName || displayName || ''} country={country} />
  else if (path === HISTORY) page = <HistoryPage tab="answered" />
  else if (path === UNFINISHED_PATH) page = <HistoryPage tab="unfinished" />
  else if (path === PRIZES_PATH) page = <PrizesPage />
  else if (path === MY_PASSES_PATH) page = <MyPassesPage />
  else if (path === PAYOUT_PATH) page = <PayoutPage />
  else if (path === BANK_PATH) page = <BankPage />
  else if (kudosMethodOf(path)) page = <KudosMethodPage key={path} method={kudosMethodOf(path)!} />
  else if (isWalletPath(path)) page = <WalletPage path={path} />
  else if (path === SETTINGS_PATH) page = <SettingsPage />
  else if (isAccountPath(path)) page = <AccountPage path={path} />
  else if (path === INBOX_PATH) page = <InboxPage tab="inbox" />
  else if (path === INBOX_REWARDS_PATH) page = <InboxPage tab="rewards" />
  else if (path === STAFF_PATH) page = <StaffDeskPage />
  else if (path === HANDLED_PATH) page = <StaffHandledPage />
  else if (path === MY_SPONSOR_PATH) page = <MySponsorPage />
  else if (path === MAPS_PATH) page = <MapsPage />
  else if (mapOf(path)) page = <MapPage key={path} treeId={mapOf(path)!} country={country} />
  else if (path === REFERRALS_PATH) page = <ReferralsPage />
  else if (path === CONVERSATIONS_PATH) page = <ConversationsPage />
  else if (conversationOf(path)) page = <ConversationPage key={path} conversationId={conversationOf(path)!} />
  else if (path === SCAN_PATH) page = <ScanPage />
  else if (inboxItemOf(path)) {
    const message = inboxItemOf(path)!
    page = <InboxItemPage key={`${message.kind}:${message.id}`} kind={message.kind} id={message.id} />
  }
  else if (item?.page === 'p') page = <PublicationPage key={item.id} publicationId={item.id} country={country} />
  else if (item?.page === 'play') page = <PlayPage key={item.id} attemptId={item.id} />
  else if (item?.page === 'result') page = <ResultPage key={item.id} attemptId={item.id} />
  else if (item?.page === 'referral') page = <ReferralPage key={item.id} referralId={item.id} />
  else if (item?.page === 'redeem') page = <RedeemPage key={item.id} scannedKey={item.id} />
  else if (item?.page === 'pass') page = <PassPage key={item.id} passKey={item.id} />
  else page = <PageState kind="notFound" titleAs="h1" description={t('notFound')} />
  // The navigation is the app's; the welcome step stands alone, and answering keeps the screen to the question.
  const nav = path !== WELCOME && item?.page !== 'play'
  const pages = (
    <AppFrame nav={nav}>
      <Header search={nav} welcome={path === WELCOME} />
      {/* Q65: the one video player, laid over the place that shows it. The home and a start page begin right under
          the header (Q66). */}
      <VideoStage />
      {/* Under the bottom menu of a phone the page keeps clear of it (F-F4); a wide screen has none. */}
      <main className={cn('flex w-full flex-col gap-4 px-4 py-4', !nav && 'mx-auto max-w-md',
        nav && 'pb-[calc(var(--bottom-bar-offset)+env(safe-area-inset-bottom)+1rem)] md:pb-4',
        (path === HOME || item?.page === 'p') && 'pt-0')}>
        <OfflineBanner />
        <PwaUpdatePrompt />
        <VerifyEmailBanner />
        {page}
      </main>
    </AppFrame>
  )
  // Whoever completed the profile before the texts in effect accepts them first (D-Q7-5); registration's second
  // step accepts them with the profile.
  return <ConsentGate kinds={LEGAL.appKinds} enabled={!!complete} onSignOut={() => {
    forgetAttempts()
    toSignIn()
  }}><FeedControlsProvider>{pages}</FeedControlsProvider></ConsentGate>
}

/** A round see-through button floating over a Sponsor's cover (from the theme's tokens, readable on any cover). */
const FLOATING = 'bg-background/80 text-foreground hover:bg-background focus-visible:ring-ring flex size-10 items-center '
  + 'justify-center rounded-full border shadow-md backdrop-blur focus-visible:ring-2 focus-visible:outline-none'

/**
 * The help centre, the legal texts and the About page (Q7b): readable without signing in, in a frame of their own
 * with the way back (to the app, or to signing in). A Sponsor's page (`sponsorSite`) has the whole top to itself (the
 * requester, 2026-11-21): no app bar and no appearance switch; the way back is a round see-through button floating
 * over the top left of the Sponsor's cover.
 */
function PublicPage({ path, signedIn, sponsorSite = false, children }: { path: string; signedIn: boolean;
  sponsorSite?: boolean; children?: ReactNode }) {
  const { t } = useTranslation(QB)
  const { t: tApp } = useTranslation(APP)
  const kind = legalKindOf(path)
  const page = children ? children : path === HELP ? <HelpPage audience="APP" top={<AskPanel />} />
    : path === ABOUT ? <AboutPage />
    : kind ? <LegalPage key={kind} kind={kind} />
    : null
  const back = signedIn ? HOME : itemPage(path) || sponsorPageOf(path) ? `/login?next=${encodeURIComponent(path)}`
    : '/login'
  const backLabel = signedIn ? tApp('public.back') : tApp('public.signIn')
  return (
    <div className="flex min-h-full flex-col">
      {/* A test system's mark (platform 17zz27) is a corner badge, outside the header: no room of the page. */}
      <EnvironmentBadge />
      {!sponsorSite && (
        <header className="bg-background sticky top-0 z-10 border-b" data-testid="public-header">
          <div className="mx-auto flex max-w-md items-center gap-2 px-4 py-2">
            {/* Signed in, these pages are rows of the settings (Q113d-2): the same back button as the app's pages
                there (`upOf`), in the same place. Signed out, the way to signing in. */}
            {signedIn && (
              <Button asChild variant="ghost" size="icon-sm" className="-ml-2">
                <Link to={SETTINGS_PATH} aria-label={tApp('nav.backTo', { name: tApp('me.settings') })}
                  data-testid="public-back">
                  <ArrowLeft aria-hidden />
                </Link>
              </Button>
            )}
            <Wordmark name={t('brand')} compact className="text-primary mr-auto" />
            <ThemeToggle />
            {!signedIn && (
              <Link to={back} className="text-primary text-sm underline" data-testid="public-back">{backLabel}</Link>
            )}
          </div>
        </header>
      )}
      {sponsorSite && (
        // Over the Sponsor's cover, not in the page: a row of no height that stays at the top while scrolling, with the
        // way back on the left (round, see-through, clear of a phone's notch). No appearance switch here: the
        // requester agreed to drop it from this page (2026-11-21); the app's settings have it.
        <div className="sticky top-0 z-20 mx-auto h-0 w-full max-w-md" data-testid="sponsor-site-controls">
          <div className={'flex items-center pt-[max(0.75rem,env(safe-area-inset-top))] '
            + 'pl-[max(0.75rem,env(safe-area-inset-left))]'}>
            <Link to={back} aria-label={backLabel} data-testid="public-back" className={FLOATING}>
              {signedIn ? <ArrowLeft aria-hidden className="size-5" /> : <LogIn aria-hidden className="size-5" />}
            </Link>
          </div>
        </div>
      )}
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

/**
 * The pages before a session in the platform's entry pages' template (phase 17zk; P4, docs/design/12-frontend.md
 * section 14), in its open look (17zz30, Q113 §9.4 as measured on x.com): no card; the brand's mark and name above the
 * column on a phone, the name large with the mark large beside the column on a wide screen; the notices over the page
 * (the way out of another app's browser first, then offline and a new version; the test system's banner is the
 * template's own), the help and the texts in the footer, and the way to the Sponsor app.
 */
function EntryPages({ children }: { children: ReactNode }) {
  const { t } = useTranslation(QB)
  const { t: tApp } = useTranslation(APP)
  const inApp = useInAppNotice()
  const notices = [
    // Q50: from the first visit, inside WeChat, QQ, LINE and the like; never in a store app. Closed, it gives the
    // expanded place on a phone to the next notice (the click that closes it is noticed here).
    { key: 'in-app', summary: tApp('entry.inAppSummary'), active: inApp.open,
      content: inApp.open ? <div onClickCapture={() => window.setTimeout(inApp.recheck)}><InAppBrowserNotice /></div>
        : null },
    ...useEntryNotices(),
  ]
  return (
    <EntryLayoutProvider
      look="open"
      mark={<BrandMark className="size-full" />}
      brand={t('brand')}
      notices={notices}
      links={<EntryLegalLinks kinds={LEGAL.appKinds} />}
      otherEntry={{ text: tApp('entry.toSponsor'), href: `${SPONSOR_CONSOLE}login`, link: tApp('entry.toSponsorLink') }}
    >
      {children}
    </EntryLayoutProvider>
  )
}

/** The app's pages by path: the sign-in pages for everyone, the rest for a signed-in taker only. */
function Routes() {
  const { t } = useTranslation(QB)
  const { t: tApp } = useTranslation(APP)
  const { ready, signedIn } = useAuth()
  const { path, search } = useLocation()
  // F-C10c: the AI help is told the page the Taker came from (a name of its list, never the path).
  useEffect(() => notePage(path, search), [path, search])
  // The name alone: the open look draws the mark itself, beside it on a phone and large on a wide screen.
  const brand = t('brand')
  // Every entry page in the template, with the notices that were over the card before (offline, a new version, the
  // way out of another app's browser).
  const frame = (content: ReactNode) => <EntryPages>{content}</EntryPages>

  // Whoever reaches a sign-in, sign-up or mailed-link page knows the app: no introduction afterwards (a verified
  // address continues to the root and must land on the sign-in, as before Q32).
  if (path === '/login' || path === '/signup' || path === '/password-reset' || MAIL_PAGES.has(path)
    || path === UNSUBSCRIBE_PATH) rememberSeen()
  // The blocks of mailed links and of the identity provider's return read the address themselves, once: the token
  // and the code are removed from it right away.
  if (path === UNSUBSCRIBE_PATH) return frame(<MailUnsubscribe brand={brand} search={search} />)
  // F-F4: an address that changed leads to its page now (the one list, `LEGACY_APP_PATHS`): the stores' address of a
  // deletion without the app (Q71), the account's old section of the details (Q56).
  const legacy = legacyTarget(path, search)
  if (legacy) return <Redirect to={legacy} />
  if (MAIL_PAGES.has(path)) {
    return frame(<MailLinkPage {...navigation} page={path.slice('/auth/'.length)} brand={brand}
      signInPath="/login" passwordResetPath="/password-reset" />)
  }
  if (path === '/login/oidc') {
    return frame(<OidcCallback brand={brand} fallbackPath={HOME}
      onSignedIn={(to) => navigate(to, { replace: true })}
      onLinked={(to, linked) => {
        rememberLinked(linked)
        navigate(to || ACCOUNT_SECURITY_PATH, { replace: true })
      }}
      // Handed over in memory with the page to go to; kept in this tab's storage too, for a reload (gap 2).
      onSecondStep={(step, to) => {
        pendingStep = { step, to }
        navigate('/login', { replace: true })
      }}
      onBack={() => navigate('/login', { replace: true })} />)
  }
  if (!ready) return <PageState kind="loading" />
  if (isPublic(path)) return <PublicPage path={path} signedIn={signedIn} />
  // Q77: a certificate's verify page and a taker's public certification page, anybody's.
  const verifyKey = verifyKeyOf(path)
  if (verifyKey) {
    return <PublicPage path={path} signedIn={signedIn}><VerifyPage key={verifyKey} verifyKey={verifyKey} /></PublicPage>
  }
  const takerHandle = takerHandleOf(path)
  if (takerHandle) {
    return <PublicPage path={path} signedIn={signedIn}><TakerPage key={takerHandle} handle={takerHandle} /></PublicPage>
  }
  // A sponsor's page (Q60a): anybody's, signed in or not, from the public templates.
  const sponsorHandle = sponsorPageOf(path)
  if (sponsorHandle) {
    return <PublicPage path={path} signedIn={signedIn} sponsorSite>
      <SponsorPage key={sponsorHandle} handle={sponsorHandle} signedIn={signedIn} />
    </PublicPage>
  }
  // A publication's share link before signing in (Q15): its public page with the address to sign in by.
  const shared = itemPage(path)
  if (!signedIn && shared?.page === 'p') {
    return <PublicPage path={path} signedIn={false}><SharePage publicationId={shared.id} /></PublicPage>
  }
  // A sponsor's redeem code scanned before signing in (Q22b): sign in, then back to it. The code is also kept on the
  // device, for a sign-in that ends in another tab (a mailed link, an identity provider).
  if (!signedIn && shared?.page === 'redeem') {
    rememberRedeem(shared.id)
    return <Redirect to={`/login?next=${encodeURIComponent(path)}`} />
  }
  // Where signing in leads: a page of the app the person came from (a share link's), else home.
  const next = new URLSearchParams(search).get('next')
  const afterSignIn = next && next.startsWith('/') && !next.startsWith('//') ? next : HOME
  // Signed in, the pages of signing up and asking for a new password lead home.
  if (signedIn && (path === '/signup' || path === '/password-reset')) return <Redirect to={HOME} />
  if (path === '/password-reset') {
    return frame(<PasswordResetRequest {...navigation} brand={brand} signInPath="/login" />)
  }
  // The form, then "look in your mailbox" with a new mail on request; the profile follows the first sign-in.
  // The texts are accepted on the welcome step (Q7-3); signing up says where to read them.
  // Signing up and signing in are two tabs of one page (suggestion ④); each keeps its address and the query.
  if (path === '/signup') {
    return frame(<>
      <SignUpForm {...navigation} brand={brand} signInPath="/login" signUpPath="/signup" tabs
        oidc={{ returnTo: HOME, layout: 'buttons' }}
        note={<span data-testid="signup-terms">
          {tApp('public.signUpTerms')}{' '}
          <LegalLink kind="TERMS_APP" />{' · '}<LegalLink kind="PRIVACY" />
        </span>} />
    </>)
  }
  if (path === '/login') {
    if (signedIn) return <Redirect to={afterSignIn} />
    const pending = pendingStep
    return frame(<SignInForm {...navigation} brand={brand} returnTo={pending?.to ?? afterSignIn}
      initialStep={pending?.step} resumeStep
      onSignedIn={(to) => {
        pendingStep = undefined
        // Someone who signed in knows the app: no introduction when the session later expires (Q32).
        rememberSeen()
        navigate(to, { replace: true })
      }} passwordResetPath="/password-reset" signInPath="/login" signUpPath="/signup" tabs
      // Q50 and gap 1: in Chinese the mailed link first (Google, Facebook and LINE do not open in mainland China); in
      // Japanese and English the providers first and the mailed link behind a button. The providers as full-width
      // pills at every width, as on x.com (Q113 §9.4).
      order={signInOrder} oidcLayout="buttons" emailLink userNameLabel="email" passkey />)
  }
  // A visitor's first time at the root: the introduction (Q32), remembered on the device; then the sign-in.
  if (!signedIn && path === HOME && !introSeen()) return <IntroPage />
  if (!signedIn) return <Redirect to="/login" />
  return (
    <StepUpProvider {...navigation} securityPath={ACCOUNT_SECURITY_PATH}>
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
          <MaintenanceRedirect />
          {/* The account page's offer to send mail in the new interface language (offerMailLanguage). */}
          <Toaster />
        </AuthProvider>
      </QueryClientProvider>
    </AppearanceProvider>
  )
}
