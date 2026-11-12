import { api, fileNameOf, runProcess, runQuery, sessionFetch, toApiError, type Filter, type Sort } from '@jabiz/client'
import { readDictionary, type DictionaryItem } from '../qb/api'
import { WALLET_DATASET, WALLET_DICTIONARY, WALLET_PROCESS, WALLET_TEMPLATE } from './ids'
import { WALLET_PATH } from '../shell/paths'

/*
 * The sponsor wallet's calls, typed by hand after the Java records and templates of backend/quizbuks `wallet`
 * (processes have no OpenAPI types of their own). The end-to-end test `topup.spec.ts` runs every one of them against
 * the server. Amounts stay as the server sends them (whole Kudos) and are shown only through `formatKudos`.
 */

/**
 * `TopUpStatus`: only the server's notices from Stripe move a card top-up on, and only finance with another
 * administrator's approval a bank one; the page only reads it.
 */
export type TopUpStatus = 'OPENING' | 'OPEN' | 'PAID' | 'EXPIRED' | 'FAILED' | 'AWAITING' | 'REPORTED' | 'REVIEW'
  | 'CANCELLED'
export const FINAL_STATUSES: readonly TopUpStatus[] = ['PAID', 'EXPIRED', 'FAILED']
/**
 * How a top-up is paid: by card on Stripe's page, by a transfer to the platform's bank account (Q13a), or credited by
 * the platform's team by hand (Q18b: money received outside, or Kudos granted).
 */
export type TopUpMethod = 'STRIPE' | 'BANK' | 'MANUAL'
/** A manual credit's kind: money received outside the system (it has a receipt), or Kudos granted (none). */
export type ManualKind = 'PAID_OUTSIDE' | 'GRANT'
export const methodOf = (value: unknown): TopUpMethod => value === 'BANK' || value === 'MANUAL' ? value : 'STRIPE'
export const manualKindOf = (value: unknown): ManualKind | null =>
  value === 'PAID_OUTSIDE' || value === 'GRANT' ? value : null
/** The bank top-ups the sponsor may still report or withdraw (no money recorded yet). */
export const BANK_WAITING: readonly TopUpStatus[] = ['AWAITING', 'REPORTED']

/** The row of `qb.sponsor.wallet` (M-04). */
export interface SponsorWallet {
  balance: number
  /** Held while a dispute is open: not available for rewards. */
  held: number
  /** Rewards waiting for a top-up, in their order. */
  pendingCount: number
  pendingAmount: number
  toppedUp: number
  takenBack: number
  topUpCount: number
  spent: number
  /** Q87a: the cash account (money with the platform, turned into Kudos when the sponsor wants). */
  cashBalance: number
  cashHeld: number
  /** Q87b: the platform's invoices not yet paid, how many of them are overdue, and how the sponsor is billed. */
  unpaidTotal: number
  unpaidCount: number
  overdueCount: number
  billingMode: BillingMode
  creditLimit: number
  /** Q87b: the postpaid fees charged but not on an invoice yet (paid early from the page, or invoiced monthly). */
  uninvoicedTotal: number
  /** Q88b: the entry fees takers paid, waiting for the daily settlement into the cash account. */
  entryFeesUnsettled: number
  /** F-M1: the granted credit (platform fee Kudos), spent on the platform's fees before the cash. */
  feeCreditBalance: number
  /** F-M4: what is left of the cash the Sponsor's operator moved to it: platform fees only, never Kudos. */
  cashFromOperator: number
  /** F-M5: what the prepayments of the Sponsor's publications still hold (set aside, not yet charged or given back). */
  prepaymentHeld: number
  /** F-M7: the credit of credit notes not used yet; the next invoices take it first. */
  creditNoteCredit: number
}

/** How the platform's fees are billed (Q87b): taken from the cash account at once, or invoiced monthly within a credit. */
export type BillingMode = 'PREPAID' | 'POSTPAID'

/** A row of `qb.sponsor.topups` (the checkout's address only while it can be paid). */
export interface TopUpRow {
  topUpId: string
  amount: number
  status: TopUpStatus
  createdAt: string
  expiresAt: string | null
  checkoutUrl: string | null
  paidAt: string | null
  refundedAmount: number
  method: TopUpMethod
  transferCode: string | null
  manualKind: ManualKind | null
  /** Q87a: where it is credited. */
  target: TopUpTarget
}

