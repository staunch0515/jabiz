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
  publisherId: string
  company: string | null
  openCount: number
  totalCount: number
  misleading: number
  inappropriate: number
  rewardNotGiven: number
  spam: number
  /** Q82: reports of what a sponsor wrote in a conversation. */
  message: number
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
  /** Q82: the conversation reported (reason MESSAGE), else null. */
  conversationId: string | null
}

/** Q82: a conversation as the reviewers read it (the two sides stay ids). */
export interface Conversation {
  conversationId: string
  takerId: string
  publisherId: string
  publicationId: string
  status: string
  closedBy: string | null
  openedAt: string | null
}

/** Q82: a message of a conversation, with who wrote it (an id) and when; the text is plain. */
export interface ConversationMessage {
  messageId: string
  sender: string
  senderUserId: string
  body: string | null
  createdAt: string | null
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
      publisherId: String(row.publisherId ?? ''),
      company: str(row.company),
      openCount: num(row.openCount),
      totalCount: num(row.totalCount),
      misleading: num(row.misleading),
      inappropriate: num(row.inappropriate),
      rewardNotGiven: num(row.rewardNotGiven),
      spam: num(row.spam),
      message: num(row.message),
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
      status: String(row.status ?? ''), createdAt: str(row.createdAt), resolution: str(row.resolution),
      conversationId: str(row.conversationId) }
  })
}

/** Q82: one conversation, or null when it is none the caller may read. */
export async function readConversation(conversationId: string): Promise<Conversation | null> {
  const answer = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: DATASET.conversation } },
    body: { offset: 0, limit: 1, filters: [{ field: 'conversationId', op: 'eq', value: conversationId }] },
  }))
  const item = (answer.items ?? [])[0]
  if (!item) return null
  const row = (item.attributes ?? {}) as Row
  return { conversationId: String(item.id ?? conversationId), takerId: String(row.takerId ?? ''),
    publisherId: String(row.publisherId ?? ''), publicationId: String(row.publicationId ?? ''),
    status: String(row.status ?? ''), closedBy: str(row.closedBy), openedAt: str(row.openedAt) }
}

/** Q82: a conversation's messages, oldest first (at most 500). */
export async function readConversationMessages(conversationId: string): Promise<ConversationMessage[]> {
  const answer = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: DATASET.conversationMessage } },
    body: { offset: 0, limit: 500, filters: [{ field: 'conversationId', op: 'eq', value: conversationId }],
      sorts: [{ field: 'createdAt', asc: true }] },
  }))
  return (answer.items ?? []).map((item) => {
    const row = (item.attributes ?? {}) as Row
    return { messageId: String(item.id ?? ''), sender: String(row.sender ?? ''),
      senderUserId: String(row.senderUserId ?? ''), body: str(row.body), createdAt: str(row.createdAt) }
  })
}
