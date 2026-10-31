import { runQuery } from '@jabiz/client'
import { STATS_TEMPLATE } from './ids'

/* The views of the sponsor's page and the clicks on their products (Q60c): the templates' rows, and the period. */

/** The periods the page offers, in days ending today. */
export const STATS_PERIODS = [7, 30, 90] as const
export type StatsPeriod = (typeof STATS_PERIODS)[number]

export interface StatsDay {
  day: string
  views: number
  clicks: number
}

export interface ProductClicks {
  productId: string
  name: string
  status: string
  clicks: number
}

type Row = Record<string, unknown>
const count = (value: unknown) => {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}

/** A date as `YYYY-MM-DD`, in the browser's calendar. */
function isoDate(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** The first and last day of the period ending on `today` (the browser's today by default). */
export function periodOf(days: StatsPeriod, today: Date = new Date()): { from: string; to: string } {
  const first = new Date(today.getFullYear(), today.getMonth(), today.getDate() - (days - 1))
  return { from: isoDate(first), to: isoDate(today) }
}

/** Every day of the period with its views and clicks (zeros included), first day first. */
export async function readStatsDays(period: { from: string; to: string }): Promise<StatsDay[]> {
  const page = await runQuery<Row>(STATS_TEMPLATE.days, { params: period, limit: 400 })
  return page.items.map((row) => ({ day: String(row.day), views: count(row.views), clicks: count(row.clicks) }))
}

/** Each of the sponsor's products with its clicks in the period, most clicked first. */
export async function readProductClicks(period: { from: string; to: string }): Promise<ProductClicks[]> {
  const page = await runQuery<Row>(STATS_TEMPLATE.products, { params: period, limit: 100 })
  return page.items.map((row) => ({ productId: String(row.productId), name: String(row.name ?? ''),
    status: String(row.status ?? ''), clicks: count(row.clicks) }))
}
