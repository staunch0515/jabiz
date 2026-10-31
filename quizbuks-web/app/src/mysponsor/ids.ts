/**
 * The names the app's sponsor page calls on the server (Q68, docs/quizbuks/plans/Q68-sponsor-in-app.md). Only the
 * app shows them this way, so they live here rather than in the shared `src/qb/ids.ts` (backend/quizbuks/CLAUDE.md
 * section 5). All are allowed to the app's sign-in (`qb.sponsor.app`) and read or touch the caller's own only.
 */

/** SQL templates: the app's own two, and the sponsor console's dashboard and trend, shared with it. */
export const MY_SPONSOR_TEMPLATE = {
  account: 'qb.app.my-sponsor',
  publications: 'qb.app.sponsor-publications',
  dashboard: 'qb.sponsor.dashboard',
  trend: 'qb.sponsor.analytics.trend',
} as const

/** The only writes: pausing and resuming one's own publication (they move no Kudos). */
export const MY_SPONSOR_PROCESS = {
  pause: 'QB_PUBLICATION_PAUSE',
  resume: 'QB_PUBLICATION_RESUME',
} as const
