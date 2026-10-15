import { fileNameOf, runQuery, sessionFetch, toApiError, type Sort } from '@jabiz/client'
import { PUBLISHING_TEMPLATE } from '../publishing/ids'
import { TRANSACTIONS_TEMPLATE } from './ids'

/*
 * The transactions page's calls, typed by hand after the templates `qb.sponsor.transactions` and
 * `qb.sponsor.transaction-summary` (backend/quizbuks, `queries/qb/sponsor`). The list, the totals and the export are
 * asked with the same parameters, so the three always agree. Amounts stay as the server sends them (exact decimals)
 * and are shown only through `formatKudos`.
 */

export type TransactionType = 'REWARD' | 'TOPUP'
export type TransactionStatus = 'COMPLETED' | 'PROCESSING' | 'FAILED'
export const TRANSACTION_TYPES: readonly TransactionType[] = ['REWARD', 'TOPUP']
export const TRANSACTION_STATUSES: readonly TransactionStatus[] = ['COMPLETED', 'PROCESSING', 'FAILED']

/** A row of `qb.sponsor.transactions`: a reward of one of the sponsor's publications, or one of their top-ups. */
export interface TransactionRow {
  entryId: string
  type: TransactionType
  status: TransactionStatus
  amount: string
  /** A top-up's refunded or taken-back part; null for a reward. */
  refunded: string | null
  occurredAt: string
  publicationId: string | null
  publicationName: string | null
  /** The taker's key with this sponsor (not their user id), only to filter by them; never shown. */
  userKey: string | null
  /** The name of the taker's profile. */
  userName: string | null
  score: number | null
  fullScore: number | null
}

/** The row of `qb.sponsor.transaction-summary`. */
export interface TransactionSummary {
  totalCount: number
  completedCount: number
  completedAmount: string
  processingCount: number
  processingAmount: string
  failedCount: number
  failedAmount: string
}

/** What the page filters by; the period is whole days (`YYYY-MM-DD`) in the browser's time zone, both included. */
export interface TransactionFilters {
  type: TransactionType | null
  status: TransactionStatus | null
  publicationId: string | null
  userKey: string | null
  from: string | null
  to: string | null
  q: string
}

export const NO_FILTERS: TransactionFilters = {
  type: null, status: null, publicationId: null, userKey: null, from: null, to: null, q: '',
}

type Row = Record<string, unknown>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const num = (value: unknown): number => {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}
const numOrNull = (value: unknown): number | null => (value == null || value === '' ? null : num(value))

/** The start of a day (`YYYY-MM-DD`) in the browser's time zone, `days` later, as an instant; null when no day. */
export function startOfDay(day: string | null, days = 0): string | null {
  const match = day ? /^(\d{4})-(\d{2})-(\d{2})$/.exec(day) : null
  if (!match) return null
  return new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]) + days).toISOString()
}

/**
 * The templates' parameters for the filters: only those set. The period's last day is included (`to` is the start of
 * the day after it); the text is trimmed.
 */
export function paramsOf(filters: TransactionFilters): Record<string, string> {
  const params: Record<string, string> = {}
  if (filters.type) params.type = filters.type
  if (filters.status) params.status = filters.status
  if (filters.publicationId) params.publicationId = filters.publicationId
  if (filters.userKey) params.userKey = filters.userKey
  const from = startOfDay(filters.from)
  const to = startOfDay(filters.to, 1)
  if (from) params.from = from
  if (to) params.to = to
  const q = filters.q.trim()
  if (q) params.q = q
  return params
}

export function toTransaction(row: Row): TransactionRow {
  return {
    entryId: String(row.entryId),
    type: row.type as TransactionType,
    status: row.status as TransactionStatus,
    amount: String(row.amount ?? '0'),
    refunded: str(row.refunded),
    occurredAt: String(row.occurredAt ?? ''),
    publicationId: str(row.publicationId),
    publicationName: str(row.publicationName),
    userKey: str(row.userKey),
    userName: str(row.userName),
    score: numOrNull(row.score),
    fullScore: numOrNull(row.fullScore),
  }
}

export interface TransactionSearch {
  params: Record<string, string>
  sorts?: Sort[]
  offset: number
  limit: number
}

/** One page of the sponsor's transactions, counted. */
export async function searchTransactions({ params, sorts, offset, limit }: TransactionSearch) {
  const page = await runQuery<Row>(TRANSACTIONS_TEMPLATE.list, { params, sorts, offset, limit, count: true })
  return { total: page.total, items: page.items.map(toTransaction) }
}

/** The totals of the rows the same parameters list. */
export async function readSummary(params: Record<string, string>): Promise<TransactionSummary> {
  const row = (await runQuery<Row>(TRANSACTIONS_TEMPLATE.summary, { params, limit: 1 })).items[0] ?? {}
  return {
    totalCount: num(row.totalCount),
    completedCount: num(row.completedCount),
    completedAmount: String(row.completedAmount ?? '0'),
    processingCount: num(row.processingCount),
    processingAmount: String(row.processingAmount ?? '0'),
    failedCount: num(row.failedCount),
    failedAmount: String(row.failedAmount ?? '0'),
  }
}

/** A publication offered as a filter. */
export interface PublicationOption {
  publicationId: string
  name: string
}

/** The sponsor's publications that can have rewards (every one but drafts), by name. */
export async function readPublicationOptions(): Promise<PublicationOption[]> {
  const page = await runQuery<Row>(PUBLISHING_TEMPLATE.publications, {
    params: {},
    sorts: [{ field: 'name', asc: true }],
    limit: 200,
  })
  return page.items
    .filter((row) => row.status !== 'DRAFT')
    .map((row) => ({ publicationId: String(row.publicationId), name: String(row.name ?? '') }))
}

/** An export format offered on the page (the platform's endpoint also has PDF; the page does not offer it). */
export type ExportFormat = 'csv' | 'xlsx'

/**
 * Exports the rows the parameters list, in the order shown, through the platform's endpoint
 * (`POST /api/queries/{id}/export?format=`), and hands the file to the browser under the server's name. Throws an
 * `ApiError` when the server refuses (`REPORT_TOO_LARGE` beyond its row limit: never cut short).
 */
export async function exportTransactions(format: ExportFormat, params: Record<string, string>, sorts?: Sort[]) {
  const response = await sessionFetch(
    `/api/queries/${encodeURIComponent(TRANSACTIONS_TEMPLATE.list)}/export?format=${format}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ params, sorts }),
    })
  if (!response.ok) throw await toApiError(response)
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = fileNameOf(response.headers.get('Content-Disposition')) ?? `${TRANSACTIONS_TEMPLATE.list}.${format}`
    link.rel = 'noopener'
    link.click()
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), 0)
  }
}

/** The page, and its address for one publication's transactions (`?publication=`). */
export const TRANSACTIONS_PATH = '/transactions'
export const transactionsOf = (publicationId: string) =>
  `${TRANSACTIONS_PATH}?publication=${encodeURIComponent(publicationId)}`

/** The filters an address asks for: only the publication can be given (from its page). */
export function filtersOf(search: string): TransactionFilters {
  const publicationId = new URLSearchParams(search).get('publication')
  return { ...NO_FILTERS, publicationId: publicationId?.trim() || null }
}

export const TRANSACTIONS_QUERY = ['qb', 'sponsor-transactions'] as const
