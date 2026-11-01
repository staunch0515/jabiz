/**
 * The names the sponsor console's analytics pages call on the server (backend/quizbuks, `queries/qb/sponsor`;
 * docs/quizbuks/plans/Q9e-analytics.md). Only the sponsor console has them, so they live here rather than in the
 * shared `src/qb/ids.ts` (backend/quizbuks/CLAUDE.md section 5).
 */

/** SQL templates (permission `qb.stats.read`; the dashboard and the trend `qb.sponsor.app`, Q68), all limited to the sponsor's own publications. */
export const ANALYTICS_TEMPLATE = {
  dashboard: 'qb.sponsor.dashboard',
  trend: 'qb.sponsor.analytics.trend',
  trendTotals: 'qb.sponsor.analytics.trend-totals',
  top: 'qb.sponsor.analytics.top',
  overview: 'qb.sponsor.analytics.overview',
  questions: 'qb.sponsor.analytics.questions',
  /** The versions of its quiz a publication ran on (Q51b), whose questions are analysed apart. */
  versions: 'qb.sponsor.analytics.versions',
  /** Q73e-1: the publication by the language its attempts are answered in. */
  languages: 'qb.sponsor.analytics.languages',
} as const

export type AnalyticsTemplate = (typeof ANALYTICS_TEMPLATE)[keyof typeof ANALYTICS_TEMPLATE]