/** The top-up as the sponsor's dataset has it. */
export interface TopUp {
  topUpId: string
  amount: number
  status: TopUpStatus
  createdAt: string
  expiresAt: string | null
  checkoutUrl: string | null
  paidAt: string | null
  method: TopUpMethod
  /** A bank transfer's: the code to put before the payer's name, and what the sponsor asked for and reported. */
  transferCode: string | null
  requestedAmount: number | null
  payerName: string | null
  transferDate: string | null
  /** The day the money arrived, once it is paid. */
  receivedOn: string | null
  /** A manual credit's kind (Q18b); null for the other methods. */
  manualKind: ManualKind | null
}

/** `TopUpProcesses.StartOutput`. */
export interface StartOutput {
  topUpId: string
  status: TopUpStatus
  jobId: string
}

type Row = Record<string, unknown>

const num = (value: unknown): number => {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}
const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))

export async function readWallet(): Promise<SponsorWallet> {
  const row = (await runQuery<Row>(WALLET_TEMPLATE.wallet, { limit: 1 })).items[0] ?? {}
  return {
    balance: num(row.balance),
    held: num(row.held),
    pendingCount: num(row.pendingCount),
    pendingAmount: num(row.pendingAmount),
    toppedUp: num(row.toppedUp),
    takenBack: num(row.takenBack),
    topUpCount: num(row.topUpCount),
    spent: num(row.spent),
    cashBalance: num(row.cashBalance),
    cashHeld: num(row.cashHeld),
    unpaidTotal: num(row.unpaidTotal),
    unpaidCount: num(row.unpaidCount),
    overdueCount: num(row.overdueCount),
    billingMode: row.billingMode === 'POSTPAID' ? 'POSTPAID' : 'PREPAID',
    creditLimit: num(row.creditLimit),
    uninvoicedTotal: num(row.uninvoicedTotal),
    entryFeesUnsettled: num(row.entryFeesUnsettled),
    feeCreditBalance: num(row.feeCreditBalance),
    cashFromOperator: num(row.cashFromOperator),
    prepaymentHeld: num(row.prepaymentHeld),
    creditNoteCredit: num(row.creditNoteCredit),
  }
}

/**
 * F-M1: what the account that pays the platform's fees may pay with now: the granted credit and the cash not held by a
 * dispute. Only for the buttons; the server allocates (and leaves out credit past its expiry).
 */
export const feeAccountAvailable = (wallet: SponsorWallet): number =>
  Math.max(0, wallet.feeCreditBalance) + Math.max(0, wallet.cashBalance - wallet.cashHeld)

/** Q87a: where a top-up is credited - the Kudos wallet or the cash account. */
export type TopUpTarget = 'KUDOS' | 'CASH'
export const TOPUP_TARGETS: readonly TopUpTarget[] = ['KUDOS', 'CASH']
export const targetOf = (value: unknown): TopUpTarget => (value === 'CASH' ? 'CASH' : 'KUDOS')

/** `qb.sponsor.cash-settings` (Q87a): the controlled switches, as the processes read them. */
export interface CashSettings {
  /** Where a top-up may go (at least one); the dialog offers a choice only when there are two. */
  targets: TopUpTarget[]
  defaultTarget: TopUpTarget
  buyEnabled: boolean
  sellEnabled: boolean
}

/** The targets a parameter lists, in the order the page shows them; Kudos when none is known. */
export function targetsOf(text: unknown): TopUpTarget[] {
  const listed = String(text ?? '').split(',').map((part) => part.trim().toUpperCase())
  const targets = TOPUP_TARGETS.filter((target) => listed.includes(target))
  return targets.length > 0 ? targets : ['KUDOS']
}

export async function readCashSettings(): Promise<CashSettings> {
  const row = (await runQuery<Row>(WALLET_TEMPLATE.cashSettings, { limit: 1 })).items[0] ?? {}
  const targets = targetsOf(row.targets)
  const fallback = targetOf(row.defaultTarget)
  return {
    targets,
    defaultTarget: targets.includes(fallback) ? fallback : targets[0],
    buyEnabled: row.buyEnabled === true,
    sellEnabled: row.sellEnabled === true,
  }
}

