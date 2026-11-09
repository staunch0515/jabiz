import { runQuery } from '@jabiz/admin'
import { TEMPLATE } from './ids'

/*
 * F-S13: the suspicious inflation of exposures (template `qb.admin.exposure-suspicious`;
 * docs/quizbuks/plans/impl/F-S13.md), typed by hand after the template: the accounts the daily cap refused, by account.
 * An operator's session reads its own Sponsors' publications only (the server's scope).
 */

/** A row: a Taker the daily cap refused, how often and on how many publications, and what they counted. */
export interface SuspiciousExposure {
  userId: string
  /** The cap's refusals, a publication a UTC day each. */
  hits: number
  publications: number
  lastCappedOn: string | null
  successes: number
}

type Row = Record<string, unknown>
const num = (value: unknown): number => (value == null || value === '' ? 0 : Number(value))

/** The accounts the cap refused, most refusals first (at most `limit`). */
export async function readSuspiciousExposures(limit = 100): Promise<SuspiciousExposure[]> {
  const answer = await runQuery<Row>(TEMPLATE.exposureSuspicious, { params: {}, limit,
    sorts: [{ field: 'hits', asc: false }] })
  return answer.items.map((row) => ({
    userId: String(row.userId ?? ''),
    hits: num(row.hits),
    publications: num(row.publications),
    lastCappedOn: row.lastCappedOn == null || row.lastCappedOn === '' ? null : String(row.lastCappedOn),
    successes: num(row.successes),
  }))
}
