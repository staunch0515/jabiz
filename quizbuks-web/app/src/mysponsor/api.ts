import { runProcess, runQuery } from '@jabiz/client'
import { useQuery } from '@tanstack/react-query'
import { MY_SPONSOR_PROCESS, MY_SPONSOR_TEMPLATE } from './ids'

/*
 * The app's sponsor page (Q68): typed by hand after the templates `qb.app.my-sponsor`, `qb.app.sponsor-publications`,
 * `qb.sponsor.dashboard` and `qb.sponsor.analytics.trend`, and the processes QB_PUBLICATION_PAUSE / RESUME. The server
 * limits every one to the caller's own; the page only shows. Nothing here moves Kudos: that stays in the console.
 */

export const MY_SPONSOR_PATH = '/my-sponsor'
export const MY_SPONSOR_QUERY = ['qb', 'my-sponsor'] as const

export type ReviewStatus = 'PENDING' | 'APPROVED' | 'REJECTED'
export type OnStatus = 'SCHEDULED' | 'LIVE' | 'PAUSED' | 'FUNDS_SHORT'

export interface MySponsor {
  company: string | null
  contactName: string | null
  avatar: string | null
  reviewStatus: ReviewStatus
}

export interface Figures {
  live: number
  paused: number
  fundsShort: number
  answering: number
  spentToday: number
  available: number
  pendingCount: number
  pendingAmount: number
  budgetRemaining: number
  zone: string
  refreshedAt: string | null
}

export interface OnPublication {
  publicationId: string
  name: string
  cover: string | null
  status: OnStatus
  pausedBy: 'SPONSOR' | 'ADMIN' | null
  starts: number
  completions: number
  passes: number
  answering: number
  spent: number
  budgetCap: number | null
  startAt: string
  endAt: string
}

export interface TrendDay {
  day: string
  completions: number
  passes: number
  spent: number
}

type Row = Record<string, unknown>
const num = (value: unknown) => (value == null ? 0 : Number(value))
const str = (value: unknown) => (value == null ? null : String(value))

/** The caller's sponsor account; null for a taker who is no sponsor. */
export async function readMySponsor(): Promise<MySponsor | null> {
  const row = (await runQuery<Row>(MY_SPONSOR_TEMPLATE.account, { params: {}, limit: 1 })).items[0]
  if (!row) return null
  return { company: str(row.company), contactName: str(row.contactName), avatar: str(row.avatar),
    reviewStatus: String(row.reviewStatus) as ReviewStatus }
}

/** Whether the signed-in taker is a sponsor too (the header's entry, the inbox's category). */
export function useMySponsor() {
  return useQuery({ queryKey: [...MY_SPONSOR_QUERY, 'account'], queryFn: readMySponsor, staleTime: 5 * 60_000 })
}

export async function readFigures(at: string): Promise<Figures> {
  const row = (await runQuery<Row>(MY_SPONSOR_TEMPLATE.dashboard, { params: { at }, limit: 1 })).items[0] ?? {}
  return {
    live: num(row.live), paused: num(row.paused), fundsShort: num(row.fundsShort), answering: num(row.answering),
    spentToday: num(row.spentToday), available: num(row.available), pendingCount: num(row.pendingCount),
    pendingAmount: num(row.pendingAmount), budgetRemaining: num(row.budgetRemaining),
    zone: str(row.zone) ?? 'UTC', refreshedAt: str(row.refreshedAt),
  }
}

export async function readOnPublications(): Promise<OnPublication[]> {
  const page = await runQuery<Row>(MY_SPONSOR_TEMPLATE.publications, { params: {}, limit: 100 })
  return page.items.map((row) => ({
    publicationId: String(row.publicationId), name: String(row.name), cover: str(row.cover),
    status: String(row.status) as OnStatus, pausedBy: str(row.pausedBy) as OnPublication['pausedBy'],
    starts: num(row.starts), completions: num(row.completions), passes: num(row.passes),
    answering: num(row.answering), spent: num(row.spent),
    budgetCap: row.budgetCap == null ? null : num(row.budgetCap), startAt: String(row.startAt),
    endAt: String(row.endAt),
  }))
}

/** One publication's last seven days, by the deployment's days (the trend's DAY buckets). */
export async function readWeek(publicationId: string, now: Date): Promise<TrendDay[]> {
  const from = new Date(now.getTime() - 6 * 24 * 3_600_000)
  const page = await runQuery<Row>(MY_SPONSOR_TEMPLATE.trend, {
    params: { publicationId, from: from.toISOString(), to: now.toISOString(), bucket: 'DAY' },
    limit: 10,
  })
  return page.items.map((row) => ({ day: String(row.day), completions: num(row.completions),
    passes: num(row.passes), spent: num(row.spent) }))
}

export const pausePublication = (publicationId: string) =>
  runProcess<{ status: string }>(MY_SPONSOR_PROCESS.pause, { publicationId })

export const resumePublication = (publicationId: string) =>
  runProcess<{ status: string }>(MY_SPONSOR_PROCESS.resume, { publicationId })
