import { runQuery } from '@jabiz/admin'
import { TEMPLATE } from './ids'

/*
 * F-S14: the ledger of shared videos' uses, read only (template `qb.admin.video-uses`;
 * docs/quizbuks/plans/impl/F-S14.md), typed by hand after the template. An operator's session reads its own Sponsors'
 * (the users') uses only (the server's scope). Each figure below the minimum group comes as none.
 */

export type VideoUseStatus = 'DRAFT' | 'SUBMITTED' | 'CONFIRMED' | 'REJECTED'
export type Pricing = 'FIXED' | 'PER_MILLE' | 'PER_TAKER'

/** A use: its two sides, its state and pricing, the figures and the reference amount. */
export interface AdminVideoUse {
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

/** The uses, last changed first (at most `limit`). */
export async function readVideoUses(limit = 200): Promise<AdminVideoUse[]> {
  const answer = await runQuery<Row>(TEMPLATE.videoUses, { params: {}, limit,
    sorts: [{ field: 'editedAt', asc: false }] })
  return answer.items.map((row) => ({
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
  }))
}
