import { HELP_THREAD_PARAM } from '../qb/help'
import { itemPage } from '../routes'

/*
 * The AI help's page names of the app (F-C10c): the server takes only the names of its list (`help.HelpPages`, the
 * entry `app`) and drops any other; a path never leaves the page. The answering pages (`/play/…`, an attempt's result)
 * have no name and no help. A page renamed here is renamed there too (`pages.test.ts` compares the two lists).
 */

/** The app's names on the server's list, in its order. */
export const APP_PAGE_NAMES = ['home', 'shorts', 'publication', 'history', 'wallet', 'inbox', 'conversations',
  'referrals', 'certificates', 'account', 'profile', 'help', 'redeem', 'my-sponsor'] as const

export type AppPageName = (typeof APP_PAGE_NAMES)[number]

/**
 * The name of a page of the app (`/wallet/payout` → `wallet`; the home with `?shorts=` → `shorts`); null for a page
 * with none (answering, the rest).
 */
export function appPageName(path: string, search = ''): AppPageName | null {
  const item = itemPage(path)
  if (item) return item.page === 'p' ? 'publication' : item.page === 'redeem' ? 'redeem' : null
  const [head, second] = path.split('?')[0].split('/').filter(Boolean)
  if (!head) return new URLSearchParams(search).get('shorts') ? 'shorts' : 'home'
  switch (head) {
    case 'history': return 'history'
    case 'wallet': return 'wallet'
    case 'inbox': return 'inbox'
    case 'conversations': return 'conversations'
    case 'referrals': return 'referrals'
    case 'certificates': return 'certificates'
    case 'help': return 'help'
    case 'my-sponsor': return 'my-sponsor'
    case 'account': return second === 'profile' ? 'profile' : second === 'certificates' ? 'certificates'
      : second === 'referrals' ? 'referrals' : 'account'
    default: return null
  }
}

/** The last page of the app the Taker was on before the help (this tab, this load). */
let previous: { path: string; search: string } | null = null

/** Notes the page the Taker is on (the app's router calls it on every page; the help itself is not noted). */
export function notePage(path: string, search = '') {
  if (appPageName(path, search) !== 'help') previous = { path, search }
}

/** The name sent with a question: the page the Taker came from (null for an answering page), else the help itself. */
export function askedFrom(): AppPageName | null {
  return previous === null ? 'help' : appPageName(previous.path, previous.search)
}

/** For the tests: forgets the page noted. */
export function forgetPage() {
  previous = null
}

/** The help page's address with a conversation open (an inbox message's link, F-C10c). */
export function helpThreadPath(threadId: string): string {
  return `/help?${HELP_THREAD_PARAM}=${encodeURIComponent(threadId)}`
}
