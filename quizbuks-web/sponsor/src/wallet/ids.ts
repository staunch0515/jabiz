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
  /** Bank transfers (`wallet.BankTopUps`, Q13a): ask for one, say it was sent, withdraw it. */
  bankStart: 'QB_TOPUP_BANK_START',
  bankReport: 'QB_TOPUP_BANK_REPORT',
  bankCancel: 'QB_TOPUP_BANK_CANCEL',
  /** Q87 (`wallet.KudosExchange`): cash in the sponsor's account becomes Kudos one for one, and back when allowed. */
  kudosBuy: 'QB_KUDOS_BUY',
  kudosSell: 'QB_KUDOS_SELL',
  /** Q87 (`fee.FeeProcesses`): an invoice paid from the cash account; its document, issued once and kept. */
  invoicePay: 'QB_FEE_INVOICE_PAY',
  invoiceDocument: 'QB_FEE_INVOICE_DOCUMENT',
} as const

/** SQL templates (`queries/qb/sponsor`). */
export const WALLET_TEMPLATE = {
  wallet: 'qb.sponsor.wallet',
  topUps: 'qb.sponsor.topups',
  /** Q10b: the top-up's range and the new sponsors' limit. */
  topUpLimit: 'qb.sponsor.topup-limit',
  /** Q13a: the platform's receiving account and a bank top-up's range. */
  bankAccount: 'qb.sponsor.bank-account',
  /** Q87: the switches of the top-up's target and the exchange, the exchanges, the platform's fees and invoices. */
  topUpSettings: 'qb.sponsor.topup-settings',
  kudosExchanges: 'qb.sponsor.kudos-exchanges',
  feeInvoices: 'qb.sponsor.fee-invoices',
  feeCharges: 'qb.sponsor.fee-charges',
  feeInvoiceLines: 'qb.sponsor.fee-invoice-lines',
} as const

/** The sponsor's own top-ups, read only (the checkout's address included: it is theirs). */
export const WALLET_DATASET = {
  topUps: 'urn:jabiz:dataset:sponsor:QbTopUp',
} as const

/** Dictionaries, labelled by the server in the request's language. */
export const WALLET_DICTIONARY = {
  topUpStatus: 'urn:jabiz:dict:quizbuks:topup-status',
  topUpTarget: 'urn:jabiz:dict:quizbuks:topup-target',
  feeInvoiceStatus: 'urn:jabiz:dict:quizbuks:fee-invoice-status',
} as const
