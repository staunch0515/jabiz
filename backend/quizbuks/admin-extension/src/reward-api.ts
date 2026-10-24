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

/** The methods a sponsor may publish with: their setting's, or the platform's Kudos alone without one. */
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
