import { runQuery, type Filter } from '@jabiz/admin'
import type { Page, SortSpec } from './api'
import { TEMPLATE } from './ids'

/*
 * F-S12: the videos Sponsors share with other Sponsors (backend `sharedvideo.SharedVideoProcesses`,
 * docs/quizbuks/plans/impl/F-S12.md), typed by hand after `qb.admin.shared-videos`. Headquarters reads every
 * provider's, an operator its own Sponsors' (the server's scope). The review is the platform's approval panel; the
 * takedown the platform's process page.
 */

export type SharedVideoStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | 'TAKEN_DOWN'
export const SHARED_VIDEO_STATUSES: readonly SharedVideoStatus[] = ['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN',
  'TAKEN_DOWN']

/** A row of `qb.admin.shared-videos`. */
export interface AdminSharedVideo {
  sharedVideoId: string
  providerId: string
  /** The provider's company (the onboarding's); null when the record is gone. */
  company: string | null
  operatorId: string | null
  operatorName: string | null
  titles: Record<string, string>
  url: string
  durationSec: number
  vertical: boolean
  topics: string[]
  status: SharedVideoStatus
  approvalRequestId: string | null
  sharedAt: string | null
  approvedAt: string | null
  rejectReason: string | null
  withdrawnAt: string | null
  takenDownAt: string | null
  takedownReason: string | null
  /** The operator whose member took it down; null: the platform (or not taken down). */
  takedownOperatorId: string | null
  /** The publications on that use it (an operator's session counts its own Sponsors' only). */
  usesRunning: number
}

type Row = Record<string, unknown>
const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const bool = (value: unknown): boolean => value === true || value === 'true'

/** The titles as the template gives them (the JSON text of the column). */
export function titlesOf(value: unknown): Record<string, string> {
  if (value && typeof value === 'object') return value as Record<string, string>
  if (typeof value !== 'string' || value === '') return {}
  try {
    const parsed: unknown = JSON.parse(value)
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, string>) : {}
  } catch {
    return {}
  }
}

/** The title in the reader's language, else English, else any. */
export function titleIn(titles: Record<string, string>, language: string): string {
  const short = language.split('-')[0]
  return titles[short] || titles.en || Object.values(titles).find((title) => !!title) || ''
}

function rowOf(row: Row): AdminSharedVideo {
  return {
    sharedVideoId: String(row.sharedVideoId ?? ''),
    providerId: String(row.providerId ?? ''),
    company: str(row.company),
    operatorId: str(row.operatorId),
    operatorName: str(row.operatorName),
    titles: titlesOf(row.title),
    url: String(row.url ?? ''),
    durationSec: Number(row.durationSec ?? 0),
    vertical: bool(row.vertical),
    topics: typeof row.topics === 'string' && row.topics !== '' ? row.topics.split(',') : [],
    status: (str(row.status) ?? 'PENDING') as SharedVideoStatus,
    approvalRequestId: str(row.approvalRequestId),
    sharedAt: str(row.sharedAt),
    approvedAt: str(row.approvedAt),
    rejectReason: str(row.rejectReason),
    withdrawnAt: str(row.withdrawnAt),
    takenDownAt: str(row.takenDownAt),
    takedownReason: str(row.takedownReason),
    takedownOperatorId: str(row.takedownOperatorId),
    usesRunning: Number(row.usesRunning ?? 0),
  }
}

/** One page of the shared videos; of one state when given, with the text searched. */
export async function readSharedVideos(offset: number, limit: number, sorts: SortSpec[] | undefined,
  status: SharedVideoStatus | null, q: string, filters: Filter[] = []): Promise<Page<AdminSharedVideo>> {
  const all: Filter[] = status ? [...filters, { field: 'status', op: 'eq', value: status }] : filters
  const answer = await runQuery<Row>(TEMPLATE.sharedVideos, { params: q.trim() === '' ? {} : { q: q.trim() },
    filters: all, sorts, offset, limit, count: true })
  return { items: answer.items.map(rowOf), total: answer.total }
}

/** One shared video; null when the session does not read it (another operator's) or there is none. */
export async function readSharedVideo(sharedVideoId: string): Promise<AdminSharedVideo | null> {
  const answer = await runQuery<Row>(TEMPLATE.sharedVideos, {
    filters: [{ field: 'sharedVideoId', op: 'eq', value: sharedVideoId }], limit: 1 })
  return answer.items.length === 0 ? null : rowOf(answer.items[0])
}

/** Whether it may be taken down (`SharedVideoRules.takeable`): approved, or withdrawn but still used. */
export const takeable = (status: SharedVideoStatus) => status === 'APPROVED' || status === 'WITHDRAWN'

/** Seconds as m:ss (h:mm:ss from an hour). */
export function duration(seconds: number): string {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = String(seconds % 60).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${s}` : `${m}:${s}`
}
