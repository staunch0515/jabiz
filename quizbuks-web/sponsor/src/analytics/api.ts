import { fileNameOf, runQuery, sessionFetch, toApiError } from '@jabiz/client'
import type { PublicationStatus } from '../publishing/api'
import { minuteNow } from '../qb/legal'
import { ANALYTICS_TEMPLATE, type AnalyticsTemplate } from './ids'

/*
 * The analytics pages' calls, typed by hand after the templates of Q9e1 (`qb.sponsor.dashboard`,
 * `qb.sponsor.analytics.{trend,top,overview,questions}`; docs/quizbuks/plans/Q9e-analytics.md). The pages only show
 * what the templates give: they never add figures up or work out the minimum group themselves. Amounts stay as the
 * server sends them (exact decimals) and are shown only through `formatKudos`. Days are the deployment's (the zone
 * the job recorded, D-Q9e-3), never the browser's.
 */

type Row = Record<string, unknown>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const num = (value: unknown): number => {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}
const numOrNull = (value: unknown): number | null => (value == null || value === '' ? null : num(value))
const amount = (value: unknown): string => (value == null || value === '' ? '0' : String(value))
const bool = (value: unknown): boolean => value === true || value === 'true'

/** The zone used while the job has not run yet (the templates say the same). */
export const DEFAULT_ZONE = 'UTC'

/** The sponsor's key figures (M-10): the counts and the wallet live, the rest as of the job's last run. */
export interface Dashboard {
  live: number
  paused: number
  fundsShort: number
  answering: number
  spentToday: string
  available: string
  pendingCount: number
  pendingAmount: string
  budgetRemaining: string
  /** The start of today in the deployment's zone. */
  dayStart: string | null
  zone: string
  /** When the analytics last ran; null before their first run. */
  refreshedAt: string | null
}

export function toDashboard(row: Row | undefined): Dashboard {
  const r = row ?? {}
  return {
    live: num(r.live),
    paused: num(r.paused),
    fundsShort: num(r.fundsShort),
    answering: num(r.answering),
    spentToday: amount(r.spentToday),
    available: amount(r.available),
    pendingCount: num(r.pendingCount),
    pendingAmount: amount(r.pendingAmount),
    budgetRemaining: amount(r.budgetRemaining),
    dayStart: str(r.dayStart),
    zone: str(r.zone) ?? DEFAULT_ZONE,
    refreshedAt: str(r.refreshedAt),
  }
}

/** The dashboard's row; `at` is now by the server's clock (today is its day in the deployment's zone). */
export async function readDashboard(at: string = minuteNow()): Promise<Dashboard> {
  const page = await runQuery<Row>(ANALYTICS_TEMPLATE.dashboard, { params: { at }, limit: 1 })
  return toDashboard(page.items[0])
}

/** The two periods of the trend (M-43): the last 24 hours by the hour, the last 7 days by the day. */
export type TrendRange = 'HOURS_24' | 'DAYS_7'
export const TREND_RANGES: readonly TrendRange[] = ['HOURS_24', 'DAYS_7']

/** A bucket of the trend; the hours are UTC hours, the days the deployment's. */
export interface TrendPoint {
  bucketStart: string
  /** The deployment zone's date of the bucket's start (`YYYY-MM-DD`). */
  day: string
  views: number
  starts: number
  completions: number
  passes: number
  winners: number
  spent: string
  /** The bucket holds increments of a time the job did not run (shown with a note). */
  gap: boolean
  /** The zone of `day` and of the hours' labels. */
  zone: string
}

export function toTrendPoint(row: Row): TrendPoint {
  return {
    bucketStart: String(row.bucketStart ?? ''),
    day: String(row.day ?? ''),
    views: num(row.views),
    starts: num(row.starts),
    completions: num(row.completions),
    passes: num(row.passes),
    winners: num(row.winners),
    spent: amount(row.spent),
    gap: bool(row.gap),
    zone: str(row.zone) ?? DEFAULT_ZONE,
  }
}

const HOUR = 3_600_000
const DAY = 24 * HOUR

/**
 * The trend template's parameters for a period ending now: 24 buckets of an hour (the hour now last) or 7 of a day.
 * The template puts `from` in its bucket and ends with the bucket of the time just before `to`; `to` is a minute
 * after now so that the bucket of now is the last one even on the hour.
 */
export function trendParams(range: TrendRange, at: string, publicationId?: string | null): Record<string, string> {
  const now = Date.parse(at)
  const params: Record<string, string> = range === 'HOURS_24'
    ? { bucket: 'HOUR', from: new Date(now - 23 * HOUR).toISOString() }
    : { bucket: 'DAY', from: new Date(now - 6 * DAY).toISOString() }
  params.to = new Date(now + 60_000).toISOString()
  if (publicationId) params.publicationId = publicationId
  return params
}