/** What the cash account can give now: its balance less what open disputes hold. */
export const availableCash = (wallet: SponsorWallet): number => Math.max(0, wallet.cashBalance - wallet.cashHeld)

/** F-M4: what the cash account can turn into Kudos: what it can give less what the operator moved to it. */
export const buyableCash = (wallet: SponsorWallet): number => Math.max(0, availableCash(wallet) - wallet.cashFromOperator)

/** `KudosExchanges.Output`. */
export interface ExchangeOutput {
  exchangeId: string
  amount: number
  kudosBalance: number
  cashBalance: number
}

/** Turns `amount` of cash into Kudos (`toKudos`) or Kudos back into cash, one to one (one per key). */
export const exchangeKudos = (toKudos: boolean, amount: number, idempotencyKey: string) =>
  runProcess<ExchangeOutput>(toKudos ? WALLET_PROCESS.kudosBuy : WALLET_PROCESS.kudosSell, { amount },
    { idempotencyKey })

/**
 * What the sponsor may top up now (`qb.sponsor.topup-limit`, Q10b): each top-up's range and, while the new sponsors'
 * limit applies, the limit in all, what remains of it and until when (null before the first paid top-up). The server
 * decides when the top-up starts; the page only says it beforehand.
 */
export interface TopUpLimit {
  min: number | null
  max: number | null
  limited: boolean
  maxTotal: number | null
  remaining: number | null
  until: string | null
}

const numOrNull = (value: unknown): number | null => (value == null || value === '' ? null : num(value))

export async function readTopUpLimit(): Promise<TopUpLimit> {
  const row = (await runQuery<Row>(WALLET_TEMPLATE.topUpLimit, { params: { at: new Date().toISOString() }, limit: 1 }))
    .items[0] ?? {}
  return {
    min: numOrNull(row.min),
    max: numOrNull(row.max),
    limited: row.limited === true || row.limited === 'true',
    maxTotal: numOrNull(row.maxTotal),
    remaining: numOrNull(row.remaining),
    until: str(row.until),
  }
}

/** What the wallet can pay rewards from: the balance less what open disputes hold (`QbWallets.available`). */
export const available = (wallet: SponsorWallet): number => Math.max(0, wallet.balance - wallet.held)

export interface TopUpSearch {
  sorts?: Sort[]
  offset: number
  limit: number
}

/** The sponsor's top-ups, one page, counted; `at` is now, so an expired checkout has no address. */
export async function searchTopUps({ sorts, offset, limit }: TopUpSearch) {
  const page = await runQuery<Row>(WALLET_TEMPLATE.topUps, {
    params: { at: new Date().toISOString() },
    sorts,
    offset,
    limit,
    count: true,
  })
  return {
    total: page.total,
    items: page.items.map((row): TopUpRow => ({
      topUpId: String(row.topUpId),
      amount: num(row.amount),
      status: row.status as TopUpStatus,
      createdAt: String(row.createdAt ?? ''),
      expiresAt: str(row.expiresAt),
      checkoutUrl: str(row.checkoutUrl),
      paidAt: str(row.paidAt),
      refundedAmount: num(row.refundedAmount),
      method: methodOf(row.method),
      transferCode: str(row.transferCode),
      manualKind: manualKindOf(row.manualKind),
      target: targetOf(row.target),
    })),
  }
}

/**
 * One of the sponsor's top-ups by its key; null when the sponsor's dataset does not have it (not theirs, or no such
 * top-up). The dataset is scoped to the caller, so another sponsor's top-up reads as missing.
 */
