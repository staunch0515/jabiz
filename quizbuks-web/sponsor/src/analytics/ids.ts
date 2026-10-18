/**
 * The names the sponsor console's analytics pages call on the server (backend/quizbuks, `queries/qb/sponsor`;
 * docs/quizbuks/plans/Q9e-analytics.md). Only the sponsor console has them, so they live here rather than in the
 * shared `src/qb/ids.ts` (backend/quizbuks/CLAUDE.md section 5).
 */

/** SQL templates (permission `qb.stats.read`), all limited to the sponsor's own publications. */
export const ANALYTICS_TEMPLATE = {
  dashboard: 'qb.sponsor.dashboard',
  trend: 'qb.sponsor.analytics.trend',
  trendTotals: 'qb.sponsor.analytics.trend-totals',
  top: 'qb.sponsor.analytics.top',
  overview: 'qb.sponsor.analytics.overview',
  questions: 'qb.sponsor.analytics.questions',
} as const

export type AnalyticsTemplate = (typeof ANALYTICS_TEMPLATE)[keyof typeof ANALYTICS_TEMPLATE]
