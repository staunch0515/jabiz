import { api, runProcess, runQuery, unwrap, type QueryPage } from '@jabiz/client'
import type { I18nText } from '../qb/legal'
import { MY_SPONSOR_PATH } from '../mysponsor/api'
import { INBOX_DATASET, INBOX_PROCESS, INBOX_TEMPLATE } from './ids'

/*
 * The app inbox's calls, typed by hand after the Java records and templates of backend/quizbuks `messaging` (Q7c).
 * The end-to-end test `inbox.spec.ts` runs every one of them against the server. The server decides what is in a
 * taker's inbox (their country comes from their profile, never from the page); the page only filters and shows.
 */

export type ItemKind = 'MESSAGE' | 'BROADCAST'
/** The inbox's filters (U-60): system messages, announcements and notices; promotions; rewards; reminders. */
/** Q68: SPONSOR, a sponsor's own messages (the cash rewards, claims and digital prizes of their publications). */
export type Category = 'SYSTEM' | 'PROMO' | 'REWARD' | 'REMINDER' | 'SPONSOR'
/** Where a message leads (`QbInbox.ACTION_*`). */
export type Action = 'RESULT' | 'WALLET' | 'PAYOUT' | 'CASH' | 'REDEEM' | 'DIGITAL'

/** A row of `qb.app.inbox`: a message of the taker's own, or an announcement or notice that matches them. */
export interface InboxItem {
  itemKind: ItemKind
  itemId: string
  type: string
  category: Category
  /** A message's kind (`REWARD_PAID` …); none for an announcement. */
  kind: string | null
  /** `PLATFORM` or `SPONSOR` for an announcement; none for a message. */
  sender: string | null
  /** The company of a sponsor's notice. */
  sponsorName: string | null
  title: I18nText
  body: I18nText
  /** Markdown (QuizBuks's texts) or plain text (a sponsor's notice, never rendered). */
  markdown: boolean
  publicationId: string | null
  action: Action | null
  actionId: string | null
  at: string
  expiresAt: string | null
  readAt: string | null
  clickedAt: string | null
  unread: boolean
}

export interface InboxFilter {
  category?: Category
  unread?: boolean
  q?: string
  /** Dates (`yyyy-mm-dd`) in the device's zone; `to` is included. */
  from?: string
  to?: string
}

/** The badge's counts (each at most 99). */
export interface Unread {
  messages: number
  broadcasts: number
  total: number
}

export interface InboxState {
  promoOff: boolean
  readAllBefore: string | null
}

export const INBOX_PATH = '/inbox'
export const INBOX_QUERY = ['qb', 'inbox'] as const
export const UNREAD_QUERY = [...INBOX_QUERY, 'unread'] as const
export const STATE_QUERY = [...INBOX_QUERY, 'state'] as const

/** How often the badge asks while the app is in front (and on entering, and on coming back to the front). */
export const UNREAD_INTERVAL = 5 * 60_000

/** An item's page: `/inbox/message/<id>`, `/inbox/broadcast/<id>`. */
export function itemPath(kind: ItemKind, id: string): string {
  return `${INBOX_PATH}/${kind.toLowerCase()}/${encodeURIComponent(id)}`
}

const ITEM_PATH = /^\/inbox\/(message|broadcast)\/([^/]+)$/

/** The item of a path such as `/inbox/broadcast/<id>`, or null for another path. */
export function inboxItemOf(path: string): { kind: ItemKind; id: string } | null {
  const match = ITEM_PATH.exec(path)
  if (!match) return null
  try {
    return { kind: match[1].toUpperCase() as ItemKind, id: decodeURIComponent(match[2]) }
  } catch {
    return null
  }
}

/** Where an item leads in the app, or null: its quiz, an attempt's result, the wallet or the payout account. */
export function targetOf(item: Pick<InboxItem, 'itemKind' | 'action' | 'actionId' | 'publicationId'>): string | null {
  if (item.itemKind === 'MESSAGE') {
    if (item.action === 'RESULT' && item.actionId) return `/result/${encodeURIComponent(item.actionId)}`
    if (item.action === 'WALLET') return '/wallet'
    if (item.action === 'PAYOUT') return '/wallet/payout'
    // Q68: a sponsor's own message leads to their page in the app (what to do about it is the console's).
    if (item.action === 'CASH' || item.action === 'REDEEM' || item.action === 'DIGITAL') return MY_SPONSOR_PATH
  }
  return item.publicationId ? `/p/${encodeURIComponent(item.publicationId)}` : null
}