/** The totals of the trend's buckets for the same parameters, from the template (the page adds nothing up). */
export interface TrendTotals {
  periodStart: string | null
  periodEnd: string | null
  views: number
  starts: number
  completions: number
  passes: number
  winners: number
  spent: string
  /** The zone the trend's days and hours are shown in. */
  zone: string
}

export function toTrendTotals(row: Row | undefined): TrendTotals {
  const r = row ?? {}
  return {
    periodStart: str(r.periodStart),
    periodEnd: str(r.periodEnd),
    views: num(r.views),
    starts: num(r.starts),
    completions: num(r.completions),
    passes: num(r.passes),
    winners: num(r.winners),
    spent: amount(r.spent),
    zone: str(r.zone) ?? DEFAULT_ZONE,
  }
}

/** The trend's totals for the parameters the trend was read with. */
export async function readTrendTotals(params: Record<string, string>): Promise<TrendTotals> {
  const page = await runQuery<Row>(ANALYTICS_TEMPLATE.trendTotals, { params, limit: 1 })
  return toTrendTotals(page.items[0])
}

/** The buckets of the period, oldest first, every one present (the template fills in zeros). */
export async function readTrend(params: Record<string, string>): Promise<TrendPoint[]> {
  const page = await runQuery<Row>(ANALYTICS_TEMPLATE.trend, {
    params,
    sorts: [{ field: 'bucketStart', asc: true }],
    limit: 400,
  })
  return page.items.map(toTrendPoint)
}

/** One of the sponsor's publications by its completions of the last seven days (M-11). */
export interface TopPublication {
  publicationId: string
  name: string
  status: PublicationStatus
  completions7d: number
  spent7d: string
  completionsTotal: number
}

export function toTopPublication(row: Row): TopPublication {
  return {
    publicationId: String(row.publicationId),
    name: String(row.name ?? ''),
    status: row.status as PublicationStatus,
    completions7d: num(row.completions7d),
    spent7d: amount(row.spent7d),
    completionsTotal: num(row.completionsTotal),
  }
}

/** The five publications with the most completions of the seven days before `at`. */
export async function readTop(at: string = minuteNow()): Promise<TopPublication[]> {
  const page = await runQuery<Row>(ANALYTICS_TEMPLATE.top, {
    params: { at },
    sorts: [{ field: 'completions7d', asc: false }],
    limit: 5,
  })
  return page.items.map(toTopPublication)
}

/** The stage of the funnel losing the largest share, as the template marks it. */
export type FunnelDrop = 'VIEW_START' | 'START_COMPLETE' | 'COMPLETE_PASS'
const DROPS: readonly FunnelDrop[] = ['VIEW_START', 'START_COMPLETE', 'COMPLETE_PASS']

/** A publication in figures (M-40, M-41, M-43). */
export interface Overview {
  publicationId: string
  name: string
  status: PublicationStatus
  views: number
  starts: number
  completions: number
  passes: number
  winners: number
  spent: string
  inProgress: number
  /** Completions / starts, 0..1; null before the first start. */
  completionRate: number | null
  /** The funnel's first stage: the views, or the starts when fewer views were recorded. */
  funnelViews: number
  viewsBelowStarts: boolean
  viewToStart: number | null
  startToComplete: number | null
  completeToPass: number | null
  largestDrop: FunnelDrop | null
  /** The completions the shown average time and timeouts are of. */
  basedOn: number
  /** Seconds; null below the minimum group. */
  avgDurationSec: number | null
  timeouts: number | null
  /** Fewer completions than the minimum group: the average time and the timeouts are not shown. */
  suppressed: boolean
  minGroup: number
  last1h: number | null
  last24h: number | null
  last7d: number | null
  last30d: number | null
  /** When the App's four windows were counted. */
  appCountedAt: string | null
  /** Since when the shown average time and timeouts hold. */
  shownAt: string | null
  /** When the analytics last ran. */
  refreshedAt: string | null
  /** The deployment's zone, the times above are shown in (the job's; UTC before its first run). */
  zone: string
}

