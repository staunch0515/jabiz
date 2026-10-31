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

// ---- Q13b: withdrawals to a bank account ------------------------------------------------------------------------

export type PayoutMethod = 'STRIPE' | 'BANK'
export const ACCOUNT_TYPES = ['ORDINARY', 'CURRENT', 'SAVINGS'] as const
export type AccountType = (typeof ACCOUNT_TYPES)[number]

/** The taker's bank account as the server keeps it (the number masked), or none. */
export interface BankAccount {
  bankCode: string
  bankName: string
  branchCode: string
  branchName: string
  accountType: AccountType
  accountNumber: string
  accountHolder: string
  changedAt: string
}

/** The row of `qb.app.bank-account`: the settings, how the taker is paid and their account. */
export interface BankSettings {
  enabled: boolean
  fee: number
  /** The smallest withdrawal (the transfer threshold); null when not set. */
  minimum: number | null
  cooldownHours: number
  method: PayoutMethod
  account: BankAccount | null
}

export async function readBankSettings(): Promise<BankSettings> {
  const row = (await runQuery<Row>(WALLET_TEMPLATE.bankAccount, { limit: 1 })).items[0] ?? {}
  const account: BankAccount | null = row.bankCode == null ? null : {
    bankCode: String(row.bankCode),
    bankName: String(row.bankName ?? ''),
    branchCode: String(row.branchCode ?? ''),
    branchName: String(row.branchName ?? ''),
    accountType: String(row.accountType) as AccountType,
    accountNumber: String(row.accountNumber ?? ''),
    accountHolder: String(row.accountHolder ?? ''),
    changedAt: String(row.changedAt ?? ''),
  }
  return {
    enabled: row.enabled === true || row.enabled === 'true',
    fee: num(row.fee),
    minimum: row.minimum == null ? null : num(row.minimum),
    cooldownHours: num(row.cooldownHours),
    method: row.payoutMethod === 'BANK' ? 'BANK' : 'STRIPE',
    account,
  }
}

/** When a withdrawal to the account may be asked for: its last change plus the cooling-off period. */
export function usableFrom(settings: BankSettings): Date | null {
  if (!settings.account?.changedAt) return null
  const changed = new Date(settings.account.changedAt)
  return Number.isNaN(changed.getTime()) ? null : new Date(changed.getTime() + settings.cooldownHours * 3_600_000)
}

/** What the taker types; the server checks and normalises it (width, padding) again. */
export interface BankAccountForm {
  bankCode: string
  bankName: string
  branchCode: string
  branchName: string
  accountType: AccountType
  bankAccountNumber: string
  accountHolder: string
}

/** `BankWithdrawals.AccountOutput`: not saved when the account is another user's (`reason`). */
export interface BankAccountSaved {
  bankAccountId: string | null
  saved: boolean
  reason: string | null
  usableFrom: string | null
}

export const saveBankAccount = (form: BankAccountForm) =>
  runProcess<BankAccountSaved>(WALLET_PROCESS.bankAccountSave, { ...form })

export const setPayoutMethod = (method: PayoutMethod) =>
  runProcess<{ method: PayoutMethod }>(WALLET_PROCESS.payoutMethodSet, { method })

/** `TransferProcesses.TransferOutput` of a withdrawal: `REVIEW` with its request. */
export interface WithdrawalOutput {
  transferId: string | null
  status: string | null
  reason: string | null
  approvalRequestId: string | null
}

/** Asks for a withdrawal; the same key when the taker retries the same submission. */
export const requestWithdrawal = (amount: number, idempotencyKey: string) =>
  runProcess<WithdrawalOutput>(WALLET_PROCESS.withdrawalRequest, { amount }, { idempotencyKey })

/** The smallest amount the server accepts: the threshold, and above the fee (`BankAccounts.amountProblem`). */
export const smallestWithdrawal = (settings: BankSettings): number =>
  Math.max(settings.minimum ?? 0, settings.fee + 1)

/** What the taker receives of `amount`: less the fixed fee. */
export const netOf = (amount: number, fee: number): number => Math.max(0, amount - fee)

/** The wallet's pages and the keys of what they read. */
export const WALLET_PATH = '/wallet'
export const PAYOUT_PATH = '/wallet/payout'
export const BANK_PATH = '/wallet/bank'

/** The parts of My Kudos (Q62), each at `/wallet/<part>` under its menu, as the account's are (Q56). */
export const WALLET_PARTS = ['balance', 'rewards', 'history'] as const
export type WalletPart = (typeof WALLET_PARTS)[number]

export function walletPath(part: WalletPart): string {
  return `${WALLET_PATH}/${part}`
}

/** The part a path names; null for the menu itself, undefined for any other path. */
export function walletPartOf(path: string): WalletPart | null | undefined {
  if (path === WALLET_PATH) return null
  const part = path.startsWith(`${WALLET_PATH}/`) ? path.slice(WALLET_PATH.length + 1) : ''
  return (WALLET_PARTS as readonly string[]).includes(part) ? (part as WalletPart) : undefined
}

/** Whether a path is one of My Kudos' pages (the bottom navigation marks My Kudos for all). */
export function isWalletPath(path: string): boolean {
  return path === WALLET_PATH || path.startsWith(`${WALLET_PATH}/`)
}
export const WALLET_QUERY = ['qb', 'taker-wallet'] as const
export const PAYOUT_QUERY = [...WALLET_QUERY, 'payout-account'] as const
export const BANK_QUERY = [...WALLET_QUERY, 'bank-account'] as const
