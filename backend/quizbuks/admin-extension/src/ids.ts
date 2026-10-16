/**
 * The names the QuizBuks admin extension calls on the server (backend/quizbuks; docs/quizbuks/plans/Q9a-admin-extension.md).
 * QuizBuks has no endpoints of its own: the pages read the administrators' datasets and SQL templates, and the decision
 * of a review is the platform's `ApprovalPanel` (`APPROVAL_DECIDE`).
 */

/** The administrators' (default) datasets, read only. */
export const DATASET = {
  publication: 'urn:jabiz:dataset:default:QbPublication',
  tier: 'urn:jabiz:dataset:default:QbRewardTier',
  /** A version holds the correct answers: only `qb.admin.quiz.read` (the content administrators) reads it. */
  version: 'urn:jabiz:dataset:default:QbQuizVersion',
  sponsor: 'urn:jabiz:dataset:default:QbSponsor',
  ban: 'urn:jabiz:dataset:default:QbUserBan',
} as const

/** SQL templates (`queries/qb/admin`). */
export const TEMPLATE = {
  /** The review queue: no money (the content administrators read it). */
  publications: 'qb.admin.publications',
  /** A publication's sponsor's funds; `qb.admin.finance.read`. */
  publicationFunds: 'qb.admin.publication-funds',
  sponsors: 'qb.admin.sponsors',
  users: 'qb.admin.users',
  /** Attempts without their amounts. */
  attempts: 'qb.admin.attempts',
  /** The four below need `qb.admin.finance.read`. */
  attemptRewards: 'qb.admin.attempt-rewards',
  transfers: 'qb.admin.transfers',
  topUps: 'qb.admin.topups',
  payoutAccounts: 'qb.admin.payout-accounts',
} as const

/** Dictionaries, labelled by the server in the request's language. */
export const DICTIONARY = {
  region: 'urn:jabiz:dict:quizbuks:region',
  publicationStatus: 'urn:jabiz:dict:quizbuks:publication-status',
  sponsorReviewStatus: 'urn:jabiz:dict:quizbuks:sponsor-review-status',
  materialKind: 'urn:jabiz:dict:quizbuks:material-kind',
  attemptStatus: 'urn:jabiz:dict:quizbuks:attempt-status',
  rewardStatus: 'urn:jabiz:dict:quizbuks:reward-status',
  transferStatus: 'urn:jabiz:dict:quizbuks:transfer-status',
  topUpStatus: 'urn:jabiz:dict:quizbuks:topup-status',
  payoutAccountStatus: 'urn:jabiz:dict:quizbuks:payout-account-status',
} as const

/** Permissions (`QbPermissions`): they only decide what the pages offer; the server checks every call. */
export const PERMISSION = {
  reviewPublication: 'qb.review.publication',
  reviewSponsor: 'qb.review.sponsor',
  quizRead: 'qb.admin.quiz.read',
  usersRead: 'qb.admin.users.read',
  financeRead: 'qb.admin.finance.read',
} as const

/** The widths the policy `qb.content.image` keeps (a smaller image is answered with the original). */
export const CONTENT_IMAGE_VARIANTS = ['w320', 'w640', 'w1280']
/** The widths the policy `qb.sponsor-avatar` keeps. */
export const AVATAR_VARIANTS = ['w128', 'w256']

/** The most rows one request asks for (the platform's page limit). */
export const PAGE_LIMIT = 100

/** A user id as the platform makes them (a UUID); anything else in an address is no user. */
export const isUserId = (value: string) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)

/** The ledger's currency: the administration shows the real one (backend/quizbuks/CLAUDE.md section 4). */
export const CURRENCY = 'JPY'

/** The publication state a review waits in, and the onboarding state. */
export const PENDING_REVIEW = 'PENDING_REVIEW'
export const SPONSOR_PENDING = 'PENDING'

/** The extension's own pages (not platform paths, `PLATFORM_PATHS`). */
export const PATH = {
  reviews: '/qb/reviews',
  publication: (publicationId: string) => `/qb/reviews/publications/${encodeURIComponent(publicationId)}`,
  sponsor: (userId: string) => `/qb/reviews/sponsors/${encodeURIComponent(userId)}`,
  users: '/qb/users',
  user: (userId: string) => `/qb/users/${encodeURIComponent(userId)}`,
} as const
