import { api, runProcess, runQuery, unwrap } from '@jabiz/admin'
import { DATASET, PROCESS, TEMPLATE } from './ids'

/*
 * The reward methods and cash rewards (docs/quizbuks/plans/Q22-reward-methods.md): the setting of a sponsor's
 * methods, read from its dataset and written by `QB_SPONSOR_REWARD_METHODS_SET`; the cash rewards of every sponsor from
 * `qb.admin.cash-rewards`, a disputed one decided by `QB_CASH_REWARD_RESOLVE`.
 */

export type RewardMethod = 'PLATFORM' | 'SPONSOR_CASH' | 'OFFLINE_REDEEM'
export const REWARD_METHODS: readonly RewardMethod[] = ['PLATFORM', 'SPONSOR_CASH', 'OFFLINE_REDEEM']

export type CashStatus = 'DUE' | 'MARKED_PAID' | 'CONFIRMED' | 'DISPUTED' | 'OVERDUE' | 'VOID'
export const CASH_STATUSES: readonly CashStatus[] = ['DUE', 'MARKED_PAID', 'CONFIRMED', 'DISPUTED', 'OVERDUE', 'VOID']

export type CashResolution = 'PAID' | 'UNPAID' | 'VOID'
export const CASH_RESOLUTIONS: readonly CashResolution[] = ['PAID', 'UNPAID', 'VOID']

type Row = Record<string, unknown>
const str = (value: unknown): string | null => (value == null ? null : String(value))

/** The reward parameters in force (Q28, `qb.admin.reward-defaults`): what a sponsor without a setting may use. */
export interface RewardDefaults {
  methods: RewardMethod[]
  /** Whether `qb.reward.default-methods` has been published yet (otherwise the platform's Kudos alone apply). */
  published: boolean
  /** The highest reward one attempt may win, in Kudos; null for no cap. */
  maxRewardPerAttempt: number | null
}

export async function readRewardDefaults(): Promise<RewardDefaults> {
  const page = await runQuery<Row>(TEMPLATE.rewardDefaults, { limit: 2 })
  const rows = new Map(page.items.map((row) => [String(row.paramKey), row]))
  const methods = rows.get('qb.reward.default-methods')
  const cap = rows.get('qb.reward.max-per-attempt')
  const named = methods?.paramExists ? String(methods.value ?? '').split(',').map((m) => m.trim()) : ['PLATFORM']
  const max = cap?.paramExists && cap.value != null && cap.value !== '' ? Number(cap.value) : null
  return {
    methods: REWARD_METHODS.filter((m) => named.includes(m)),
    published: methods?.paramExists === true,
    maxRewardPerAttempt: max != null && Number.isFinite(max) && max > 0 ? max : null,
  }
}

/** The methods a sponsor may publish with: their setting's; null without one (the defaults apply, Q28). */
export async function readSponsorMethods(userId: string): Promise<{ methods: RewardMethod[]; note: string | null;
  setBy: string | null; setAt: string | null } | null> {
  const page = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: DATASET.sponsorMethods } },
    body: { filters: [{ field: 'userId', op: 'eq', value: userId }], limit: 1 },
  }))
  const row = (page.items ?? [])[0]?.attributes as Row | undefined
  if (!row) return null
  const methods = String(row.methods ?? '').split(',').filter((m): m is RewardMethod =>
    (REWARD_METHODS as readonly string[]).includes(m))
  return { methods: REWARD_METHODS.filter((m) => methods.includes(m)), note: str(row.note), setBy: str(row.setBy),
    setAt: str(row.setAt) }
}

export const setSponsorMethods = (userId: string, methods: RewardMethod[], note: string) =>
  runProcess<{ userId: string; methods: RewardMethod[]; revision: number }>(PROCESS.rewardMethodsSet,
    { userId, methods, note: note.trim() || null })

/** A row of `qb.admin.cash-rewards`. */
export interface CashReward {
  cashRewardId: string
  code: string
  attemptId: string
  publicationId: string
  publicationName: string
  sponsorId: string
  sponsorCompany: string | null
  userId: string
  takerName: string | null
  amount: string
  status: CashStatus
  dueAt: string
  payBy: string | null
  remindedAt: string | null
  markedPaidAt: string | null
  markedPaidNote: string | null
  confirmBy: string | null
  confirmedAt: string | null
  disputedAt: string | null
  disputeNote: string | null
  overdueAt: string | null
  resolvedAt: string | null
  resolution: string | null
  resolveNote: string | null
}

