import { runProcess, runQuery, type Filter } from '@jabiz/admin'
import { isUserId, PROCESS, TEMPLATE } from './ids'

/*
 * Q14: the administrators' view of answering and payouts, typed by hand after `qb.admin.attempts`,
 * `qb.admin.attempt-rewards`, `qb.admin.taker-kudos` and `TransferProcesses.PreviewOutput` / `TransferOutput`.
 * Amounts stay as the server sends them.
 */

type Row = Record<string, unknown>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const num = (value: unknown): number | null => (value == null || value === '' ? null : Number(value))
const amount = (value: unknown): string => (value == null || value === '' ? '0' : String(value))

/** A row of `qb.admin.taker-kudos`. */
export interface TakerKudos {
  userId: string
  attempts: number
  completed: number
  earned: string
  pendingRewards: string
  balance: string
  inTransfer: string
  transferred: string
}

/** The Kudos of the given takers (one page of users); a taker who never answered has no row. */
export async function readTakerKudos(userIds: readonly string[]): Promise<Map<string, TakerKudos>> {
  if (userIds.length === 0) return new Map()
  const answer = await runQuery<Row>(TEMPLATE.takerKudos, {
    filters: [{ field: 'userId', op: 'in', values: [...userIds] }], limit: userIds.length })
  return new Map(answer.items.map((row) => [String(row.userId), {
    userId: String(row.userId),
    attempts: num(row.attempts) ?? 0,
    completed: num(row.completed) ?? 0,
    earned: amount(row.earned),
    pendingRewards: amount(row.pendingRewards),
    balance: amount(row.balance),
    inTransfer: amount(row.inTransfer),
    transferred: amount(row.transferred),
  }]))
}

/** `TransferProcesses.PreviewOutput`: what the daily run would do for the taker now. */
export interface TransferPreview {
  transfers: boolean
  /** Why not (`TransferRules`): DISABLED, NO_ACCOUNT, BELOW_THRESHOLD … */
  skip: string | null
  amount: string | null
  fee: string | null
  net: string | null
  review: boolean
  balance: string
  held: string
}

export const previewTransfer = (userId: string) => runProcess<TransferPreview>(PROCESS.transferPreview, { userId })

/** `TransferProcesses.TransferOutput`: the transfer made (sending, or waiting for review), or why none was. */
export interface TransferNowOutput {
  transferId: string | null
  status: string | null
  reason: string | null
  approvalRequestId: string | null
}

export const transferNow = (userId: string) => runProcess<TransferNowOutput>(PROCESS.transferNow, { userId })

/** The attempts' filter of the page (from its address). */
export interface AttemptFilter {
  userId: string | null
  publicationId: string | null
  status: string | null
  rewardStatus: string | null
}

/** A row of `qb.admin.attempts`, with its reward when finance reads it. */
export interface AttemptListRow {
  attemptId: string
  userId: string
  publicationId: string
  name: string | null
  status: string
  startedAt: string | null
  submittedAt: string | null
  score: number | null
  fullScore: number | null
  passed: boolean
  rewardStatus: string | null
}

export async function readAllAttempts(filter: AttemptFilter, offset: number, limit: number) {
  const filters: Filter[] = []
  if (filter.userId) filters.push({ field: 'userId', op: 'eq', value: filter.userId })
  if (filter.publicationId) filters.push({ field: 'publicationId', op: 'eq', value: filter.publicationId })
  if (filter.status) filters.push({ field: 'status', op: 'eq', value: filter.status })
  if (filter.rewardStatus) filters.push({ field: 'rewardStatus', op: 'eq', value: filter.rewardStatus })
  const answer = await runQuery<Row>(TEMPLATE.attempts, { filters, offset, limit, count: true })
  return {
    total: answer.total,
    items: answer.items.map((row): AttemptListRow => ({
      attemptId: String(row.attemptId),
      userId: String(row.userId ?? ''),
      publicationId: String(row.publicationId ?? ''),
      name: str(row.name),
      status: String(row.status ?? ''),
      startedAt: str(row.startedAt),
      submittedAt: str(row.submittedAt),
      score: num(row.score),
      fullScore: num(row.fullScore),
      passed: row.passed === true || row.passed === 'true',
      rewardStatus: str(row.rewardStatus),
    })),
  }
}

/** The taker's share of each attempt's reward (reward less commission), by attempt: finance only. */
export async function readAttemptKudos(attemptIds: readonly string[]): Promise<Map<string, string>> {
  if (attemptIds.length === 0) return new Map()
  const answer = await runQuery<Row>(TEMPLATE.attemptRewards, {
    filters: [{ field: 'attemptId', op: 'in', values: [...attemptIds] }], limit: attemptIds.length })
  return new Map(answer.items.map((row) => [String(row.attemptId),
    String(Number(amount(row.reward)) - Number(amount(row.fee)))]))
}

/** The page's filter from its address (`?user=…&publication=…&status=…&reward=…`); anything malformed is ignored. */
export function attemptFilterOf(search: URLSearchParams): AttemptFilter {
  const id = (name: string) => {
    const value = search.get(name)?.trim() ?? ''
    return isUserId(value) ? value : null
  }
  const code = (name: string) => {
    const value = search.get(name)?.trim() ?? ''
    return /^[A-Z_]{1,20}$/.test(value) ? value : null
  }
  return { userId: id('user'), publicationId: id('publication'), status: code('status'), rewardStatus: code('reward') }
}
