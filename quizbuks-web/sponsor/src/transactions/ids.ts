/**
 * The names the sponsor console's transactions page calls on the server (backend/quizbuks, `queries/qb/sponsor`;
 * docs/quizbuks/plans/Q9b-sponsor-transactions.md). Only the sponsor console has it, so they live here rather than in
 * the shared `src/qb/ids.ts` (backend/quizbuks/CLAUDE.md section 5). The publications offered as a filter come from
 * the publishing pages' template (`publishing/ids.ts`).
 */

/** SQL templates: the rows, and the totals of the same rows for the same parameters. */
export const TRANSACTIONS_TEMPLATE = {
  list: 'qb.sponsor.transactions',
  summary: 'qb.sponsor.transaction-summary',
} as const
