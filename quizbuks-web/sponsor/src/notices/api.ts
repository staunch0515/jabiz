import { runProcess, runQuery } from '@jabiz/client'
import { readDictionary, type DictionaryItem } from '../qb/api'
import type { I18nText } from '../qb/legal'
import { NOTICE_DICTIONARY, NOTICE_PROCESS, NOTICE_TEMPLATE } from './ids'

/*
 * The message centre's calls, typed by hand after the Java records and templates of backend/quizbuks `messaging`
 * (Q7c). The end-to-end test `inbox.spec.ts` runs every one of them against the server. The rules of a notice (its
 * publication, regions, number, interval, no addresses) are the server's: a save answers with the problems sending
 * would meet, and the page shows those, never its own.
 */

export type NoticeStatus = 'DRAFT' | 'SCHEDULED' | 'SENT' | 'CANCELLED' | 'WITHDRAWN'

/** A row of `qb.sponsor.broadcasts`: one of the sponsor's notices with its statistics (M-61). */
export interface NoticeRow {
  broadcastId: string
  status: NoticeStatus
  title: I18nText
  body: I18nText
  publicationId: string | null
  publicationName: string | null
  regions: string[]
  scheduledAt: string | null
  sentAt: string | null
  expiresAt: string | null
  /**
   * About how many takers it matched when sent: rounded down to tens (hundreds from 1,000); null below ten or not sent
   * (review M2).
   */
  audienceCount: number | null
  /** When the day's count was taken (the sponsor sees no live counts, review M2); null before the first. */
  snapshotAt: string | null
  /**
   * Each null when fewer than five (not shown, `BroadcastRules.MIN_GROUP`), not counted yet or not sent; clicks and
   * conversions also while fewer than `qb.broadcast.stats.min-audience` were reached.
   */
  reads: number | null
  clicks: number | null
  conversions: number | null
  cancelReason: string | null
  withdrawnAt: string | null
  withdrawnByAdmin: boolean
  withdrawReason: string | null
  revision: number
  editedAt: string
}

/** `BroadcastProcesses.SponsorSaveInput`. */
export interface NoticeInput {
  broadcastId?: string | null
  baseRevision?: number | null
  title: I18nText
  body: I18nText
  publicationId: string | null
  regions: string[]
  scheduledAt: string | null
}

/** One problem sending would meet, worded by the server. */
export interface Problem {
  field: string | null
  code: string
  message: string
}

/** `BroadcastProcesses.SaveOutput`. */
export interface SaveOutput {
  broadcastId: string
  status: NoticeStatus
  revision: number
  sendsAt: string
  expiresAt: string | null
  problems: Problem[]
}

/** `BroadcastProcesses.BroadcastOutput`. */
export interface BroadcastOutput {
  broadcastId: string
  status: NoticeStatus
  revision: number
  sentAt: string | null
  audienceCount: number | null
}

/** A row of `qb.sponsor.broadcast-publications`: a publication a notice may name. */
export interface PublicationOption {
  publicationId: string
  name: string
  status: string
  regions: string[]
  startAt: string
  endAt: string
  notices: number
}

/** A row of `qb.sponsor.inbox`: an announcement of QuizBuks to the sponsor. */
export interface Announcement {
  itemId: string
  type: string
  title: I18nText
  body: I18nText
  at: string
  expiresAt: string | null
  readAt: string | null
  unread: boolean
}

export const MESSAGES_PATH = '/messages'
export const NOTICES_QUERY = ['qb', 'notices'] as const
export const ANNOUNCEMENTS_QUERY = ['qb', 'announcements'] as const

/** The longest text of a notice (`BroadcastRules.SPONSOR_BODY_LENGTH`) and title (`QbInbox.TITLE_LENGTH`). */
export const NOTICE_LIMITS = { title: 120, body: 1_000 } as const

type Row = Record<string, unknown>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const count = (value: unknown): number | null => (value == null || value === '' ? null : Number(value))
const text = (value: unknown): I18nText => (value && typeof value === 'object' ? value as I18nText : {})
const regionsOf = (value: unknown): string[] => (value == null || value === '' ? [] : String(value).split(','))