export async function readTopUp(topUpId: string): Promise<TopUp | null> {
  const { data, error, response } = await api.GET('/api/datasets/{resourceId}/entities/{id}', {
    params: { path: { resourceId: WALLET_DATASET.topUps, id: topUpId } },
  })
  if (response.status === 404) return null
  if (error !== undefined || !data) throw await toApiError(response, error)
  const row = (data.attributes ?? {}) as Row
  return {
    topUpId: String(row.topUpId ?? topUpId),
    amount: num(row.amount),
    status: row.status as TopUpStatus,
    createdAt: String(row.createdAt ?? ''),
    expiresAt: str(row.expiresAt),
    checkoutUrl: str(row.checkoutUrl),
    paidAt: str(row.paidAt),
    method: methodOf(row.method),
    transferCode: str(row.transferCode),
    requestedAmount: numOrNull(row.requestedAmount),
    payerName: str(row.payerName),
    transferDate: str(row.transferDate),
    receivedOn: row.status === 'PAID' ? str(row.receivedOn) : null,
    manualKind: manualKindOf(row.manualKind),
  }
}

/**
 * The checkout's address while the top-up can still be paid there (open and not expired by this browser's clock); the
 * caller still checks the address itself before following it (`stripePageUrl`).
 */
export function payableUrl(topUp: Pick<TopUp, 'status' | 'checkoutUrl' | 'expiresAt'>, now = Date.now()): string | null {
  if (topUp.status !== 'OPEN' || !topUp.checkoutUrl) return null
  if (topUp.expiresAt && Date.parse(topUp.expiresAt) <= now) return null
  return topUp.checkoutUrl
}

/**
 * Starts a top-up of `amount` whole Kudos. The same key is passed when the sponsor retries the same submission, so a
 * retried call starts one top-up only.
 */
export const startTopUp = (amount: number, idempotencyKey: string, target: TopUpTarget | null = null) =>
  runProcess<StartOutput>(WALLET_PROCESS.topUpStart, { amount, ...(target ? { target } : {}) }, { idempotencyKey })

/** The platform's receiving account (`qb.sponsor.bank-account`, Q13a); a part not set is null. */
export interface BankAccount {
  bank: string | null
  branch: string | null
  accountType: string | null
  accountNumber: string | null
  accountHolder: string | null
  min: number | null
  max: number | null
}

/** Whether every part of the account and both limits are set (until then the server refuses bank top-ups). */
export const bankAccountSet = (account: BankAccount) => Boolean(account.bank && account.branch
  && account.accountType && account.accountNumber && account.accountHolder)
  && account.min !== null && account.max !== null

export async function readBankAccount(): Promise<BankAccount> {
  const row = (await runQuery<Row>(WALLET_TEMPLATE.bankAccount, { limit: 1 })).items[0] ?? {}
  return {
    bank: str(row.bank),
    branch: str(row.branch),
    accountType: str(row.accountType),
    accountNumber: str(row.accountNumber),
    accountHolder: str(row.accountHolder),
    min: numOrNull(row.min),
    max: numOrNull(row.max),
  }
}

/** `BankTopUps.StartOutput`. */
export interface BankStartOutput {
  topUpId: string
  transferCode: string
  amount: number
  status: TopUpStatus
}

/** Asks for a bank top-up of `amount` whole Kudos (one per key, as with a card top-up). */
export const startBankTopUp = (amount: number, idempotencyKey: string, target: TopUpTarget | null = null) =>
  runProcess<BankStartOutput>(WALLET_PROCESS.bankStart, { amount, ...(target ? { target } : {}) },
    { idempotencyKey })

/** Says the money was sent: the payer's name as the bank shows it and the day (`YYYY-MM-DD`, in Japan). */
export const reportBankTransfer = (topUpId: string, payerName: string, transferDate: string) =>
  runProcess<unknown>(WALLET_PROCESS.bankReport, { topUpId, payerName, transferDate })

/** Withdraws a bank top-up no money was recorded against. */
export const cancelBankTopUp = (topUpId: string) => runProcess<unknown>(WALLET_PROCESS.bankCancel, { topUpId })

/** Today in Japan (`YYYY-MM-DD`), where the bank is: the server checks the days against it. */
export function todayInJapan(now = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Tokyo', year: 'numeric', month: '2-digit',
    day: '2-digit' }).format(now)
}

/** `TopUpReceipts.IssueOutput`: the receipt's document, issued now or before. */
export interface ReceiptOutput {
  runId: string
  documentNo: string
  issued: boolean
}

/** The languages a receipt is issued in (the application's); another interface language falls back to English. */
export function receiptLanguage(language: string | undefined): 'en' | 'zh' | 'ja' {
  const base = (language ?? '').toLowerCase().split('-')[0]
  return base === 'zh' || base === 'ja' ? base : 'en'
}