function cashReward(row: Row): CashReward {
  return {
    cashRewardId: String(row.cashRewardId),
    code: String(row.code ?? ''),
    attemptId: String(row.attemptId ?? ''),
    publicationId: String(row.publicationId ?? ''),
    publicationName: String(row.publicationName ?? ''),
    sponsorId: String(row.sponsorId ?? ''),
    sponsorCompany: str(row.sponsorCompany),
    userId: String(row.userId ?? ''),
    takerName: str(row.takerName),
    amount: String(row.amount ?? '0'),
    status: (CASH_STATUSES as readonly string[]).includes(String(row.status)) ? (row.status as CashStatus) : 'DUE',
    dueAt: String(row.dueAt ?? ''),
    payBy: str(row.payBy),
    remindedAt: str(row.remindedAt),
    markedPaidAt: str(row.markedPaidAt),
    markedPaidNote: str(row.markedPaidNote),
    confirmBy: str(row.confirmBy),
    confirmedAt: str(row.confirmedAt),
    disputedAt: str(row.disputedAt),
    disputeNote: str(row.disputeNote),
    overdueAt: str(row.overdueAt),
    resolvedAt: str(row.resolvedAt),
    resolution: str(row.resolution),
    resolveNote: str(row.resolveNote),
  }
}

export async function readCashRewards(status: string | null, q: string, offset: number, limit: number):
  Promise<{ items: CashReward[]; total?: number }> {
  const page = await runQuery<Row>(TEMPLATE.cashRewards, {
    params: q.trim() ? { q: q.trim() } : {},
    filters: status ? [{ field: 'status', op: 'eq', value: status }] : [],
    offset,
    limit,
    count: true,
  })
  return { items: page.items.map(cashReward), total: page.total }
}

export const resolveCashReward = (cashRewardId: string, resolution: CashResolution, note: string) =>
  runProcess<{ cashRewardId: string; status: CashStatus; revision: number }>(PROCESS.cashResolve,
    { cashRewardId, resolution, note: note.trim() || null })

// ---- prizes collected at the sponsor's place (Q22b) ------------------------------------------------------------------

export type RedemptionStatus = 'DUE' | 'CLAIMED' | 'REDEEMED' | 'EXPIRED' | 'VOID'
export const REDEMPTION_STATUSES: readonly RedemptionStatus[] = ['DUE', 'CLAIMED', 'REDEEMED', 'EXPIRED', 'VOID']

/** A row of `qb.admin.redemptions`. */
export interface Redemption {
  redemptionId: string
  code: string
  attemptId: string
  publicationId: string
  publicationName: string
  sponsorId: string
  sponsorCompany: string | null
  userId: string
  takerName: string | null
  amount: string
  description: string
  status: RedemptionStatus
  dueAt: string
  expiresAt: string | null
  claimKind: string | null
  claimedAt: string | null
  confirmBy: string | null
  redeemedAt: string | null
  redeemedBy: string | null
  voidedBy: string | null
  voidNote: string | null
}

function redemption(row: Row): Redemption {
  return {
    redemptionId: String(row.redemptionId),
    code: String(row.code ?? ''),
    attemptId: String(row.attemptId ?? ''),
    publicationId: String(row.publicationId ?? ''),
    publicationName: String(row.publicationName ?? ''),
    sponsorId: String(row.sponsorId ?? ''),
    sponsorCompany: str(row.sponsorCompany),
    userId: String(row.userId ?? ''),
    takerName: str(row.takerName),
    amount: String(row.amount ?? '0'),
    description: String(row.description ?? ''),
    status: (REDEMPTION_STATUSES as readonly string[]).includes(String(row.status))
      ? (row.status as RedemptionStatus) : 'DUE',
    dueAt: String(row.dueAt ?? ''),
    expiresAt: str(row.expiresAt),
    claimKind: str(row.claimKind),
    claimedAt: str(row.claimedAt),
    confirmBy: str(row.confirmBy),
    redeemedAt: str(row.redeemedAt),
    redeemedBy: str(row.redeemedBy),
    voidedBy: str(row.voidedBy),
    voidNote: str(row.voidNote),
  }
}

export async function readRedemptions(status: string | null, q: string, offset: number, limit: number):
  Promise<{ items: Redemption[]; total?: number }> {
  const page = await runQuery<Row>(TEMPLATE.redemptions, {
    params: q.trim() ? { q: q.trim() } : {},
    filters: status ? [{ field: 'status', op: 'eq', value: status }] : [],
    offset,
    limit,
    count: true,
  })
  return { items: page.items.map(redemption), total: page.total }
}

export const voidRedemption = (redemptionId: string, note: string) =>
  runProcess<{ redemptionId: string; status: RedemptionStatus; revision: number }>(PROCESS.redemptionVoid,
    { redemptionId, note: note.trim() || null })