/** The badge's text: the count, at most "99+". */
export function badgeText(count: number): string {
  return count > 99 ? '99+' : String(count)
}

/** A date of the device's calendar as the start of that day there (an instant). */
export function dayStart(date: string): string {
  const [y, m, d] = date.split('-').map(Number)
  return new Date(y, m - 1, d).toISOString()
}

/** The day after a date of the device's calendar, at its start: the end of a range that includes the date. */
export function dayEnd(date: string): string {
  const [y, m, d] = date.split('-').map(Number)
  return new Date(y, m - 1, d + 1).toISOString()
}

type Row = Record<string, unknown>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const num = (value: unknown): number => {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}
const text = (value: unknown): I18nText => (value && typeof value === 'object' ? value as I18nText : {})

function item(row: Row): InboxItem {
  return {
    itemKind: row.itemKind === 'BROADCAST' ? 'BROADCAST' : 'MESSAGE',
    itemId: String(row.itemId),
    type: String(row.type ?? ''),
    category: (str(row.category) ?? 'SYSTEM') as Category,
    kind: str(row.kind),
    sender: str(row.sender),
    sponsorName: str(row.sponsorName),
    title: text(row.title),
    body: text(row.body),
    markdown: row.markdown === true,
    publicationId: str(row.publicationId),
    action: str(row.action) as Action | null,
    actionId: str(row.actionId),
    at: String(row.at),
    expiresAt: str(row.expiresAt),
    readAt: str(row.readAt),
    clickedAt: str(row.clickedAt),
    unread: row.unread === true,
  }
}

/**
 * A page of the inbox, newest first, as the server has it now (the device's clock is no parameter: review L1; what
 * expired is out of the feed).
 */
export async function readInbox(language: string, filter: InboxFilter, offset: number,
  limit: number, languages?: readonly string[]): Promise<QueryPage<InboxItem>> {
  const page = await runQuery<Row>(INBOX_TEMPLATE.inbox, {
    params: {
      lang: language.split('-')[0],
      // Q59b: the sponsors' notices about publications of the languages the taker sees only.
      languages: languages && languages.length > 0 ? languages.join(',') : undefined,
      category: filter.category,
      unread: filter.unread || undefined,
      q: filter.q || undefined,
      from: filter.from ? dayStart(filter.from) : undefined,
      to: filter.to ? dayEnd(filter.to) : undefined,
    },
    offset,
    limit,
  })
  return { ...page, items: page.items.map(item) }
}

/** One item of the taker's inbox now; null when it is not (or no longer) in it. */
export async function readInboxItem(kind: ItemKind, id: string): Promise<InboxItem | null> {
  const page = await runQuery<Row>(INBOX_TEMPLATE.inbox, {
    params: { itemKind: kind, itemId: id },
    limit: 1,
  })
  return page.items[0] ? item(page.items[0]) : null
}

export async function readUnread(languages?: readonly string[]): Promise<Unread> {
  const params = languages && languages.length > 0 ? { languages: languages.join(',') } : {}
  const row = (await runQuery<Row>(INBOX_TEMPLATE.unread, { params, limit: 1 })).items[0] ?? {}
  const messages = num(row.messages)
  const broadcasts = num(row.broadcasts)
  return { messages, broadcasts, total: messages + broadcasts }
}

/** `InboxProcesses.MarkOutput`: whether this was the first such mark. */
export const markItem = (kind: ItemKind, id: string, action: 'READ' | 'CLICK') =>
  runProcess<{ changed: boolean }>(INBOX_PROCESS.mark, { kind, id, action })

export const readAll = () => runProcess<InboxState>(INBOX_PROCESS.readAll, {})

export const saveInboxSettings = (promoOff: boolean) =>
  runProcess<InboxState>(INBOX_PROCESS.settings, { promoOff })

/** The taker's inbox settings; promotions on until they turn them off. */
export async function readInboxState(): Promise<InboxState> {
  const page = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: INBOX_DATASET.myState } },
    body: { limit: 1 },
  }))
  const row = ((page.items ?? [])[0]?.attributes ?? {}) as Row
  return { promoOff: row.promoOff === true, readAllBefore: str(row.readAllBefore) }
}