/**
 * The receipt of a paid top-up (F-09): issued the first time in the sponsor's language (D-5), the same document
 * every time after; then its PDF exactly as kept (`GET /api/documents/runs/{id}/pdf`), handed to the browser.
 */
export async function downloadReceipt(topUpId: string, language: string | undefined) {
  const receipt = await runProcess<ReceiptOutput>(WALLET_PROCESS.receiptIssue,
    { topUpId, language: receiptLanguage(language) })
  const response = await sessionFetch(`/api/documents/runs/${encodeURIComponent(receipt.runId)}/pdf`)
  if (!response.ok) throw await toApiError(response)
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = fileNameOf(response.headers.get('Content-Disposition')) ?? `${receipt.documentNo}.pdf`
    link.rel = 'noopener'
    link.click()
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), 0)
  }
  return receipt
}

/** The labels of the top-up states, in the request's language. */
export const readTopUpStatuses = (): Promise<DictionaryItem[]> => readDictionary(WALLET_DICTIONARY.topUpStatus)

/** The labels of the invoice states (Q87b), in the request's language. */
export const readInvoiceStatuses = (): Promise<DictionaryItem[]> => readDictionary(WALLET_DICTIONARY.feeInvoiceStatus)

// ---- Q87b: the platform's invoices -----------------------------------------------------------------------------------


/** The states of the platform's invoices (`fee.QbFees`): only the server moves them. */
export type InvoiceStatus = 'OPEN' | 'PARTIAL' | 'OVERDUE' | 'PAID' | 'VOID'
/** F-M1: a partly paid invoice is still owed (its total less what was paid). */
export const INVOICE_UNPAID: readonly InvoiceStatus[] = ['OPEN', 'PARTIAL', 'OVERDUE']
const invoiceStatusOf = (value: unknown): InvoiceStatus =>
  value === 'PARTIAL' || value === 'OVERDUE' || value === 'PAID' || value === 'VOID' ? value : 'OPEN'

/** A row of `qb.sponsor.fee-invoices`. */
export interface InvoiceRow {
  invoiceId: string
  invoiceNo: string
  /** The month billed, `YYYY-MM`. */
  period: string
  billingMode: BillingMode
  amount: number
  tax: number
  total: number
  /** F-M1: what has been paid of the total. */
  paidAmount: number
  /** F-M7: what credit notes paid of the total. */
  creditApplied: number
  status: InvoiceStatus
  issuedAt: string
  dueOn: string | null
  paidAt: string | null
  documentRunId: string | null
}

export async function searchFeeInvoices({ sorts, offset, limit }: TopUpSearch) {
  const page = await runQuery<Row>(WALLET_TEMPLATE.feeInvoices, { sorts, offset, limit, count: true })
  return {
    total: page.total,
    items: page.items.map((row): InvoiceRow => ({
      invoiceId: String(row.invoiceId),
      invoiceNo: String(row.invoiceNo ?? ''),
      period: String(row.period ?? ''),
      billingMode: row.billingMode === 'POSTPAID' ? 'POSTPAID' : 'PREPAID',
      amount: num(row.amount),
      tax: num(row.tax),
      total: num(row.total),
      paidAmount: num(row.paidAmount),
      creditApplied: num(row.creditApplied),
      status: invoiceStatusOf(row.status),
      issuedAt: String(row.issuedAt ?? ''),
      dueOn: str(row.dueOn),
      paidAt: str(row.paidAt),
      documentRunId: str(row.documentRunId),
    })),
  }
}

/** What is still owed of an invoice: its total less what credit notes and payments covered (F-M7). */
export const invoiceOwed = (invoice: { total: number; paidAmount: number; creditApplied: number }): number =>
  Math.max(0, invoice.total - invoice.creditApplied - invoice.paidAmount)

/** A row of `qb.sponsor.fee-charges`: a fee the platform charged, and the invoice it is on. */
export interface ChargeRow {
  chargeId: string
  kind: 'MONTHLY' | 'MANUAL'
  period: string
  billingMode: BillingMode
  amount: number
  tax: number
  total: number
  reason: string | null
  chargedAt: string
  invoiceId: string | null
  invoiceNo: string | null
  invoiceStatus: InvoiceStatus | null
}

