import { api, runQuery, unwrap } from '@jabiz/admin'
import type { Page, SortSpec } from './api'
import { DATASET, TEMPLATE } from './ids'

/*
 * Takers' reports of publications (Q49a; backend `report.ReportProcesses`, docs/quizbuks/plans/Q49a-contest-report.md),
 * typed by hand after the template and the records. Closing them is the platform's process page.
 */

/** A row of `qb.admin.reports`: a reported publication and its reports counted. */
export interface ReportedPublication {
  publicationId: string
  name: string | null
  status: string | null
  publisherId: string
  company: string | null
  openCount: number
  totalCount: number
  misleading: number
  inappropriate: number
  rewardNotGiven: number
  spam: number
  other: number
  lastReportedAt: string | null
}

/** One report, as the reviewers read it (the reporter stays an id). */
export interface Report {
  reportId: string
  reason: string
  note: string | null
  status: string
  createdAt: string | null
  resolution: string | null
}

type Row = Record<string, unknown>
const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const num = (value: unknown): number => (value == null ? 0 : Number(value))

/** One page of the reported publications: with open reports (or none open) when asked. */
export async function readReportedPublications(offset: number, limit: number, sorts?: SortSpec[],
  open?: boolean | null): Promise<Page<ReportedPublication>> {
  const answer = await runQuery<Row>(TEMPLATE.abuseReports, { params: open == null ? {} : { open }, sorts,
    offset, limit, count: true })
  return {
    items: answer.items.map((row) => ({
      publicationId: String(row.publicationId ?? ''),
      name: str(row.name),
      status: str(row.status),
      publisherId: String(row.publisherId ?? ''),
      company: str(row.company),
      openCount: num(row.openCount),
      totalCount: num(row.totalCount),
      misleading: num(row.misleading),
      inappropriate: num(row.inappropriate),
      rewardNotGiven: num(row.rewardNotGiven),
      spam: num(row.spam),
      other: num(row.other),
      lastReportedAt: str(row.lastReportedAt),
    })),
    total: answer.total,
  }
}

/** A publication's reports, the latest first (at most 100: the counts above say how many there are). */
export async function readReports(publicationId: string): Promise<Report[]> {
  const answer = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: DATASET.report } },
    body: { offset: 0, limit: 100, filters: [{ field: 'publicationId', op: 'eq', value: publicationId }],
      sorts: [{ field: 'createdAt', asc: false }] },
  }))
  return (answer.items ?? []).map((item) => {
    const row = (item.attributes ?? {}) as Row
    return { reportId: String(item.id ?? ''), reason: String(row.reason ?? ''), note: str(row.note),
      status: String(row.status ?? ''), createdAt: str(row.createdAt), resolution: str(row.resolution) }
  })
}
