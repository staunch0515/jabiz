import { runProcess, runQuery, type QueryPage } from '@jabiz/client'
import { readDictionary, type DictionaryItem } from '../qb/api'
import { WALLET_DICTIONARY, WALLET_PROCESS, WALLET_TEMPLATE } from './ids'

/*
 * The app wallet's calls, typed by hand after the Java records and templates of backend/quizbuks `wallet` (processes
 * have no OpenAPI types of their own). The end-to-end test `payout.spec.ts` runs every one of them against the server.
 * Amounts stay as the server sends them (whole Kudos) and are shown only through `formatKudos`.
 */

/** The row of `qb.app.wallet` (U-50). */
export interface TakerWallet {
  /** What the taker has (account 2120). */
  available: number
  totalEarned: number
  /** Rewards waiting for their sponsors (not in the ledger yet), before the commission. */
  pendingCount: number
  pendingAmount: number
  /** On the way to the taker's Stripe account. */
  inTransfer: number
  /**
   * Of `available`, the rewards held back from transfers (Q10b): new ones for some days, and those of a sponsor whose
   * payment is being checked (`heldOpen`, no date); null when the server says nothing about holds.
   */
  held: number | null
  heldOpen: number
  /** When the last reward held for its age only is free; null without one. */
  heldUntil: string | null
}

/** What may be transferred: what the taker has less what is held back. */
export const transferable = (wallet: TakerWallet): number => Math.max(0, wallet.available - (wallet.held ?? 0))

export type EntryType = 'REWARD' | 'TRANSFER'
export type EntryStatus = 'COMPLETED' | 'PROCESSING' | 'FAILED' | 'REVERSED'

/** A row of `qb.app.ledger` (U-51, U-52). */
export interface LedgerRow {
  entryId: string
  type: EntryType
  status: EntryStatus
  amount: number
  occurredAt: string
  publicationId: string | null
  name: string | null
  quizTitle: string | null
}

/** `PayoutAccountStatus`; only Stripe's reports (through the server) change it. */
export type PayoutStatus = 'PENDING' | 'ENABLED' | 'RESTRICTED' | 'DEAUTHORIZED'

/** The row of `qb.app.payout-account` (U-53); none before the first onboarding. */
export interface PayoutAccount {
  status: PayoutStatus
  payoutsEnabled: boolean
  requirementsDue: string | null
  /** Stripe's onboarding link while it is valid (the template leaves out an expired one). */
  onboardingUrl: string | null
  linkExpiresAt: string | null
}

/** `PayoutProcesses.OnboardOutput`. */
export interface OnboardOutput {
  jobId: string
  accountStatus: PayoutStatus | null
}

type Row = Record<string, unknown>

const num = (value: unknown): number => {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}
const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))

export async function readWallet(): Promise<TakerWallet> {
  // `at` is now: what was paid within the hold period before it is held.
  const row = (await runQuery<Row>(WALLET_TEMPLATE.wallet, { params: { at: new Date().toISOString() }, limit: 1 }))
    .items[0] ?? {}
  return {
    available: num(row.available),
    totalEarned: num(row.totalEarned),
    pendingCount: num(row.pendingCount),
    pendingAmount: num(row.pendingAmount),
    inTransfer: num(row.inTransfer),
    held: row.held == null ? null : num(row.held),
    heldOpen: num(row.heldOpen),
    heldUntil: str(row.heldUntil),
  }
}

export interface LedgerRequest {
  q?: string
  type?: EntryType
  offset: number
  limit: number
}

/** The taker's transactions, newest first (the template's default order), one page. */
export async function readLedger(request: LedgerRequest): Promise<QueryPage<LedgerRow>> {
  const page = await runQuery<Row>(WALLET_TEMPLATE.ledger, {
    params: { q: request.q?.trim() || null },
    filters: request.type ? [{ field: 'type', op: 'eq', value: request.type }] : undefined,
    offset: request.offset,
    limit: request.limit,
  })
  return {
    ...page,
    items: page.items.map((row) => ({
      entryId: String(row.entryId),
      type: row.type as EntryType,
      status: row.status as EntryStatus,
      amount: num(row.amount),
      occurredAt: String(row.occurredAt ?? ''),
      publicationId: str(row.publicationId),
      name: str(row.name),
      quizTitle: str(row.quizTitle),
    })),
  }
}

/** The taker's payout account; null before the first onboarding. */
export async function readPayoutAccount(): Promise<PayoutAccount | null> {
  const row = (await runQuery<Row>(WALLET_TEMPLATE.payoutAccount, {
    params: { now: new Date().toISOString() },
    limit: 1,
  })).items[0]
  if (!row) return null
  return {
    status: row.status as PayoutStatus,
    payoutsEnabled: row.payoutsEnabled === true || row.payoutsEnabled === 'true',
    requirementsDue: str(row.requirementsDue),
    onboardingUrl: str(row.onboardingUrl),
    linkExpiresAt: str(row.linkExpiresAt),
  }
}

/**
 * Asks for a payout account and a fresh onboarding link (the job creates them shortly after). The same key is passed
 * when the taker retries the same click.
 */
export const startOnboarding = (idempotencyKey: string) =>
  runProcess<OnboardOutput>(WALLET_PROCESS.payoutOnboard, {}, { idempotencyKey })

/** The labels of the payout account states, in the request's language. */
export const readPayoutStatuses = (): Promise<DictionaryItem[]> =>
  readDictionary(WALLET_DICTIONARY.payoutAccountStatus)

/**
 * The onboarding link written after `before` was read: Stripe's links work once, so the page waits for the one its own
 * request made rather than following an older one still in its time.
 */
export function freshLink(before: PayoutAccount | null, now: PayoutAccount | null): string | null {
  if (!now?.onboardingUrl) return null
  if (before?.onboardingUrl && before.onboardingUrl === now.onboardingUrl
    && before.linkExpiresAt === now.linkExpiresAt) return null
  return now.onboardingUrl
}

/** How Stripe sent the taker back (`PayoutSettings.returnUrl`: `?onboarding=done|refresh`); null otherwise. */
export function onboardingReturn(search: string): 'done' | 'refresh' | null {
  const value = new URLSearchParams(search).get('onboarding')
  return value === 'done' || value === 'refresh' ? value : null
}

/** The wallet's pages and the keys of what they read. */
export const WALLET_PATH = '/wallet'
export const PAYOUT_PATH = '/wallet/payout'
export const WALLET_QUERY = ['qb', 'taker-wallet'] as const
export const PAYOUT_QUERY = [...WALLET_QUERY, 'payout-account'] as const
