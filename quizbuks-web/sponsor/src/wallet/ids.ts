/**
 * The names the sponsor console's wallet calls on the server (backend/quizbuks, package `wallet`;
 * docs/quizbuks/plans/Q6-payments.md). Only the sponsor console tops up, so they live here rather than in the shared
 * `src/qb/ids.ts` (backend/quizbuks/CLAUDE.md section 5).
 */

/** Processes (`wallet.TopUpProcesses`). */
export const WALLET_PROCESS = {
  topUpStart: 'QB_TOPUP_START',
  /** Issues the receipt of a paid top-up, or gives the one issued before (`wallet.TopUpReceipts`, Q9d). */
  receiptIssue: 'QB_TOPUP_RECEIPT_ISSUE',
} as const

/** SQL templates (`queries/qb/sponsor`). */
export const WALLET_TEMPLATE = {
  wallet: 'qb.sponsor.wallet',
  topUps: 'qb.sponsor.topups',
  /** Q10b: the top-up's range and the new sponsors' limit. */
  topUpLimit: 'qb.sponsor.topup-limit',
} as const

/** The sponsor's own top-ups, read only (the checkout's address included: it is theirs). */
export const WALLET_DATASET = {
  topUps: 'urn:jabiz:dataset:sponsor:QbTopUp',
} as const

/** Dictionaries, labelled by the server in the request's language. */
export const WALLET_DICTIONARY = {
  topUpStatus: 'urn:jabiz:dict:quizbuks:topup-status',
} as const
