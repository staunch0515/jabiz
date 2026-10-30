import { runQuery } from '@jabiz/client'
import type { RewardMethod } from '../qb/play'
import { WALLET_TEMPLATE } from './ids'

/*
 * The taker's Kudos by reward method (docs/quizbuks/plans/Q58-kudos-by-method.md; template `qb.app.kudos`): for each
 * of the four ways a reward is paid, what was earned, settled (transferred out, received, collected, delivered),
 * lapsed and is still owed. The total is their sum.
 */

/** The four methods, in the order the wallet shows them. */
export const METHODS: readonly RewardMethod[] = ['PLATFORM', 'SPONSOR_CASH', 'OFFLINE_REDEEM', 'DIGITAL_PRIZE']

/** A row of `qb.app.kudos`. */
export interface KudosRow {
  method: RewardMethod
  earned: number
  settled: number
  lapsed: number
  /** Still owed now, whatever the period. */
  balance: number
  rewards: number
  /** PLATFORM only: rewards waiting for their sponsor, before the commission. */
  pendingCount: number
  pendingAmount: number
}

export type KudosTotals = Omit<KudosRow, 'method' | 'pendingCount' | 'pendingAmount'>

/** The periods offered; more (a chosen year or month) take the same `from`/`to`. */
export type Period = 'all' | 'year' | 'month'
export const PERIODS: readonly Period[] = ['all', 'year', 'month']

/** A period's bounds in the device's time zone (this year, this month), as the template takes them. */
export function periodRange(period: Period, now: Date = new Date()): { from: string | null; to: string | null } {
  if (period === 'all') return { from: null, to: null }
  const from = period === 'year' ? new Date(now.getFullYear(), 0, 1) : new Date(now.getFullYear(), now.getMonth(), 1)
  const to = period === 'year' ? new Date(now.getFullYear() + 1, 0, 1)
    : new Date(now.getFullYear(), now.getMonth() + 1, 1)
  return { from: from.toISOString(), to: to.toISOString() }
}

const number = (value: unknown) => {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}

/** The four rows (a method the server did not name is all nought), in {@link METHODS}' order. */
export async function readKudos(period: Period, now: Date = new Date()): Promise<KudosRow[]> {
  const page = await runQuery<Record<string, unknown>>(WALLET_TEMPLATE.kudos, { params: periodRange(period, now),
    limit: 10 })
  const byMethod = new Map(page.items.map((row) => [String(row.method), row]))
  return METHODS.map((method) => {
    const row = byMethod.get(method) ?? {}
    return { method, earned: number(row.earned), settled: number(row.settled), lapsed: number(row.lapsed),
      balance: number(row.balance), rewards: number(row.rewards), pendingCount: number(row.pendingCount),
      pendingAmount: number(row.pendingAmount) }
  })
}

/** The sum of the four: the total Kudos. */
export function totalOf(rows: KudosRow[]): KudosTotals {
  return rows.reduce((total, row) => ({ earned: total.earned + row.earned, settled: total.settled + row.settled,
    lapsed: total.lapsed + row.lapsed, balance: total.balance + row.balance, rewards: total.rewards + row.rewards }),
  { earned: 0, settled: 0, lapsed: 0, balance: 0, rewards: 0 })
}

/** A method's page in the wallet. */
export const kudosPath = (method: RewardMethod) => `/wallet/kudos/${method}`

/** The method a wallet path names, or null. */
export function kudosMethodOf(path: string): RewardMethod | null {
  const match = /^\/wallet\/kudos\/([A-Z_]+)$/.exec(path)
  return match && (METHODS as readonly string[]).includes(match[1]) ? (match[1] as RewardMethod) : null
}
