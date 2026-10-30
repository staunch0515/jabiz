/**
 * The names the app's wallet calls on the server (backend/quizbuks, package `wallet`; docs/quizbuks/plans/
 * Q6-payments.md, Q6b). Only the app shows a taker's wallet and payout account, so they live here rather than in the
 * shared `src/qb/ids.ts` (backend/quizbuks/CLAUDE.md section 5).
 */

/** Processes (`wallet.PayoutProcesses`). */
export const WALLET_PROCESS = {
  payoutOnboard: 'QB_PAYOUT_ONBOARD',
  /** Q13b (`wallet.BankWithdrawals`): the bank account, how the taker is paid, and a withdrawal. */
  bankAccountSave: 'QB_BANK_ACCOUNT_SAVE',
  payoutMethodSet: 'QB_PAYOUT_METHOD_SET',
  withdrawalRequest: 'QB_WITHDRAWAL_REQUEST',
} as const

/** SQL templates (`queries/qb/app`). */
export const WALLET_TEMPLATE = {
  wallet: 'qb.app.wallet',
  ledger: 'qb.app.ledger',
  payoutAccount: 'qb.app.payout-account',
  bankAccount: 'qb.app.bank-account',
  /** Q58: the Kudos by reward method. */
  kudos: 'qb.app.kudos',
} as const

/** Dictionaries, labelled by the server in the request's language. */
export const WALLET_DICTIONARY = {
  payoutAccountStatus: 'urn:jabiz:dict:quizbuks:payout-account-status',
} as const
