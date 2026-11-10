import { ASSETS_PATH, BILLS_PATH, CONVERSATIONS_PATH, DIGITAL_PRIZES_PATH, FEE_ACCOUNT_PATH, REDEEM_PATH, WALLET_PATH,
} from '../shell/paths'
import { profilePath, PROFILE_PATH } from '../profile/paths'
import { PUBLICATIONS_PATH } from '../publishing/paths'
import { NO_TODO, type TodoColumn, type TodoCounts } from './api'

/**
 * What the home's "to do" list is made of (F-F3b, docs/quizbuks/plans/impl/F-F3.md section 3): each item is a count and
 * the page that handles it. The server's counts come from `qb.sponsor.todo`; the unread conversations (the menu's
 * badge), the application in review and the profile's missing parts are what the page already reads. An item with
 * none is not shown. AI credit short (F-M9) is not here yet: its column and a row of its own come with that item.
 */
export type TodoKey = TodoColumn | 'unreadConversations' | 'onboardingInReview' | 'profileMissing'

export interface TodoItem {
  key: TodoKey
  count: number
  /** The page that handles it, straight away. */
  path: string
}

/** The places the items lead to. */
export const TODO_PATH: Record<TodoKey, string> = {
  claimsToConfirm: REDEEM_PATH,
  digitalToSend: DIGITAL_PRIZES_PATH,
  unreadConversations: CONVERSATIONS_PATH,
  fundsShort: `${PUBLICATIONS_PATH}?status=FUNDS_SHORT`,
  rewardsWaiting: WALLET_PATH,
  invoicesOverdue: BILLS_PATH,
  creditsExpiring: FEE_ACCOUNT_PATH,
  onboardingInReview: PROFILE_PATH,
  publicationsInReview: `${PUBLICATIONS_PATH}?status=PENDING_REVIEW`,
  pageInReview: profilePath('page'),
  sharedVideosInReview: `${ASSETS_PATH}/media`,
  videoUsesToConfirm: `${ASSETS_PATH}/media`,
  profileMissing: PROFILE_PATH,
}

/** The order of the list: what waits for a person first, then money, then what is in review, then the profile. */
export const TODO_ORDER: readonly TodoKey[] = ['claimsToConfirm', 'digitalToSend', 'unreadConversations', 'fundsShort',
  'rewardsWaiting', 'invoicesOverdue', 'creditsExpiring', 'onboardingInReview', 'publicationsInReview', 'pageInReview',
  'sharedVideosInReview', 'videoUsesToConfirm', 'profileMissing']

export interface TodoInput {
  /** The server's counts; none until they are read (or when they could not be). */
  counts: TodoCounts | undefined
  unreadConversations: number
  /** The application is in review. */
  onboardingInReview: boolean
  /** The profile's parts still missing that are needed to publish (none until the checklist is read). */
  profileMissing: number
}

/** The items to show, in order; those with nothing to do are left out. */
export function todoItems(input: TodoInput): TodoItem[] {
  const counts = { ...NO_TODO, ...input.counts }
  const count: Record<TodoKey, number> = {
    ...counts,
    unreadConversations: input.unreadConversations,
    onboardingInReview: input.onboardingInReview ? 1 : 0,
    profileMissing: input.profileMissing,
  }
  return TODO_ORDER.filter((key) => count[key] > 0).map((key) => ({ key, count: count[key], path: TODO_PATH[key] }))
}

/** The prizes area's badge: claims waiting for a confirmation and digital prizes waiting for the sponsor. */
export function prizesBadge(counts: TodoCounts | undefined): number {
  return counts ? counts.claimsToConfirm + counts.digitalToSend : 0
}
