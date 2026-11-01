/**
 * The names the app's referral pages call on the server (Q70, docs/quizbuks/plans/Q70-referrals-and-rounds.md). Only
 * the app shows referrals to referrers and referees, so they live here rather than in the shared `src/qb/ids.ts`.
 */

export const REFERRAL_PROCESS = {
  /** The caller's link to a publication (a new one needs the referrer certification). */
  link: 'QB_REFERRAL_LINK',
  /** A friend's link opened: counted when the publication's rules say so, else nothing is recorded. */
  claim: 'QB_REFERRAL_CLAIM',
  /** One of the caller's referrals, with the record of its reward. */
  view: 'QB_REFERRAL_VIEW',
} as const

export const REFERRAL_TEMPLATE = {
  referrals: 'qb.app.referrals',
  links: 'qb.app.referral-links',
} as const

/** The certification a referrer holds (Q70, D-Q70-8). */
export const REFERRER = 'REFERRER'
