import { runQuery, type Filter } from '@jabiz/admin'
import type { Page, SortSpec } from './api'
import { exportTemplate, type ExportFormat } from './finance-api'
import { TEMPLATE } from './ids'

/*
 * The sponsors' usage by month (F-C2, docs/quizbuks/plans/impl/F-C2.md), typed by hand after `qb.admin.usage`. The
 * server limits an operator's session to its own sponsors (`OperatorScopes`); the page never decides that.
 */

/** A row of `qb.admin.usage`: one sponsor's month of one meter. */
export interface UsageRow {
  sponsorId: string
  company: string | null
  /** YYYY-MM, of the snapshots' days (the deployment's zone). */
  month: string
  meter: string
  /** The days of the month with a snapshot (a missing day does not lower the average). */
  days: number
  average: number
  peak: number
  /** The month's last snapshot. */
  latest: number
}

/** What the page asks: whole months, and one sponsor or meter when chosen. */
export interface UsageFilter {
  /** The first month, YYYY-MM. */
  from: string
  /** The last month, YYYY-MM. */
  to: string
  sponsorId?: string | null
  meter?: string | null
}

type Row = Record<string, unknown>
const num = (value: unknown) => (Number.isFinite(Number(value)) ? Number(value) : 0)

/** The days a month range covers, as the template's date parameters: the first's first day to the last's last. */
export function usageParams(filter: UsageFilter): Record<string, unknown> {
  const [year, month] = filter.to.split('-').map(Number)
  const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate()
  const params: Record<string, unknown> = { from: `${filter.from}-01`, to: `${filter.to}-${String(lastDay).padStart(2, '0')}` }
  if (filter.sponsorId) params.sponsorId = filter.sponsorId
  if (filter.meter) params.meter = filter.meter
  return params
}

/** Whether a month range can be asked: both months, the first not after the last. */
export const validRange = (filter: Pick<UsageFilter, 'from' | 'to'>) =>
  /^\d{4}-\d{2}$/.test(filter.from) && /^\d{4}-\d{2}$/.test(filter.to) && filter.from <= filter.to

/** The month of a date, YYYY-MM (UTC). */
export const monthOf = (date: Date = new Date()) =>
  `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, '0')}`

export async function readUsage(filter: UsageFilter, offset: number, limit: number,
  sorts?: SortSpec[]): Promise<Page<UsageRow>> {
  const filters: Filter[] = []
  const answer = await runQuery<Row>(TEMPLATE.usage, { params: usageParams(filter), filters, sorts, offset, limit,
    count: true })
  return {
    items: answer.items.map((row) => ({
      sponsorId: String(row.sponsorId ?? ''),
      company: row.company == null || row.company === '' ? null : String(row.company),
      month: String(row.month ?? ''),
      meter: String(row.meter ?? ''),
      days: num(row.days),
      average: num(row.average),
      peak: num(row.peak),
      latest: num(row.latest),
    })),
    total: answer.total,
  }
}

/** Every row of the range (not only the page) through the platform's export. */
export const exportUsage = (filter: UsageFilter, format: ExportFormat) =>
  exportTemplate(TEMPLATE.usage, usageParams(filter), format)

/** A value of a meter: bytes in MB (1 MB = 1,048,576 bytes, as the Sponsor's page and notice), others as counted. */
export function formatUsage(value: number, bytes: boolean, locale?: string): string {
  if (bytes) {
    return `${new Intl.NumberFormat(locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 })
      .format(value / 1_048_576)} MB`
  }
  return new Intl.NumberFormat(locale, { maximumFractionDigits: 0 }).format(value)
}
