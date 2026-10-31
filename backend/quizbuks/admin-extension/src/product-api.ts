import { runQuery, type Filter } from '@jabiz/admin'
import type { Page, SortSpec } from './api'
import { TEMPLATE } from './ids'

/** A row of `qb.admin.sponsor-products` (Q60b-3): a sponsor's product with the sponsor's company and page. */
export interface SponsorProductRow {
  productId: string
  userId: string
  name: string
  description: string | null
  image: string | null
  price: string
  currency: string
  shopUrl: string
  status: string
  takenDownAt: string | null
  takedownReason: string | null
  takedownOperatorId: string | null
  editedAt: string | null
  company: string | null
  handle: string | null
}

type Row = Record<string, unknown>
const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))

function rowOf(row: Row): SponsorProductRow {
  return {
    productId: String(row.productId ?? ''),
    userId: String(row.userId ?? ''),
    name: String(row.name ?? ''),
    description: str(row.description),
    image: str(row.image),
    price: String(row.price ?? ''),
    currency: String(row.currency ?? ''),
    shopUrl: String(row.shopUrl ?? ''),
    status: String(row.status ?? ''),
    takenDownAt: str(row.takenDownAt),
    takedownReason: str(row.takedownReason),
    takedownOperatorId: str(row.takedownOperatorId),
    editedAt: str(row.editedAt),
    company: str(row.company),
    handle: str(row.handle),
  }
}

/** One page of the sponsors' products, of one status and one sponsor when given. */
export async function readSponsorProducts(offset: number, limit: number, sorts?: SortSpec[],
  status?: string | null, userId?: string | null): Promise<Page<SponsorProductRow>> {
  const filters: Filter[] = status ? [{ field: 'status', op: 'eq', value: status }] : []
  const answer = await runQuery<Row>(TEMPLATE.sponsorProducts, { params: userId ? { userId } : {}, filters, sorts,
    offset, limit, count: true })
  return { items: answer.items.map(rowOf), total: answer.total }
}

/** A price in its currency (the shop's own price), as the administrator's language writes it. */
export function formatShopPrice(price: string, currency: string, locale?: string): string {
  const amount = Number(price)
  const digits = currency === 'JPY' ? 0 : 2
  if (!Number.isFinite(amount)) return `${price} ${currency}`
  try {
    return new Intl.NumberFormat(locale, { style: 'currency', currency, minimumFractionDigits: digits,
      maximumFractionDigits: digits }).format(amount)
  } catch {
    return `${amount.toFixed(digits)} ${currency}`
  }
}

/** One sponsor's page views and product clicks in a period (Q60c-2; approximate, platform decision D65). */
export interface SponsorVisits {
  views: number
  clicks: number
}

/** The sponsor's visits from `from` to `to` (dates, `YYYY-MM-DD`); an operator's session reads its sponsors' only. */
export async function readSponsorVisits(userId: string, from: string, to: string): Promise<SponsorVisits> {
  const answer = await runQuery<Row>(TEMPLATE.sponsorStats, { params: { userId, from, to }, limit: 1 })
  const row = answer.items[0] ?? {}
  const count = (value: unknown) => (Number.isFinite(Number(value)) ? Number(value) : 0)
  return { views: count(row.views), clicks: count(row.clicks) }
}