function notice(row: Row): NoticeRow {
  return {
    broadcastId: String(row.broadcastId),
    status: String(row.status) as NoticeStatus,
    title: text(row.title),
    body: text(row.body),
    publicationId: str(row.publicationId),
    publicationName: str(row.publicationName),
    regions: regionsOf(row.regions),
    scheduledAt: str(row.scheduledAt),
    sentAt: str(row.sentAt),
    expiresAt: str(row.expiresAt),
    audienceCount: count(row.audienceCount),
    snapshotAt: str(row.snapshotAt),
    reads: count(row.reads),
    clicks: count(row.clicks),
    conversions: count(row.conversions),
    cancelReason: str(row.cancelReason),
    withdrawnAt: str(row.withdrawnAt),
    withdrawnByAdmin: row.withdrawnByAdmin === true,
    withdrawReason: str(row.withdrawReason),
    revision: Number(row.revision ?? 0),
    editedAt: String(row.editedAt),
  }
}

export async function readNotices(): Promise<NoticeRow[]> {
  return (await runQuery<Row>(NOTICE_TEMPLATE.notices, { limit: 200 })).items.map(notice)
}

export async function readPublicationOptions(): Promise<PublicationOption[]> {
  const page = await runQuery<Row>(NOTICE_TEMPLATE.publications, {
    params: { at: new Date().toISOString() },
    limit: 200,
  })
  return page.items.map((row) => ({
    publicationId: String(row.publicationId),
    name: String(row.name),
    status: String(row.status),
    regions: regionsOf(row.regions),
    startAt: String(row.startAt),
    endAt: String(row.endAt),
    notices: Number(row.notices ?? 0),
  }))
}

export async function readAnnouncements(): Promise<Announcement[]> {
  // The server's now (review L1): the device's clock is no parameter.
  const page = await runQuery<Row>(NOTICE_TEMPLATE.inbox, { params: {}, limit: 100 })
  return page.items.map((row) => ({
    itemId: String(row.itemId),
    type: String(row.type),
    title: text(row.title),
    body: text(row.body),
    at: String(row.at),
    expiresAt: str(row.expiresAt),
    readAt: str(row.readAt),
    unread: row.unread === true,
  }))
}

export const readNoticeStatuses = (): Promise<DictionaryItem[]> => readDictionary(NOTICE_DICTIONARY.status)

export const saveNotice = (input: NoticeInput) => runProcess<SaveOutput>(NOTICE_PROCESS.save, { ...input })

export const sendNotice = (broadcastId: string, baseRevision: number) =>
  runProcess<BroadcastOutput>(NOTICE_PROCESS.send, { broadcastId, baseRevision })

/** A scheduled notice back to its draft, or a draft discarded. */
export const cancelNotice = (broadcastId: string, baseRevision: number) =>
  runProcess<BroadcastOutput>(NOTICE_PROCESS.cancel, { broadcastId, baseRevision })

export const withdrawNotice = (broadcastId: string, baseRevision: number) =>
  runProcess<BroadcastOutput>(NOTICE_PROCESS.withdraw, { broadcastId, baseRevision })

export const markAnnouncementRead = (id: string) =>
  runProcess<{ changed: boolean }>(NOTICE_PROCESS.mark, { kind: 'BROADCAST', id, action: 'READ' })

/** A share in whole per cent, or null when either count is unknown (fewer than five) or there is no base. */
export function rate(part: number | null, whole: number | null): number | null {
  if (part == null || whole == null || whole <= 0) return null
  return Math.round((part / whole) * 100)
}

/** A `datetime-local` value (the device's time) as an instant, or null. */
export function instantOf(local: string): string | null {
  if (!local) return null
  const time = new Date(local)
  return Number.isNaN(time.getTime()) ? null : time.toISOString()
}

/** An instant as a `datetime-local` value in the device's time. */
export function localOf(instant: string | null): string {
  if (!instant) return ''
  const time = new Date(instant)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${time.getFullYear()}-${pad(time.getMonth() + 1)}-${pad(time.getDate())}T${pad(time.getHours())}:`
    + `${pad(time.getMinutes())}`
}