export function toOverview(row: Row): Overview {
  const drop = str(row.largestDrop)
  const suppressed = bool(row.suppressed)
  return {
    publicationId: String(row.publicationId),
    name: String(row.name ?? ''),
    status: row.status as PublicationStatus,
    views: num(row.views),
    starts: num(row.starts),
    completions: num(row.completions),
    passes: num(row.passes),
    winners: num(row.winners),
    spent: amount(row.spent),
    inProgress: num(row.inProgress),
    completionRate: numOrNull(row.completionRate),
    funnelViews: num(row.funnelViews),
    viewsBelowStarts: bool(row.viewsBelowStarts),
    viewToStart: numOrNull(row.viewToStart),
    startToComplete: numOrNull(row.startToComplete),
    completeToPass: numOrNull(row.completeToPass),
    largestDrop: drop && (DROPS as readonly string[]).includes(drop) ? (drop as FunnelDrop) : null,
    basedOn: num(row.basedOn),
    // Below the minimum group the time and the timeouts are never shown, whatever came with them (as toQuestion).
    avgDurationSec: suppressed ? null : numOrNull(row.avgDurationSec),
    timeouts: suppressed ? null : numOrNull(row.timeouts),
    suppressed,
    minGroup: num(row.minGroup),
    last1h: numOrNull(row.last1h),
    last24h: numOrNull(row.last24h),
    last7d: numOrNull(row.last7d),
    last30d: numOrNull(row.last30d),
    appCountedAt: str(row.appCountedAt),
    shownAt: str(row.shownAt),
    refreshedAt: str(row.refreshedAt),
    zone: str(row.zone) ?? DEFAULT_ZONE,
  }
}

/** The publication's overview, or null when it is not one of the sponsor's (or does not exist). */
export async function readOverview(publicationId: string): Promise<Overview | null> {
  const page = await runQuery<Row>(ANALYTICS_TEMPLATE.overview, { params: { publicationId }, limit: 1 })
  return page.items[0] ? toOverview(page.items[0]) : null
}

/**
 * A stage of the funnel: its count and its rate against the stage before (null for the first one). The share lost
 * at a stage is `1 - rate`: the only figure the page works out itself, from the template's own rate.
 */
export interface FunnelStage {
  key: 'views' | 'starts' | 'completions' | 'passes'
  count: number
  rate: number | null
  /** The drop into this stage is the largest. */
  largestDrop: boolean
}

/** The funnel's four stages, as the template gives them (M-41). */
export function funnelOf(o: Overview): FunnelStage[] {
  return [
    { key: 'views', count: o.funnelViews, rate: null, largestDrop: false },
    { key: 'starts', count: o.starts, rate: o.viewToStart, largestDrop: o.largestDrop === 'VIEW_START' },
    { key: 'completions', count: o.completions, rate: o.startToComplete,
      largestDrop: o.largestDrop === 'START_COMPLETE' },
    { key: 'passes', count: o.passes, rate: o.completeToPass, largestDrop: o.largestDrop === 'COMPLETE_PASS' },
  ]
}

export type Difficulty = 'EASY' | 'MEDIUM' | 'HARD'
const DIFFICULTIES: readonly Difficulty[] = ['EASY', 'MEDIUM', 'HARD']

/** A question of the publication (M-42); the figures are null while suppressed (fewer than k completions). */
export interface QuestionFigures {
  questionNo: number
  stem: string
  points: number
  completions: number | null
  correct: number | null
  correctRate: number | null
  difficulty: Difficulty | null
  suppressed: boolean
  basedOn: number
  minGroup: number
  shownAt: string | null
}

export function toQuestion(row: Row): QuestionFigures {
  const difficulty = str(row.difficulty)
  const suppressed = bool(row.suppressed)
  return {
    questionNo: num(row.questionNo),
    stem: String(row.stem ?? ''),
    points: num(row.points),
    // A suppressed question shows no figure, whatever came with it.
    completions: suppressed ? null : numOrNull(row.completions),
    correct: suppressed ? null : numOrNull(row.correct),
    correctRate: suppressed ? null : numOrNull(row.correctRate),
    difficulty: !suppressed && difficulty && (DIFFICULTIES as readonly string[]).includes(difficulty)
      ? (difficulty as Difficulty) : null,
    suppressed,
    basedOn: num(row.basedOn),
    minGroup: num(row.minGroup),
    shownAt: str(row.shownAt),
  }
}

/**
 * The publication's questions in order, in one version it ran on (Q51b; by default the one it runs on): each
 * version's answers are counted apart, as the same numbers are other questions after a switch.
 */
export async function readQuestions(publicationId: string, versionId?: string | null): Promise<QuestionFigures[]> {
  const page = await runQuery<Row>(ANALYTICS_TEMPLATE.questions, {
    params: versionId ? { publicationId, versionId } : { publicationId },
    sorts: [{ field: 'questionNo', asc: true }],
    limit: 500,
  })
  return page.items.map(toQuestion)
}

