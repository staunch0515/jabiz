import {
  FileQuestion,
  Gift,
  Handshake,
  House,
  Library,
  Megaphone,
  MessagesSquare,
  Store,
  Wallet,
  type LucideIcon,
} from 'lucide-react'
import { BENEFIT_CONVERSATIONS_PATH, BENEFITS_PATH, OFFERS_PATH, PURCHASES_PATH } from '../benefits/api'
import { QUIZZES_PATH } from '../content/paths'
import { PROFILE_PATH, profilePath } from '../profile/paths'
import { PUBLICATIONS_PATH } from '../publishing/paths'
import {
  AI_CREDIT_PATH,
  ASSETS_PATH,
  BILLS_PATH,
  CASH_REWARDS_PATH,
  CONVERSATIONS_PATH,
  DIGITAL_PRIZES_PATH,
  FEE_ACCOUNT_PATH,
  HOME_PATH,
  MAP_PATH,
  NOTICES_PATH,
  REDEEM_PATH,
  TRANSACTIONS_PATH,
  WALLET_PATH,
} from './paths'

/**
 * The Sponsor app's menu (F-F3; Q108 section 2): the home and eight areas, each a place with its pages as tabs under
 * the title. **New features add a tab (or an entry of the avatar menu), never a menu entry nor a bottom-menu entry**
 * (backend/quizbuks/CLAUDE.md section 5). The menu, the tabs, the bottom menu, "More" and the areas' root addresses all
 * come from this one list.
 */
export type AreaKey = 'home' | 'quizzes' | 'publications' | 'prizes' | 'messages' | 'benefits' | 'money' | 'assets'
  | 'hub'

/** What decides whether an area or a tab shows (the server decides what may be done; this only hides). */
export interface ShellState {
  /** The Sponsor may pay rewards in the platform's Kudos (Q28): the Kudos wallet and its top-ups. */
  kudos: boolean
  /** The Sponsor holds one of the benefit roles (BENEFIT_PROVIDER, BENEFIT_SPONSOR; `qb.sponsor.benefit-access`). */
  benefits: boolean
}

export interface AreaTab {
  /** Unique within the area: the tab's text is `shell.tabs.<area>.<key>`, its test id `page-tab-<key>`. */
  key: string
  path: string
  when?: (state: ShellState) => boolean
}

export interface Area {
  /** The area's name is `shell.areas.<key>`, its short name in the bottom menu `shell.bottom.<key>`. */
  key: AreaKey
  icon: LucideIcon
  tabs: readonly AreaTab[]
  when?: (state: ShellState) => boolean
}

export const AREAS: readonly Area[] = [
  { key: 'home', icon: House, tabs: [{ key: 'home', path: HOME_PATH }] },
  { key: 'quizzes', icon: FileQuestion, tabs: [{ key: 'quizzes', path: QUIZZES_PATH }] },
  { key: 'publications', icon: Megaphone, tabs: [
    { key: 'publications', path: PUBLICATIONS_PATH },
    { key: 'map', path: MAP_PATH },
  ] },
  { key: 'prizes', icon: Gift, tabs: [
    { key: 'redeem', path: REDEEM_PATH },
    { key: 'cash', path: CASH_REWARDS_PATH },
    { key: 'digital', path: DIGITAL_PRIZES_PATH },
  ] },
  // The "Customers" tab (the people on the map) comes later (the controller, 2026-11-09): nothing points to it.
  { key: 'messages', icon: MessagesSquare, tabs: [
    { key: 'conversations', path: CONVERSATIONS_PATH },
    { key: 'notices', path: NOTICES_PATH },
  ] },
  { key: 'benefits', icon: Handshake, when: (state) => state.benefits, tabs: [
    { key: 'catalog', path: BENEFITS_PATH },
    { key: 'offers', path: OFFERS_PATH },
    { key: 'conversations', path: BENEFIT_CONVERSATIONS_PATH },
    { key: 'purchases', path: PURCHASES_PATH },
  ] },
  { key: 'money', icon: Wallet, tabs: [
    { key: 'transactions', path: TRANSACTIONS_PATH },
    { key: 'wallet', path: WALLET_PATH, when: (state) => state.kudos },
    { key: 'account', path: FEE_ACCOUNT_PATH },
    { key: 'ai', path: AI_CREDIT_PATH },
    { key: 'bills', path: BILLS_PATH },
  ] },
  { key: 'assets', icon: Library, tabs: [
    { key: 'all', path: ASSETS_PATH },
    { key: 'media', path: `${ASSETS_PATH}/media` },
    { key: 'text', path: `${ASSETS_PATH}/text` },
    { key: 'prize', path: `${ASSETS_PATH}/prizes` },
    { key: 'products', path: `${ASSETS_PATH}/products` },
  ] },
  { key: 'hub', icon: Store, tabs: [
    { key: 'overview', path: PROFILE_PATH },
    { key: 'basics', path: profilePath('basics') },
    { key: 'page', path: profilePath('page') },
    { key: 'staff', path: profilePath('staff') },
    { key: 'mail-server', path: profilePath('mail-server') },
  ] },
]

export const AREA_KEYS: readonly AreaKey[] = AREAS.map((area) => area.key)

/** Whether the area shows. */
export const areaShown = (area: Area, state: ShellState) => area.when?.(state) ?? true

/** The tabs of an area that show. */
export function shownTabs(area: Area, state: ShellState): AreaTab[] {
  return area.tabs.filter((tab) => tab.when?.(state) ?? true)
}

/** Where an area opens: its first tab that shows. */
export function rootOf(area: Area, state: ShellState): string {
  return (shownTabs(area, state)[0] ?? area.tabs[0]).path
}

const under = (path: string, prefix: string) => path === prefix || path.startsWith(`${prefix}/`)

/**
 * The area and tab of a path: the tab whose address is the path or the longest one the path is under (a publication's
 * page is under the publications' tab, `/benefits/offers` is the offers' tab, not the catalog's). Null outside the
 * areas (the account, the help).
 */
export function placeOf(path: string): { area: Area; tab: AreaTab } | null {
  let best: { area: Area; tab: AreaTab } | null = null
  for (const area of AREAS) {
    for (const tab of area.tabs) {
      if (under(path, tab.path) && (!best || tab.path.length > best.tab.path.length)) best = { area, tab }
    }
  }
  return best
}

/** The area by its key. */
export function areaOf(key: string): Area | undefined {
  return AREAS.find((area) => area.key === key)
}

/**
 * The bottom menu's areas from the parameter `qb.sponsor.bottom-bar` (comma-separated area keys): unknown keys and
 * repeats are left out, in the parameter's order.
 */
export function bottomBarAreas(value: string): AreaKey[] {
  const keys: AreaKey[] = []
  for (const part of value.split(',')) {
    const key = part.trim() as AreaKey
    if ((AREA_KEYS as readonly string[]).includes(key) && !keys.includes(key)) keys.push(key)
  }
  return keys
}
