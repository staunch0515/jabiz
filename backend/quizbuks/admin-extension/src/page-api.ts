import { runQuery, type Filter } from '@jabiz/admin'
import type { Page, SortSpec } from './api'
import { SPONSOR_PAGE_PENDING, TEMPLATE } from './ids'

/** A row of `qb.admin.sponsor-pages` (Q60a-3): a sponsor's page with the sponsor's company. */
export interface SponsorPageRow {
  userId: string
  handle: string
  language: string
  headline: string
  story: string | null
  logo: string | null
  cover: string | null
  website: string | null
  status: string
  approvalRequestId: string | null
  submittedAt: string | null
  publishedAt: string | null
  rejectReason: string | null
  takenDownAt: string | null
  takedownReason: string | null
  reviewRequired: boolean
  editedAt: string | null
  company: string | null
}

type Row = Record<string, unknown>
const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))

function rowOf(row: Row): SponsorPageRow {
  return {
    userId: String(row.userId ?? ''),
    handle: String(row.handle ?? ''),
    language: String(row.language ?? ''),
    headline: String(row.headline ?? ''),
    story: str(row.story),
    logo: str(row.logo),
    cover: str(row.cover),
    website: str(row.website),
    status: String(row.status ?? ''),
    approvalRequestId: str(row.approvalRequestId),
    submittedAt: str(row.submittedAt),
    publishedAt: str(row.publishedAt),
    rejectReason: str(row.rejectReason),
    takenDownAt: str(row.takenDownAt),
    takedownReason: str(row.takedownReason),
    reviewRequired: row.reviewRequired === true || row.reviewRequired === 'true',
    editedAt: str(row.editedAt),
    company: str(row.company),
  }
}

/** One page of the sponsors' pages, of one status when given. */
export async function readSponsorPages(offset: number, limit: number, sorts?: SortSpec[],
  status?: string | null): Promise<Page<SponsorPageRow>> {
  const filters: Filter[] = status ? [{ field: 'status', op: 'eq', value: status }] : []
  const answer = await runQuery<Row>(TEMPLATE.sponsorPages, { filters, sorts, offset, limit, count: true })
  return { items: answer.items.map(rowOf), total: answer.total }
}

/** The review queue: the pages waiting for a review, the longest waiting first. */
export const readPendingSponsorPages = (offset: number, limit: number, sorts?: SortSpec[]) =>
  readSponsorPages(offset, limit, sorts ?? [{ field: 'submittedAt', asc: true }], SPONSOR_PAGE_PENDING)

/** One sponsor's page; null when there is none (or it is not the caller's operator's sponsor's). */
export async function readSponsorPage(userId: string): Promise<SponsorPageRow | null> {
  const answer = await runQuery<Row>(TEMPLATE.sponsorPages, { params: { userId }, offset: 0, limit: 1 })
  return answer.items[0] ? rowOf(answer.items[0]) : null
}
