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
  /** Q87a: cash of the cash account into Kudos, and back (`wallet.KudosExchanges`). */
  kudosBuy: 'QB_KUDOS_BUY',
  kudosSell: 'QB_KUDOS_SELL',
  /** Q87b (`fee.FeeProcesses`): an invoice paid from the cash account; its document, issued once and kept. */
  invoicePay: 'QB_FEE_INVOICE_PAY',
  /** Q87b: the postpaid fees not invoiced yet, paid early (each gets an invoice of its own, paid). */
  payEarly: 'QB_FEE_PAY_EARLY',
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
  /** Q87a: where top-ups may go and whether cash and Kudos may be exchanged (the controlled switches). */
  cashSettings: 'qb.sponsor.cash-settings',
  /** Q87b: the platform's invoices, the fees charged and an invoice's lines. */
  feeInvoices: 'qb.sponsor.fee-invoices',
  feeCharges: 'qb.sponsor.fee-charges',
  feeInvoiceLines: 'qb.sponsor.fee-invoice-lines',
  /** F-M1: the account's lots with money left, and how each fee was paid. */
  feeAccountLots: 'qb.sponsor.fee-account-lots',
  feePayments: 'qb.sponsor.fee-payments',
} as const

/** The sponsor's own top-ups, read only (the checkout's address included: it is theirs). */
export const WALLET_DATASET = {
  topUps: 'urn:jabiz:dataset:sponsor:QbTopUp',
} as const

/** Dictionaries, labelled by the server in the request's language. */
export const WALLET_DICTIONARY = {
  topUpStatus: 'urn:jabiz:dict:quizbuks:topup-status',
  /** Q87b: the states of the platform's invoices. */
  feeInvoiceStatus: 'urn:jabiz:dict:quizbuks:fee-invoice-status',
  /** F-M1: the account's sub-accounts and where its lots came from. */
  feeSubAccount: 'urn:jabiz:dict:quizbuks:fee-sub-account',
  feeCreditSource: 'urn:jabiz:dict:quizbuks:fee-credit-source',
  cashSource: 'urn:jabiz:dict:quizbuks:cash-source',
} as const
