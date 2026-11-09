import { runQuery } from '@jabiz/admin'
import { TEMPLATE } from './ids'

/*
 * F-S14: the ledger of shared videos' uses, read only (template `qb.admin.video-uses`;
 * docs/quizbuks/plans/impl/F-S14.md), typed by hand after the template, and the same as the providers' operators read it
 * (`qb.admin.video-uses-provided`). An operator's session reads the uses of its own Sponsors, as users through the first
 * and as providers through the second (the server's scope); headquarters reads every one through both. Each figure
 * below the minimum group comes as none.
 */

export type VideoUseStatus = 'DRAFT' | 'SUBMITTED' | 'CONFIRMED' | 'REJECTED'
export type Pricing = 'FIXED' | 'PER_MILLE' | 'PER_TAKER'

/** A use: its two sides, its state and pricing, the figures and the reference amount. */
export interface AdminVideoUse {
  /** Through which read path it came: the users' operators' (`user`) or the providers' (`provider`); both for HQ. */
  paths: ('user' | 'provider')[]
  videoUseId: string
  publicationId: string
  name: string | null
  sponsorId: string
  sponsorCompany: string | null
  providerId: string
  providerCompany: string | null
  /** The shared video's title in each language. */
  videoTitles: Record<string, string>
  status: VideoUseStatus
  periodFrom: string
  periodTo: string
  pricing: Pricing
  price: number
  note: string | null
  rejectReason: string | null
  confirmedRevision: number | null
  revision: number
  editedAt: string | null
  opens: number | null
  successes: number | null
  goes: number | null
  qualified: number | null
  minGroup: number
  finalisedAt: string | null
  amount: number | null
}

type Row = Record<string, unknown>
const text = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const count = (value: unknown): number | null => (value == null || value === '' ? null : Number(value))

function titles(value: unknown): Record<string, string> {
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
export function titleIn(values: Record<string, string>, language: string): string {
  return values[language.split('-')[0]] || values.en || Object.values(values).find((v) => !!v) || ''
}

/** The uses through both read paths, each once, last changed first (at most `limit` a path). */
export async function readVideoUses(limit = 200): Promise<AdminVideoUse[]> {
  const options = { params: {}, limit, sorts: [{ field: 'editedAt', asc: false }] }
  const [users, providers] = await Promise.all([runQuery<Row>(TEMPLATE.videoUses, options),
    runQuery<Row>(TEMPLATE.videoUsesProvided, options)])
  const merged = new Map<string, AdminVideoUse>()
  for (const [path, answer] of [['user', users], ['provider', providers]] as const) {
    for (const row of answer.items) {
      const use = toUse(row)
      const known = merged.get(use.videoUseId)
      if (known) known.paths.push(path)
      else merged.set(use.videoUseId, { ...use, paths: [path] })
    }
  }
  return [...merged.values()].sort((a, b) => (b.editedAt ?? '').localeCompare(a.editedAt ?? ''))
}

function toUse(row: Row): Omit<AdminVideoUse, 'paths'> {
  return {
    videoUseId: String(row.videoUseId ?? ''),
    publicationId: String(row.publicationId ?? ''),
    name: text(row.name),
    sponsorId: String(row.sponsorId ?? ''),
    sponsorCompany: text(row.sponsorCompany),
    providerId: String(row.providerId ?? ''),
    providerCompany: text(row.providerCompany),
    videoTitles: titles(row.videoTitle),
    status: String(row.status) as VideoUseStatus,
    periodFrom: String(row.periodFrom ?? ''),
    periodTo: String(row.periodTo ?? ''),
    pricing: String(row.pricing) as Pricing,
    price: Number(row.price ?? 0),
    note: text(row.note),
    rejectReason: text(row.rejectReason),
    confirmedRevision: count(row.confirmedRevision),
    revision: Number(row.revision ?? 0),
    editedAt: text(row.editedAt),
    opens: count(row.opens),
    successes: count(row.successes),
    goes: count(row.goes),
    qualified: count(row.qualified),
    minGroup: Number(row.minGroup ?? 5),
    finalisedAt: text(row.finalisedAt),
    amount: count(row.amount),
  }
}

/** One version of a use: who wrote it, by which act, when, and what the entry said then. */
export interface AdminHistoryEntry {
  versionNo: number
  processName: string
  actorId: string
  recordedAt: string
  status: VideoUseStatus
  pricing: Pricing
  price: number
  periodFrom: string
  periodTo: string
  rejectReason: string | null
  revision: number
}

/** Every version of a use, oldest first, through the path the page read it by. */
export async function readVideoUseHistory(videoUseId: string, path: 'user' | 'provider'): Promise<AdminHistoryEntry[]> {
  const answer = await runQuery<Row>(path === 'user' ? TEMPLATE.videoUseHistory : TEMPLATE.videoUseHistoryProvided,
    { params: { videoUseId }, limit: 200 })
  return answer.items.map((row) => ({
    versionNo: Number(row.versionNo ?? 0),
    processName: String(row.processName ?? ''),
    actorId: String(row.actorId ?? ''),
    recordedAt: String(row.recordedAt ?? ''),
    status: String(row.status) as VideoUseStatus,
    pricing: String(row.pricing) as Pricing,
    price: Number(row.price ?? 0),
    periodFrom: String(row.periodFrom ?? ''),
    periodTo: String(row.periodTo ?? ''),
    rejectReason: text(row.rejectReason),
    revision: Number(row.revision ?? 0),
  }))
}

/** The act of a version from the process that wrote it. */
export function actOf(processName: string, versionNo: number): 'CREATED' | 'CORRECTED' | 'SUBMITTED' | 'CONFIRMED'
  | 'REJECTED' {
  switch (processName) {
    case 'QB_VIDEO_USE_SUBMIT': return 'SUBMITTED'
    case 'QB_VIDEO_USE_CONFIRM': return 'CONFIRMED'
    case 'QB_VIDEO_USE_REJECT': return 'REJECTED'
    default: return versionNo <= 1 ? 'CREATED' : 'CORRECTED'
  }
}
