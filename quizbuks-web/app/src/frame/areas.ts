import { MAIL_LINK_PAGES } from '@jabiz/ui'
import { History, House, MessagesSquare, ScanLine, UserRound, type LucideIcon } from 'lucide-react'
import { verifyKeyOf, takerHandleOf } from '../certificates/PublicCertificates'
import { conversationOf, CONVERSATIONS_PATH } from '../conversation/api'
import { inboxItemOf, INBOX_PATH } from '../inbox/api'
import { mapOf, MAPS_PATH } from '../maps/paths'
import { MY_SPONSOR_PATH } from '../mysponsor/api'
import { ACCOUNT_DELETION_PATH, ACCOUNT_DETAILS_PATH, ACCOUNT_PARTS, ACCOUNT_PATH } from '../pages/AccountPage'
import { legalKindOf } from '../qb/legal'
import { REFERRALS_PATH } from '../referral/api'
import { itemPage } from '../routes'
import { SCAN_PATH } from '../scan/ScanPage'
import { sponsorPageOf } from '../share/SponsorPage'
import { HANDLED_PATH, STAFF_PATH } from '../staff/api'
import { BANK_PATH, PAYOUT_PATH, WALLET_PATH, walletPartOf } from '../wallet/api'
import { kudosMethodOf } from '../wallet/kudos'

/*
 * The taker app's frame (F-F4, docs/quizbuks/plans/impl/F-F4.md): its five areas, which area each page belongs to, and
 * the addresses that changed. The one place for all three: the bottom menu (the left column on a wide screen), the
 * areas' title icons and the redirects read it, and new pages are added here (backend/quizbuks/CLAUDE.md section 5).
 *
 * F-F4a only records it: the frame that reads it (the bottom menu, the tabs, "Me") is F-F4b, the publication's tabs
 * F-F4c. Until then the app looks as before; only the redirects already read `LEGACY_APP_PATHS`.
 */

/** The five areas, in the bottom menu's order (F-F4: scanning in the middle, raised). */
export type AreaKey = 'home' | 'history' | 'scan' | 'messages' | 'me'

export interface Area {
  key: AreaKey
  /** The area's page: the bottom menu's link. */
  path: string
  /** The bottom menu's icon, and the one before the titles of the area's pages. */
  icon: LucideIcon
  /** Raised in the middle of the bottom menu. */
  raised?: true
}

/** The bottom menu (the left column on a wide screen). New features become tabs or rows of "Me", never a sixth area. */
export const AREAS: readonly Area[] = [
  { key: 'home', path: '/', icon: House },
  { key: 'history', path: '/history', icon: History },
  { key: 'scan', path: SCAN_PATH, icon: ScanLine, raised: true },
  { key: 'messages', path: INBOX_PATH, icon: MessagesSquare },
  { key: 'me', path: ACCOUNT_PATH, icon: UserRound },
]

/** The area of a key. */
export function areaOf(key: AreaKey): Area {
  return AREAS.find((area) => area.key === key)!
}

/**
 * The pages of the app. `entry`: the platform's entry pages (17zk) and the mailed links; `public`: readable without
 * signing in, in their own frame; the rest are a signed-in taker's.
 */
export type PageName =
  // Home
  | 'home' | 'publication'
  // Answered
  | 'history' | 'prizes' | 'play' | 'result'
  // Scan
  | 'scan' | 'myPasses' | 'redeem' | 'pass'
  // Messages
  | 'inbox' | 'inboxItem' | 'conversations' | 'conversation'
  // Me
  | 'account' | 'accountPart' | 'accountBlocks' | 'accountDeletion' | 'wallet' | 'walletPart' | 'kudosMethod'
  | 'payout' | 'bank' | 'referrals' | 'referral' | 'mySponsor' | 'staffDesk' | 'staffHandled' | 'maps' | 'map'
  // Outside the areas
  | 'welcome' | 'help' | 'legal' | 'about' | 'verify' | 'takerPage' | 'sponsorPage'
  | 'signIn' | 'signUp' | 'passwordReset' | 'mailLink' | 'oidcCallback' | 'unsubscribe'

