import { api, runProcess, runQuery, toApiError, type Sort } from '@jabiz/client'
import { readDictionary, type DictionaryItem } from '../qb/api'
import { WALLET_DATASET, WALLET_DICTIONARY, WALLET_PROCESS, WALLET_TEMPLATE } from './ids'

/*
 * The sponsor wallet's calls, typed by hand after the Java records and templates of backend/quizbuks `wallet`
 * (processes have no OpenAPI types of their own). The end-to-end test `topup.spec.ts` runs every one of them against
 * the server. Amounts stay as the server sends them (whole Kudos) and are shown only through `formatKudos`.
 */

/** `TopUpStatus`: only the server's notices from Stripe move a top-up on; the page only reads it. */
export type TopUpStatus = 'OPENING' | 'OPEN' | 'PAID' | 'EXPIRED' | 'FAILED'
export const FINAL_STATUSES: readonly TopUpStatus[] = ['PAID', 'EXPIRED', 'FAILED']

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

/** The top-up of a page's path (`/wallet/topups/<id>`), or null. */
export function topUpIdOf(path: string): string | null {
  const match = /^\/wallet\/topups\/([^/]+)\/?$/.exec(path)
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
