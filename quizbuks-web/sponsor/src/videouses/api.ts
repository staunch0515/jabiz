import { runProcess, runQuery } from '@jabiz/client'
import { titlesOf } from '../sharedvideos/api'

/*
 * F-S14 (docs/quizbuks/plans/impl/F-S14.md): the ledger of shared videos' uses and both sides' figures, typed by hand
 * after `videouse.VideoUseProcesses` and the templates `qb.sponsor.video-uses` (the user's side: the Sponsor's
 * publications using another's shared video) and `qb.sponsor.video-uses-provided` (the provider's side). No money
 * moves on the platform for a use: the two Sponsors settle it between themselves.
 */

export const VIDEO_USE_PROCESS = {
  save: 'QB_VIDEO_USE_SAVE',
  submit: 'QB_VIDEO_USE_SUBMIT',
  confirm: 'QB_VIDEO_USE_CONFIRM',
  reject: 'QB_VIDEO_USE_REJECT',
} as const

export const VIDEO_USE_TEMPLATE = {
  user: 'qb.sponsor.video-uses',
  provider: 'qb.sponsor.video-uses-provided',
} as const

export const videoUseQuery = (publicationId: string) => ['qb', 'video-uses', 'user', publicationId] as const
export const PROVIDED_QUERY = ['qb', 'video-uses', 'provider'] as const

/** A use's states (`VideoUseRules.STATUSES`). */
export type VideoUseStatus = 'DRAFT' | 'SUBMITTED' | 'CONFIRMED' | 'REJECTED'
/** How a use is priced (`VideoUseRules.PRICINGS`). */
export type Pricing = 'FIXED' | 'PER_MILLE' | 'PER_TAKER'
export const PRICINGS: readonly Pricing[] = ['FIXED', 'PER_MILLE', 'PER_TAKER']

/** The longest note and reason, as the server's columns (`QbVideoUses`). */
export const NOTE_LENGTH = 1000
export const REASON_LENGTH = 500

/** The four figures, each none when below the minimum group (Q9e) or not counted yet. */
export interface Figures {
  opens: number | null
  successes: number | null
  goes: number | null
  qualified: number | null
  /** The smallest group shown (`qb.stats.min-group`, never below 3). */
  minGroup: number
  /** When the daily job last counted them (none: not yet). */
  refreshedAt: string | null
  /** When they became final (the publication ended), else none. */
  finalisedAt: string | null
}

/** The ledger's use of a publication, as either side reads it (none of it before the user writes it). */
export interface VideoUse {
  videoUseId: string
  status: VideoUseStatus
  periodFrom: string
  periodTo: string
  pricing: Pricing
  price: number
  note: string | null
  submittedAt: string | null
  confirmedAt: string | null
  confirmedRevision: number | null
  rejectedAt: string | null
  rejectReason: string | null
  revision: number
}

/** A row of either side: the publication, the other side, the use (if any), the figures and the reference amount. */
export interface VideoUseRow {
  publicationId: string
  name: string
  publicationStatus: string
  wentOnAt: string | null
  endAt: string | null
  endedAt: string | null
  /** The shared video's title in each language. */
  videoTitles: Record<string, string>
  /** The other side's company: the provider on the user's side, the user on the provider's. */
  otherCompany: string | null
  use: VideoUse | null
  figures: Figures
  /** By the figures (none when the figure it is by is not shown), or the fixed price. */
  amount: number | null
}

export interface SaveVideoUseInput {
  videoUseId?: string
  publicationId?: string
  periodFrom: string
  periodTo: string
  pricing: Pricing
  price: string
  note: string
  baseRevision?: number
}

export interface VideoUseOutput {
  videoUseId: string
  status: VideoUseStatus
  revision: number
}

type Row = Record<string, unknown>
const text = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const count = (value: unknown): number | null => (value == null || value === '' ? null : Number(value))

function rowOf(row: Row, otherCompany: unknown): VideoUseRow {
  const videoUseId = text(row.videoUseId)
  return {
    publicationId: String(row.publicationId ?? ''),
    name: String(row.name ?? ''),
    publicationStatus: String(row.publicationStatus ?? ''),
    wentOnAt: text(row.wentOnAt),
    endAt: text(row.endAt),
    endedAt: text(row.endedAt),
    videoTitles: titlesOf(row.videoTitle),
    otherCompany: text(otherCompany),
    use: videoUseId ? {
      videoUseId,
      status: String(row.status) as VideoUseStatus,
      periodFrom: String(row.periodFrom ?? ''),
      periodTo: String(row.periodTo ?? ''),
      pricing: String(row.pricing) as Pricing,
      price: Number(row.price ?? 0),
      note: text(row.note),
      submittedAt: text(row.submittedAt),
      confirmedAt: text(row.confirmedAt),
      confirmedRevision: count(row.confirmedRevision),
      rejectedAt: text(row.rejectedAt),
      rejectReason: text(row.rejectReason),
      revision: Number(row.revision ?? 0),
    } : null,
    figures: {
      opens: count(row.opens),
      successes: count(row.successes),
      goes: count(row.goes),
      qualified: count(row.qualified),
      minGroup: Number(row.minGroup ?? 5),
      refreshedAt: text(row.refreshedAt),
      finalisedAt: text(row.finalisedAt),
    },
    amount: count(row.amount),
  }
}

/** The user's side of a publication; none when it uses no other Sponsor's shared video (it has no use). */
export async function readVideoUse(publicationId: string): Promise<VideoUseRow | null> {
  const page = await runQuery<Row>(VIDEO_USE_TEMPLATE.user, { params: { publicationId }, limit: 1 })
  const row = page.items[0]
  return row ? rowOf(row, row.providerCompany) : null
}

/** The provider's side: the publications using the Sponsor's shared videos, newest first. */
export async function readProvidedUses(limit = 100): Promise<VideoUseRow[]> {
  const page = await runQuery<Row>(VIDEO_USE_TEMPLATE.provider, { params: {}, limit })
  return page.items.map((row) => rowOf(row, row.userCompany))
}

export async function saveVideoUse(input: SaveVideoUseInput): Promise<VideoUseOutput> {
  return runProcess<VideoUseOutput>(VIDEO_USE_PROCESS.save, {
    videoUseId: input.videoUseId,
    publicationId: input.videoUseId ? undefined : input.publicationId,
    periodFrom: input.periodFrom,
    periodTo: input.periodTo,
    pricing: input.pricing,
    price: input.price.trim() === '' ? null : input.price.trim(),
    note: input.note,
    baseRevision: input.baseRevision,
  })
}

export async function submitVideoUse(videoUseId: string, baseRevision: number): Promise<VideoUseOutput> {
  return runProcess<VideoUseOutput>(VIDEO_USE_PROCESS.submit, { videoUseId, baseRevision })
}

export async function confirmVideoUse(videoUseId: string, baseRevision: number): Promise<VideoUseOutput> {
  return runProcess<VideoUseOutput>(VIDEO_USE_PROCESS.confirm, { videoUseId, baseRevision })
}

export async function rejectVideoUse(videoUseId: string, reason: string, baseRevision: number): Promise<VideoUseOutput> {
  return runProcess<VideoUseOutput>(VIDEO_USE_PROCESS.reject, { videoUseId, reason, baseRevision })
}