export async function searchFeeCharges({ sorts, offset, limit }: TopUpSearch) {
  const page = await runQuery<Row>(WALLET_TEMPLATE.feeCharges, { sorts, offset, limit, count: true })
  return {
    total: page.total,
    items: page.items.map((row): ChargeRow => ({
      chargeId: String(row.chargeId),
      kind: row.kind === 'MANUAL' ? 'MANUAL' : 'MONTHLY',
      period: String(row.period ?? ''),
      billingMode: row.billingMode === 'POSTPAID' ? 'POSTPAID' : 'PREPAID',
      amount: num(row.amount),
      tax: num(row.tax),
      total: num(row.total),
      reason: str(row.reason),
      chargedAt: String(row.chargedAt ?? ''),
      invoiceId: str(row.invoiceId),
      invoiceNo: str(row.invoiceNo),
      invoiceStatus: row.invoiceStatus == null ? null : invoiceStatusOf(row.invoiceStatus),
    })),
  }
}

/** What `QB_FEE_INVOICE_PAY` answers: the invoice's state after, and the cash left. */
export interface InvoicePayOutput {
  invoiceId: string
  invoiceNo: string
  status: InvoiceStatus
  total: number
  cashBalance: number
  /** F-M1: what has been paid of the total after, and of it now (from the granted credit and the cash). */
  paidAmount: number
  paidNow: number
}

/** Pays an invoice from the account, as far as it goes; the server checks the state and allocates. */
export async function payInvoice(invoiceId: string): Promise<InvoicePayOutput> {
  const out = await runProcess<Record<string, unknown>>(WALLET_PROCESS.invoicePay, { invoiceId })
  return { invoiceId: String(out.invoiceId), invoiceNo: String(out.invoiceNo ?? ''), status: invoiceStatusOf(out.status),
    total: num(out.total), cashBalance: num(out.cashBalance), paidAmount: num(out.paidAmount), paidNow: num(out.paidNow) }
}

/** What `QB_FEE_PAY_EARLY` answers: how many fees were settled, their total, the cash left, their invoices. */
export interface EarlyPayOutput {
  count: number
  total: number
  cashBalance: number
  invoiceNos: string[]
  /** How many fees still wait: the server settles a hundred at a time, oldest first. */
  remaining: number
}

/** Pays the postpaid fees not invoiced yet from the cash account; the server checks there are some and the cash. */
export async function payEarly(): Promise<EarlyPayOutput> {
  const out = await runProcess<Record<string, unknown>>(WALLET_PROCESS.payEarly, {})
  return { count: num(out.count), total: num(out.total), cashBalance: num(out.cashBalance),
    invoiceNos: Array.isArray(out.invoiceNos) ? out.invoiceNos.map(String) : [], remaining: num(out.remaining) }
}

/** Hands the PDF kept for a document run to the browser, under the name the server gave it. */
async function saveDocumentPdf(documentRunId: string, fallbackName: string) {
  const response = await sessionFetch(`/api/documents/runs/${encodeURIComponent(documentRunId)}/pdf`)
  if (!response.ok) throw await toApiError(response)
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = fileNameOf(response.headers.get('Content-Disposition')) ?? `${fallbackName}.pdf`
    link.rel = 'noopener'
    link.click()
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), 0)
  }
}

/**
 * The invoice's document (Q87b): issued by the server the first time, in the sponsor's language, the same one every
 * time after; then its PDF exactly as kept, handed to the browser (as the receipts).
 */
export async function downloadInvoiceDocument(invoiceId: string, language: string | undefined) {
  const issued = await runProcess<{ documentRunId: string; documentNo: string | null }>(WALLET_PROCESS.invoiceDocument,
    { invoiceId, language: receiptLanguage(language) })
  await saveDocumentPdf(issued.documentRunId, issued.documentNo ?? invoiceId)
  return issued
}

// ---- F-M7: credit notes ----------------------------------------------------------------------------------------------

