import { runQuery } from '@jabiz/admin'
import { TEMPLATE } from './ids'

/*
 * F-S8: the figures of blocking (templates `qb.admin.blocks`, `qb.admin.blocked-sponsors`;
 * docs/quizbuks/plans/impl/F-S8.md), typed by hand after the templates. Aggregates only: never whose blocks.
 */

export type BlockKind = 'PUBLICATION' | 'QUIZ' | 'SPONSOR' | 'PROVIDER'
export const BLOCK_KINDS: readonly BlockKind[] = ['PUBLICATION', 'QUIZ', 'SPONSOR', 'PROVIDER']

/** A row of `qb.admin.blocks`: one kind's blocks and the takers who made them. */
export interface BlockKindRow {
  kind: BlockKind
  blocks: number
  blockers: number
  /** The takers blocking anything (the same on every row). */
  allBlockers: number
}

/** A row of `qb.admin.blocked-sponsors`: a Sponsor and its blockers, rounded down to tens (from the minimum group). */
export interface BlockedSponsorRow {
  sponsorId: string
  company: string | null
  blockers: number
  minGroup: number
}

type Row = Record<string, unknown>
const num = (value: unknown): number => (value == null || value === '' ? 0 : Number(value))

/** The four kinds in their order, a kind the server did not give with zeros. */
export async function readBlockKinds(): Promise<BlockKindRow[]> {
  const answer = await runQuery<Row>(TEMPLATE.blocks, { limit: 10 })
  const byKind = new Map(answer.items.map((row) => [String(row.kind), row]))
  const all = num(answer.items[0]?.allBlockers)
  return BLOCK_KINDS.map((kind) => {
    const row = byKind.get(kind)
    return { kind, blocks: num(row?.blocks), blockers: num(row?.blockers), allBlockers: all }
  })
}

/** The Sponsors most blocked, most first (at most `limit`). */
export async function readBlockedSponsors(limit = 20): Promise<BlockedSponsorRow[]> {
  const answer = await runQuery<Row>(TEMPLATE.blockedSponsors, { limit,
    sorts: [{ field: 'blockers', asc: false }] })
  return answer.items.map((row) => ({
    sponsorId: String(row.sponsorId ?? ''),
    company: row.company == null || row.company === '' ? null : String(row.company),
    blockers: num(row.blockers),
    minGroup: num(row.minGroup) || 20,
  }))
}