export interface Route {
  page: PageName
  /** The area the page belongs to (the bottom menu marks it); null outside the areas. */
  area: AreaKey | null
  /** Whether the frame's navigation shows: not while answering, nor on the welcome step, nor outside the areas. */
  nav: boolean
}

/** The pages F-F4b adds (F-F4 section 2): their routes are recorded already; the app shows them from F-F4b on. */
export const PRIZES_PATH = '/history/prizes'
export const MY_PASSES_PATH = '/scan/pass'
export const ACCOUNT_BLOCKS_PATH = `${ACCOUNT_PATH}/blocks`

const route = (page: PageName, area: AreaKey | null, nav = area !== null): Route => ({ page, area, nav })

/** The fixed addresses of the app (the ones with an id are matched in {@link routeOf}). */
const FIXED: Readonly<Record<string, Route>> = {
  '/': route('home', 'home'),
  '/history': route('history', 'history'),
  [PRIZES_PATH]: route('prizes', 'history'),
  [SCAN_PATH]: route('scan', 'scan'),
  [MY_PASSES_PATH]: route('myPasses', 'scan'),
  [INBOX_PATH]: route('inbox', 'messages'),
  [CONVERSATIONS_PATH]: route('conversations', 'messages'),
  [ACCOUNT_PATH]: route('account', 'me'),
  [ACCOUNT_BLOCKS_PATH]: route('accountBlocks', 'me'),
  [ACCOUNT_DELETION_PATH]: route('accountDeletion', 'me'),
  [WALLET_PATH]: route('wallet', 'me'),
  [PAYOUT_PATH]: route('payout', 'me'),
  [BANK_PATH]: route('bank', 'me'),
  [REFERRALS_PATH]: route('referrals', 'me'),
  [MY_SPONSOR_PATH]: route('mySponsor', 'me'),
  [STAFF_PATH]: route('staffDesk', 'me'),
  [HANDLED_PATH]: route('staffHandled', 'me'),
  // F-S3c: the knowledge maps, a row of "Me".
  [MAPS_PATH]: route('maps', 'me'),
  '/welcome': route('welcome', null),
  '/help': route('help', null),
  '/about': route('about', null),
  '/login': route('signIn', null),
  '/signup': route('signUp', null),
  '/password-reset': route('passwordReset', null),
  '/login/oidc': route('oidcCallback', null),
  '/mail/unsubscribe': route('unsubscribe', null),
}

const ITEM_ROUTES: Readonly<Record<NonNullable<ReturnType<typeof itemPage>>['page'], Route>> = {
  p: route('publication', 'home'),
  play: route('play', 'history', false),
  result: route('result', 'history'),
  redeem: route('redeem', 'scan'),
  pass: route('pass', 'scan'),
  referral: route('referral', 'me'),
}

/**
 * The page and area of an address (its path; `search` only for the old addresses that carry a query), or null for an
 * address the app has no page for. An old address of {@link LEGACY_APP_PATHS} is the page it leads to.
 */
export function routeOf(path: string, search = ''): Route | null {
  const legacy = legacyTarget(path, search)
  if (legacy) {
    const [to, query] = legacy.split('?')
    return routeOf(to, query ? `?${query}` : '')
  }
  const fixed = FIXED[path]
  if (fixed) return fixed
  const item = itemPage(path)
  if (item) return ITEM_ROUTES[item.page]
  if (inboxItemOf(path)) return route('inboxItem', 'messages')
  if (conversationOf(path)) return route('conversation', 'messages')
  if (kudosMethodOf(path)) return route('kudosMethod', 'me')
  if (walletPartOf(path)) return route('walletPart', 'me')
  if (mapOf(path)) return route('map', 'me')
  if (path.startsWith(`${ACCOUNT_PATH}/`)
    && (ACCOUNT_PARTS as readonly string[]).includes(path.slice(ACCOUNT_PATH.length + 1))) {
    return route('accountPart', 'me')
  }
  if (path.startsWith('/auth/') && (MAIL_LINK_PAGES as readonly string[]).includes(path.slice('/auth/'.length))) {
    return route('mailLink', null)
  }
  if (legalKindOf(path)) return route('legal', null)
  if (verifyKeyOf(path)) return route('verify', null)
  if (takerHandleOf(path)) return route('takerPage', null)
  if (sponsorPageOf(path)) return route('sponsorPage', null)
  return null
}

