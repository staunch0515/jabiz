/**
 * The names the app's report of a quiz calls on the server (Q49a, docs/quizbuks/plans/Q49a-contest-report.md). Only
 * the app reports quizzes, so they live here rather than in the shared `src/qb/ids.ts`.
 */

export const REPORT_PROCESS = {
  /** A taker reports a quiz: a reason and an optional note. */
  submit: 'QB_REPORT_SUBMIT',
} as const

/** The reasons, as the server takes them (`QbReports.REASONS`), in the order the dialog offers them. */
export const REPORT_REASONS = ['MISLEADING', 'INAPPROPRIATE', 'REWARD_NOT_GIVEN', 'SPAM', 'OTHER'] as const
export type ReportReason = (typeof REPORT_REASONS)[number]

/** The longest note the server keeps (`QbReports.NOTE_LENGTH`). */
export const REPORT_NOTE_LENGTH = 500