/** `QbFeeCreditNote.reason`: the audience fee of the unused days given back, or a credit note issued by hand. */
export type CreditNoteReason = 'AUDIENCE_UNUSED' | 'MANUAL'
export const CREDIT_NOTE_REASONS: readonly CreditNoteReason[] = ['AUDIENCE_UNUSED', 'MANUAL']
export const creditNoteReasonOf = (value: unknown): CreditNoteReason => value === 'MANUAL' ? 'MANUAL' : 'AUDIENCE_UNUSED'

/** A row of `qb.sponsor.fee-credit-notes`. */
export interface CreditNoteRow {
  creditNoteId: string
  creditNoteNo: string
  invoiceNo: string | null
  publicationId: string | null
  reason: CreditNoteReason
  endReason: string | null
  usedDays: number | null
  amount: number
  tax: number
  total: number
  /** What was given back as cash, and as credit; the credit used so far and left. */
  refundedCash: number
  refundedCredit: number
  used: number
  creditLeft: number
  issuedAt: string
  documentNo: string | null
}

export const creditNotesQuery = (publicationId?: string) => [...WALLET_QUERY, 'credit-notes', publicationId ?? null] as const

export interface CreditNoteSearch extends TopUpSearch {
  /** Only the credit notes of this publication. */
  publicationId?: string
}

export async function searchCreditNotes({ sorts, offset, limit, publicationId }: CreditNoteSearch) {
  const filters: Filter[] = publicationId ? [{ field: 'publicationId', op: 'eq', value: publicationId }] : []
  const page = await runQuery<Row>(WALLET_TEMPLATE.feeCreditNotes, { sorts, offset, limit, count: true, filters })
  return {
    total: page.total,
    items: page.items.map((row): CreditNoteRow => ({
      creditNoteId: String(row.creditNoteId),
      creditNoteNo: String(row.creditNoteNo ?? ''),
      invoiceNo: str(row.invoiceNo),
      publicationId: str(row.publicationId),
      reason: creditNoteReasonOf(row.reason),
      endReason: str(row.endReason),
      usedDays: row.usedDays == null ? null : num(row.usedDays),
      amount: num(row.amount),
      tax: num(row.tax),
      total: num(row.total),
      refundedCash: num(row.refundedCash),
      refundedCredit: num(row.refundedCredit),
      used: num(row.used),
      creditLeft: num(row.creditLeft),
      issuedAt: String(row.issuedAt ?? ''),
      documentNo: str(row.documentNo),
    })),
  }
}

/** The credit note's document: issued once, kept, then its PDF as kept (the same flow as the invoice's). */
export async function downloadCreditNoteDocument(creditNoteId: string, language: string | undefined) {
  const issued = await runProcess<{ creditNoteId: string; creditNoteNo: string; documentRunId: string;
    documentNo: string | null }>(WALLET_PROCESS.creditNoteDocument, { creditNoteId, language: receiptLanguage(language) })
  await saveDocumentPdf(issued.documentRunId, issued.documentNo ?? issued.creditNoteNo ?? creditNoteId)
  return issued
}

/** A whole, positive number of Kudos as typed (digits only, separators allowed); null otherwise. */
export function parseKudos(text: string): number | null {
  const plain = text.trim().replace(/[,\s_]/g, '')
  if (!/^\d{1,13}$/.test(plain)) return null
  const value = Number(plain)
  return value > 0 ? value : null
}

/** How Stripe sent the sponsor back (`CheckoutRequest`: `?checkout=paid|cancelled`); null when not from Stripe. */
export function checkoutReturn(search: string): 'paid' | 'cancelled' | null {
  const value = new URLSearchParams(search).get('checkout')
  return value === 'paid' || value === 'cancelled' ? value : null
}

/** The wallet's pages and the keys of what they read. */
export { WALLET_PATH }
export const topUpPath = (topUpId: string) => `${WALLET_PATH}/topups/${encodeURIComponent(topUpId)}`
/** A bank top-up's page: the account to transfer to, the code, the report (Q13a). */
export const bankTopUpPath = (topUpId: string) => `${WALLET_PATH}/bank/${encodeURIComponent(topUpId)}`
/** The page of a top-up of either method. */
export const pathOfTopUp = (row: { topUpId: string; method: TopUpMethod }) =>
  row.method === 'BANK' ? bankTopUpPath(row.topUpId) : topUpPath(row.topUpId)