/** An address that changed: `from` (a path, and the query it must carry, if any) leads to `to`. */
export interface LegacyPath {
  from: string
  to: string
  /** Why it moved, and since when. */
  reason: string
}

/**
 * Every address of the app that changed, with where it leads now (as Q79's `LEGACY_PROFILE_PATHS` in the Sponsor app):
 * bookmarks, old mails, the stores' listings and an installed app's start keep working. The only list of redirects;
 * a test checks each. F-F4 moved no page (its areas keep the addresses), so the list starts with the two redirects the
 * app had before.
 */
export const LEGACY_APP_PATHS: readonly LegacyPath[] = [
  { from: '/delete-account', to: '/auth/account-deletion',
    reason: 'Q71: the address given to the stores for a deletion without the app (Google Play\'s policy)' },
  { from: `${ACCOUNT_PATH}?section=details`, to: ACCOUNT_DETAILS_PATH,
    reason: 'Q56: the details were a section of the account page before each part had its own page' },
]

/** Where an old address leads now, or null when it is not one. A `from` with a query matches when `search` has it. */
export function legacyTarget(path: string, search = ''): string | null {
  const query = new URLSearchParams(search)
  for (const legacy of LEGACY_APP_PATHS) {
    const [fromPath, fromQuery] = legacy.from.split('?')
    if (fromPath !== path) continue
    const wanted = new URLSearchParams(fromQuery ?? '')
    if ([...wanted].every(([key, value]) => query.get(key) === value)) return legacy.to
  }
  return null
}

/**
 * Every page the app had when F-F4 began (2026-11-09), one address each (an id is a sample: the end-to-end test opens
 * each with ids of its own). Only grows: an address in it that stops being a page must get a {@link LEGACY_APP_PATHS}
 * entry, and the test fails until it does.
 */
export const OLD_ROUTES: readonly string[] = [
  '/',
  '/?q=sample',
  '/?shorts=sample',
  '/p/sample',
  '/history',
  '/play/sample',
  '/result/sample',
  '/scan',
  '/redeem/sample',
  '/pass/sample',
  '/inbox',
  '/inbox/message/sample',
  '/inbox/broadcast/sample',
  '/conversations',
  '/conversations/sample',
  '/account',
  '/account?section=details',
  '/account/profile',
  '/account/details',
  '/account/certificates',
  '/account/security',
  '/account/mail',
  '/account/preferences',
  '/account/id',
  '/account/delete',
  '/wallet',
  '/wallet/balance',
  '/wallet/rewards',
  '/wallet/history',
  '/wallet/kudos/PLATFORM',
  '/wallet/kudos/SPONSOR_CASH',
  '/wallet/kudos/OFFLINE_REDEEM',
  '/wallet/kudos/DIGITAL_PRIZE',
  '/wallet/payout',
  '/wallet/bank',
  '/referrals',
  '/referral/sample',
  '/my-sponsor',
  '/staff',
  '/staff/handled',
  '/welcome',
  '/help',
  '/about',
  '/legal/terms',
  '/legal/sponsor-terms',
  '/legal/privacy',
  '/legal/commerce',
  '/legal/ai',
  '/legal/contest-rules',
  '/verify/sample',
  '/u/sample',
  '/s/sample',
  '/login',
  '/signup',
  '/password-reset',
  '/login/oidc',
  '/mail/unsubscribe',
  '/delete-account',
  '/auth/verify-email',
  '/auth/join',
  '/auth/password-reset',
  '/auth/email-link',
  '/auth/account-deletion',
  '/auth/invitation',
  '/auth/passkey-confirm',
]