/** A version of its quiz the publication ran on (Q51b): the one it runs on, or one it switched from and when. */
export interface AnalyticsVersion {
  versionId: string
  label: string
  title: string
  current: boolean
  replacedAt: string | null
}

/** The versions the publication ran on, newest first. */
export async function readVersions(publicationId: string): Promise<AnalyticsVersion[]> {
  const page = await runQuery<Row>(ANALYTICS_TEMPLATE.versions, {
    params: { publicationId },
    sorts: [{ field: 'versionNumber', asc: false }],
    limit: 500,
  })
  return page.items.map((row) => ({
    versionId: String(row.versionId),
    label: String(row.label ?? ''),
    title: String(row.title ?? ''),
    current: bool(row.current),
    replacedAt: str(row.replacedAt),
  }))
}

/** An export format offered on the pages (the platform's endpoint also has PDF; the pages do not offer it). */
export type ExportFormat = 'csv' | 'xlsx'

/** What can be exported from a publication's analytics (E-7: aggregates only). */
export type ExportTarget = 'trend' | 'overview' | 'questions'
export const EXPORT_TARGETS: readonly ExportTarget[] = ['trend', 'overview', 'questions']

const TARGET_TEMPLATE: Record<ExportTarget, AnalyticsTemplate> = {
  trend: ANALYTICS_TEMPLATE.trend,
  overview: ANALYTICS_TEMPLATE.overview,
  questions: ANALYTICS_TEMPLATE.questions,
}

/**
 * Exports a template's rows for the parameters through the platform's endpoint (`POST /api/queries/{id}/export`)
 * and hands the file to the browser under the server's name. Throws an `ApiError` when the server refuses.
 */
export async function exportAnalytics(target: ExportTarget, format: ExportFormat, params: Record<string, string>) {
  const id = TARGET_TEMPLATE[target]
  const sorts = target === 'trend' ? [{ field: 'bucketStart', asc: true }]
    : target === 'questions' ? [{ field: 'questionNo', asc: true }] : undefined
  const response = await sessionFetch(`/api/queries/${encodeURIComponent(id)}/export?format=${format}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ params, sorts }),
  })
  if (!response.ok) throw await toApiError(response)
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = fileNameOf(response.headers.get('Content-Disposition')) ?? `${id}.${format}`
    link.rel = 'noopener'
    link.click()
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), 0)
  }
}

/** A bucket's label: the hour in the deployment's zone, or the deployment's day. */
export function bucketLabel(point: TrendPoint, range: TrendRange, zone: string, locale?: string): string {
  if (range === 'DAYS_7') {
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(point.day)
    if (!match) return point.day
    return new Intl.DateTimeFormat(locale, { month: '2-digit', day: '2-digit', timeZone: 'UTC' })
      .format(new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]))))
  }
  const time = Date.parse(point.bucketStart)
  if (Number.isNaN(time)) return point.bucketStart
  return new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
    timeZone: safeZone(zone) }).format(new Date(time))
}

/** A time (to the minute) in the deployment's zone, as the pages show "updated at". */
export function zoneTime(value: string | null, zone: string, locale?: string): string {
  const time = value ? Date.parse(value) : Number.NaN
  if (Number.isNaN(time)) return ''
  return new Intl.DateTimeFormat(locale, { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit',
    minute: '2-digit', hourCycle: 'h23', timeZone: safeZone(zone) }).format(new Date(time))
}

/** The zone if the browser knows it, otherwise UTC (never the browser's own). */
export function safeZone(zone: string): string {
  try {
    new Intl.DateTimeFormat(undefined, { timeZone: zone })
    return zone
  } catch {
    return DEFAULT_ZONE
  }
}

/** A share (0..1) as a percentage with at most one decimal. */
export function percent(rate: number | null, locale?: string): string {
  if (rate === null) return '—'
  return new Intl.NumberFormat(locale, { style: 'percent', maximumFractionDigits: 1 }).format(rate)
}

/** The publication's analytics page. */
export const analyticsPath = (publicationId: string) => `/publications/${encodeURIComponent(publicationId)}/analytics`

/** The publication of an analytics page's path, or null. */
export function analyticsIdOf(path: string): string | null {
  const match = /^\/publications\/([^/]+)\/analytics\/?$/.exec(path)
  return match ? decodeURIComponent(match[1]) : null
}

export const ANALYTICS_QUERY = ['qb', 'analytics'] as const
/** How often the figures are read again while the page is open (they change every ten minutes, some live). */
export const REFRESH_MS = 60_000
