/** The names of the drafting from a website (F-C7; backend `content.ai.SiteProfileProcesses`), permission `qb.ai.use`. */
export const SITE_PROFILE = {
  request: 'QB_SITE_PROFILE_REQUEST',
  /** The sponsor's own site drafts, newest first (`queries/qb/sponsor/site-profile.sql`). */
  template: 'qb.sponsor.site-profile',
} as const

/** The reasons a job's stored `failureCode` can name; others get the general text. */
export const SITE_FAILURES = ['QB_AI_SITE_UNREACHABLE', 'QB_AI_SITE_ROBOTS', 'QB_AI_SITE_NO_TEXT',
  'QB_AI_SITE_TIMEOUT', 'QB_AI_NO_CANDIDATES'] as const

/** A draft is looked at this often while it is made, and for this long at most (the reading takes 1 to 3 minutes). */
export const SITE_POLL = { everyMs: 5_000, forMs: 5 * 60_000 } as const
