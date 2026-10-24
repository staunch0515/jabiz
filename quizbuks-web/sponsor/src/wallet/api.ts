import { api, fileNameOf, runProcess, runQuery, sessionFetch, toApiError, type Sort } from '@jabiz/client'
import { readDictionary, type DictionaryItem } from '../qb/api'
import { WALLET_DATASET, WALLET_DICTIONARY, WALLET_PROCESS, WALLET_TEMPLATE } from './ids'

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
/** How a top-up is paid: by card on Stripe's page, or by a transfer to the platform's bank account (Q13a). */
export type TopUpMethod = 'STRIPE' | 'BANK'
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
}

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
  }
}

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
      method: row.method === 'BANK' ? 'BANK' : 'STRIPE',
      transferCode: str(row.transferCode),
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
    method: row.method === 'BANK' ? 'BANK' : 'STRIPE',
    transferCode: str(row.transferCode),
    requestedAmount: numOrNull(row.requestedAmount),
    payerName: str(row.payerName),
    transferDate: str(row.transferDate),
    receivedOn: row.status === 'PAID' ? str(row.receivedOn) : null,
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
export const startTopUp = (amount: number, idempotencyKey: string) =>
  runProcess<StartOutput>(WALLET_PROCESS.topUpStart, { amount }, { idempotencyKey })

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
export const startBankTopUp = (amount: number, idempotencyKey: string) =>
  runProcess<BankStartOutput>(WALLET_PROCESS.bankStart, { amount }, { idempotencyKey })

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
export const WALLET_PATH = '/wallet'
export const topUpPath = (topUpId: string) => `${WALLET_PATH}/topups/${encodeURIComponent(topUpId)}`
/** A bank top-up's page: the account to transfer to, the code, the report (Q13a). */
export const bankTopUpPath = (topUpId: string) => `${WALLET_PATH}/bank/${encodeURIComponent(topUpId)}`
/** The page of a top-up of either method. */
export const pathOfTopUp = (row: { topUpId: string; method: TopUpMethod }) =>
  row.method === 'BANK' ? bankTopUpPath(row.topUpId) : topUpPath(row.topUpId)

/** The top-up of a page's path (`/wallet/topups/<id>`), or null. */
export function topUpIdOf(path: string): string | null {
  return idOf(/^\/wallet\/topups\/([^/]+)\/?$/, path)
}

/** The bank top-up of a page's path (`/wallet/bank/<id>`), or null. */
export function bankTopUpIdOf(path: string): string | null {
  return idOf(/^\/wallet\/bank\/([^/]+)\/?$/, path)
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
