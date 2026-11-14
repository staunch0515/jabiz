import { runQuery } from '@jabiz/admin'
import type { Page, SortSpec } from './api'
import { exportTemplate, type ExportFormat } from './finance-api'
import { TEMPLATE } from './ids'

/*
 * F-M15 b: the wind-ups of erased Sponsors' platform fee accounts (backend `fee.MoneyClose`), typed by hand after
 * `qb.admin.money-closures` and `qb.admin.money-closure-amounts`. Headquarters' finance reads them; nothing is written
 * here (a wind-up is the system's; fees owed are waived, if at all, through a fee request's credit note).
 */

/** What the list shows: not done, done with fees owed, waiting past the alert days; or all. */
export type ClosureShow = 'OPEN' | 'OWED' | 'SLOW' | 'ALL'
export const CLOSURE_SHOWS: readonly ClosureShow[] = ['OPEN', 'OWED', 'SLOW', 'ALL']

/** A row of `qb.admin.money-closures`. */
export interface MoneyClosure {
  sponsorId: string
  status: string
  reasonCode: string | null
  erasedAt: string | null
  lastRunAt: string | null
  closedAt: string | null
  alertedAt: string | null
  owed: string
  slow: boolean
}

/** The row of `qb.admin.money-closure-amounts`: summed from the payment parts, the lots and the ledger. */
export interface MoneyClosureAmounts {
  paidFromCredit: string
  paidFromCash: string
  creditVoided: string
  owed: string
  cashLeft: string
}

type Row = Record<string, unknown>
const str = (value: unknown) => (value === null || value === undefined ? null : String(value))
const amount = (value: unknown) => (value === null || value === undefined ? '0' : String(value))

/** Now, to the minute: whether a wind-up waits past the alert days is the template's, at this time. */
export const minuteNow = (now: Date = new Date()) =>
  new Date(Math.floor(now.getTime() / 60_000) * 60_000).toISOString()

function closureParams(show: ClosureShow, at: string): Record<string, string> {
  return show === 'ALL' ? { at } : { at, show }
}

function closureOf(row: Row): MoneyClosure {
  return { sponsorId: String(row.sponsorId ?? ''), status: String(row.status ?? ''), reasonCode: str(row.reasonCode),
    erasedAt: str(row.erasedAt), lastRunAt: str(row.lastRunAt), closedAt: str(row.closedAt),
    alertedAt: str(row.alertedAt), owed: amount(row.owed), slow: row.slow === true }
}

export async function readMoneyClosures(show: ClosureShow, at: string, offset: number, limit: number,
  sorts?: SortSpec[]): Promise<Page<MoneyClosure>> {
  const page = await runQuery<Row>(TEMPLATE.moneyClosures, { params: closureParams(show, at), sorts, offset, limit,
    count: true })
  return { total: page.total, items: page.items.map(closureOf) }
}

/** One Sponsor's wind-up; null when they have none. */
export async function readMoneyClosure(sponsorId: string, at: string): Promise<MoneyClosure | null> {
  const page = await runQuery<Row>(TEMPLATE.moneyClosures, { params: { at },
    filters: [{ field: 'sponsorId', op: 'eq', value: sponsorId }], limit: 1 })
  return page.items.length === 0 ? null : closureOf(page.items[0])
}

export async function readMoneyClosureAmounts(sponsorId: string): Promise<MoneyClosureAmounts | null> {
  const page = await runQuery<Row>(TEMPLATE.moneyClosureAmounts, { params: { sponsorId }, limit: 1 })
  const row = page.items[0]
  if (!row) return null
  return { paidFromCredit: amount(row.paidFromCredit), paidFromCash: amount(row.paidFromCash),
    creditVoided: amount(row.creditVoided), owed: amount(row.owed), cashLeft: amount(row.cashLeft) }
}

export const exportMoneyClosures = (show: ClosureShow, at: string, format: ExportFormat) =>
  exportTemplate(TEMPLATE.moneyClosures, closureParams(show, at), format)