/** The top-up of a page's path (`/money/wallet/topups/<id>`, F-F3), or null. */
export function topUpIdOf(path: string): string | null {
  return idOf(/^\/money\/wallet\/topups\/([^/]+)\/?$/, path)
}

/** The bank top-up of a page's path (`/money/wallet/bank/<id>`, F-F3), or null. */
export function bankTopUpIdOf(path: string): string | null {
  return idOf(/^\/money\/wallet\/bank\/([^/]+)\/?$/, path)
}

function idOf(pattern: RegExp, path: string): string | null {
  const match = pattern.exec(path)
  if (!match) return null
  try {
    return decodeURIComponent(match[1])
  } catch {
    return null
  }
}

export const WALLET_QUERY = ['qb', 'sponsor-wallet'] as const

/**
 * Marks a top-up just started in this tab, for its page to go on to the checkout once by itself. Opened any other way
 * (the list, the browser's back button from Stripe) the page offers a button instead of leaving again.
 */
const JUST_STARTED = 'qb.topup.just-started'

export function rememberJustStarted(topUpId: string) {
  try {
    window.sessionStorage.setItem(JUST_STARTED, topUpId)
  } catch {
    // Without storage the page offers the button.
  }
}

/** Whether the top-up was just started in this tab (reading changes nothing: safe to render twice). */
export function wasJustStarted(topUpId: string): boolean {
  try {
    return window.sessionStorage.getItem(JUST_STARTED) === topUpId
  } catch {
    return false
  }
}

/** Forgets the mark once the page went on (or will not), so it happens once. */
export function forgetJustStarted() {
  try {
    window.sessionStorage.removeItem(JUST_STARTED)
  } catch {
    // Nothing kept.
  }
}

// ---- F-M1: the account that pays the platform's fees -----------------------------------------------------------------

/** A lot with money left (`qb.sponsor.fee-account-lots`), in the order the account is spent. */
export interface FeeAccountLot {
  seq: number
  subAccount: 'FEE_CREDIT' | 'CASH'
  lotId: string
  source: string
  amount: number
  remaining: number
  arrivedAt: string | null
  expiresAt: string | null
}

/** A part of a payment (`qb.sponsor.fee-payments`); negative: given back when an invoice was voided. */
export interface FeePaymentPart {
  paymentId: string
  paidAt: string | null
  invoiceNo: string | null
  subAccount: 'FEE_CREDIT' | 'CASH'
  source: string | null
  amount: number
}

const subAccountOf = (value: unknown): 'FEE_CREDIT' | 'CASH' => (value === 'FEE_CREDIT' ? 'FEE_CREDIT' : 'CASH')

export async function readFeeAccountLots(offset: number, limit: number) {
  const page = await runQuery<Row>(WALLET_TEMPLATE.feeAccountLots, { offset, limit, count: true })
  return { total: page.total, items: page.items.map((row): FeeAccountLot => ({
    seq: num(row.seq), subAccount: subAccountOf(row.subAccount), lotId: String(row.lotId ?? ''),
    source: String(row.source ?? ''), amount: num(row.amount), remaining: num(row.remaining),
    arrivedAt: str(row.arrivedAt), expiresAt: str(row.expiresAt) })) }
}

export async function readFeePayments(offset: number, limit: number) {
  const page = await runQuery<Row>(WALLET_TEMPLATE.feePayments, { offset, limit, count: true })
  return { total: page.total, items: page.items.map((row): FeePaymentPart => ({
    paymentId: String(row.paymentId), paidAt: str(row.paidAt), invoiceNo: str(row.invoiceNo),
    subAccount: subAccountOf(row.subAccount), source: str(row.source), amount: num(row.amount) })) }
}

export const readFeeSubAccounts = (): Promise<DictionaryItem[]> => readDictionary(WALLET_DICTIONARY.feeSubAccount)
export const readFeeCreditSources = (): Promise<DictionaryItem[]> => readDictionary(WALLET_DICTIONARY.feeCreditSource)
export const readCashSources = (): Promise<DictionaryItem[]> => readDictionary(WALLET_DICTIONARY.cashSource)
