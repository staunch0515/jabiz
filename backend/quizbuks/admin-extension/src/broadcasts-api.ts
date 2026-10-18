import { api, runProcess, runQuery, unwrap } from '@jabiz/admin'
import { BROADCAST, DATASET, PAGE_LIMIT, TEMPLATE } from './ids'

/*
 * What the announcements' page reads and runs (docs/quizbuks/plans/Q7-messages-legal.md, Q7c; P-04), typed by hand
 * after `messaging.BroadcastProcesses` and the template `qb.admin.broadcasts`. The rules of an announcement are the
 * server's: a save answers with the problems sending would meet, and the page shows those.
 */

type Row = Record<string, unknown>
export type Text = Record<string, string>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const count = (value: unknown): number | null => (value == null || value === '' ? null : Number(value))
const text = (value: unknown): Text => (value && typeof value === 'object' ? value as Text : {})

export const AUDIENCES = ['APP', 'SPONSOR'] as const
export const TYPES = ['SYSTEM', 'NOTICE', 'PROMO'] as const
export const TARGET_MODES = ['ALL', 'REGIONS', 'USERS'] as const
/** `country.Regions.ALL`, in the order the server writes them. */
export const REGIONS = ['GLOBAL', 'JP', 'CN', 'US', 'EUROPE', 'ASIA', 'NORTH_AMERICA', 'SOUTH_AMERICA'] as const
/** The languages an announcement is written in (English is the fallback and required). */
export const LANGUAGES = ['en', 'ja', 'zh'] as const
/** `BroadcastRules.MAX_TARGETS`. */
export const MAX_TARGETS = 1000

/** A row of `qb.admin.broadcasts`: an announcement or a sponsor's notice with its statistics (exact). */
export interface BroadcastRow {
  broadcastId: string
  senderKind: 'PLATFORM' | 'SPONSOR'
  senderId: string
  sponsorCompany: string | null
  audience: string
  type: string
  status: string
  title: Text
  body: Text
  targetMode: string
  regions: string[]
  targetCount: number | null
  publicationId: string | null
  publicationName: string | null
  scheduledAt: string | null
  sentAt: string | null
  expiresAt: string | null
  audienceCount: number | null
  reads: number | null
  clicks: number | null
  conversions: number | null
  cancelReason: string | null
  withdrawnAt: string | null
  withdrawnBy: string | null
  withdrawReason: string | null
  revision: number
  editedAt: string
}

export interface BroadcastFilter {
  senderKind?: string
  status?: string
  q?: string
}

/** `BroadcastProcesses.AdminSaveInput`. */
export interface AnnouncementInput {
  broadcastId: string | null
  baseRevision: number | null
  audience: string
  type: string
  title: Text
  body: Text
  targetMode: string
  regions: string[]
  /** Given only when named users are the target (the complete list). */
  userIds?: string[]
  scheduledAt: string | null
  expiresAt: string | null
}

export interface Problem {
  field: string | null
  code: string
  message: string
}

/** `BroadcastProcesses.SaveOutput`. */
export interface SaveOutput {
  broadcastId: string
  status: string
  revision: number
  sendsAt: string
  expiresAt: string | null
  targetCount: number
  problems: Problem[]
}

const regionsOf = (value: unknown): string[] => (value == null || value === '' ? [] : String(value).split(','))

function broadcast(row: Row): BroadcastRow {
  return {
    broadcastId: String(row.broadcastId),
    senderKind: row.senderKind === 'SPONSOR' ? 'SPONSOR' : 'PLATFORM',
    senderId: String(row.senderId ?? ''),
    sponsorCompany: str(row.sponsorCompany),
    audience: String(row.audience ?? ''),
    type: String(row.type ?? ''),
    status: String(row.status ?? ''),
    title: text(row.title),
    body: text(row.body),
    targetMode: String(row.targetMode ?? ''),
    regions: regionsOf(row.regions),
    targetCount: count(row.targetCount),
    publicationId: str(row.publicationId),
    publicationName: str(row.publicationName),
    scheduledAt: str(row.scheduledAt),
    sentAt: str(row.sentAt),
    expiresAt: str(row.expiresAt),
    audienceCount: count(row.audienceCount),
    reads: count(row.reads),
    clicks: count(row.clicks),
    conversions: count(row.conversions),
    cancelReason: str(row.cancelReason),
    withdrawnAt: str(row.withdrawnAt),
    withdrawnBy: str(row.withdrawnBy),
    withdrawReason: str(row.withdrawReason),
    revision: Number(row.revision ?? 0),
    editedAt: String(row.editedAt ?? ''),
  }
}

/** A page of every announcement and notice, newest edited first. */
export async function readBroadcasts(filter: BroadcastFilter, offset: number, limit: number) {
  const answer = await runQuery<Row>(TEMPLATE.broadcasts, {
    params: { senderKind: filter.senderKind || undefined, status: filter.status || undefined,
      q: filter.q || undefined },
    offset,
    limit,
    count: true,
  })
  return { ...answer, items: answer.items.map(broadcast) }
}

/** The named users of an announcement. */
export async function readTargets(broadcastId: string): Promise<string[]> {
  const ids: string[] = []
  // A page at a time: at most MAX_TARGETS in all.
  for (let offset = 0; offset <= MAX_TARGETS; offset += PAGE_LIMIT) {
    const answer = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
      params: { path: { resourceId: DATASET.broadcastTarget } },
      body: { offset, limit: PAGE_LIMIT, filters: [{ field: 'broadcastId', op: 'eq', value: broadcastId }] },
    }))
    const items = answer.items ?? []
    ids.push(...items.map((item) => String((item.attributes as Row | undefined)?.userId ?? '')))
    if (items.length < PAGE_LIMIT) break
  }
  return ids
}

export const saveAnnouncement = (input: AnnouncementInput) =>
  runProcess<SaveOutput>(BROADCAST.save, { ...input })

export const sendAnnouncement = (broadcastId: string, baseRevision: number) =>
  runProcess<Row>(BROADCAST.send, { broadcastId, baseRevision })

/** A scheduled announcement back to its draft, or a draft discarded. */
export const cancelAnnouncement = (broadcastId: string, baseRevision: number) =>
  runProcess<Row>(BROADCAST.cancel, { broadcastId, baseRevision })

/** Withdraws an announcement or a sponsor's notice from every inbox, with the reason (a second factor). */
export const withdrawBroadcast = (broadcastId: string, reason: string) =>
  runProcess<Row>(BROADCAST.withdraw, { broadcastId, reason })

/** Ids pasted one per line, by commas or spaces: each once, in order. */
export function userIdsOf(pasted: string): string[] {
  return [...new Set(pasted.split(/[\s,;]+/).map((id) => id.trim()).filter(Boolean))]
}

/** A share in whole per cent, or null without a base. */
export function rate(part: number | null, whole: number | null): number | null {
  if (part == null || whole == null || whole <= 0) return null
  return Math.round((part / whole) * 100)
}

/** A `datetime-local` value as an instant (null when empty), and back. */
export function instantOf(local: string): string | null {
  if (!local) return null
  const time = new Date(local)
  return Number.isNaN(time.getTime()) ? null : time.toISOString()
}

export function localOf(instant: string | null): string {
  if (!instant) return ''
  const time = new Date(instant)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${time.getFullYear()}-${pad(time.getMonth() + 1)}-${pad(time.getDate())}T${pad(time.getHours())}:`
    + `${pad(time.getMinutes())}`
}
