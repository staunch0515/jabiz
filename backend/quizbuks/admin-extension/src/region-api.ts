import { runQuery, type Filter } from '@jabiz/admin'
import type { Page, SortSpec } from './api'
import { TEMPLATE } from './ids'

/*
 * F-S11: the exceptions to the rule on foreign videos and mainland China (headquarters every Sponsor's, an operator its
 * own Sponsors') (backend `region.RegionExceptions`,
 * docs/quizbuks/plans/impl/F-S11.md), the starts the rule refused by day, and the sites mainland China plays; typed by
 * hand after the templates. Adding, changing and revoking are the platform's process pages.
 */

/** A row of `qb.admin.region-exceptions`. */
export interface RegionException {
  exceptionId: string
  kind: 'PUBLICATION' | 'SPONSOR'
  publicationId: string | null
  publicationName: string | null
  /** The publication's card says it has a foreign video; null before it went on, or for a sponsor's exception. */
  foreignVideo: boolean | null
  sponsorId: string | null
  /** The Sponsor it belongs to (a publication's: its publisher). */
  ownerId: string | null
  /** That Sponsor's company; null when the onboarding record is gone. */
  company: string | null
  /** That Sponsor's operator now (F-S1b: the platform's column and filter). */
  operatorId: string | null
  operatorName: string | null
  reason: string
  /** The last day it counts (UTC); null: until revoked. */
  expiresOn: string | null
  revoked: boolean
  revokeReason: string | null
  inForce: boolean
  createdAt: string | null
  createdBy: string | null
  changedAt: string | null
  changedBy: string | null
}

/** A day's refused starts. */
export interface RefusalDay {
  day: string
  refused: number
}

type Row = Record<string, unknown>
const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const bool = (value: unknown): boolean => value === true || value === 'true'

/** One page of the exceptions; only those in force when asked. */
export async function readRegionExceptions(offset: number, limit: number, sorts: SortSpec[] | undefined,
  inForce: boolean | null, at: string, filters: Filter[] = []): Promise<Page<RegionException>> {
  const answer = await runQuery<Row>(TEMPLATE.regionExceptions, {
    params: inForce == null ? { at } : { at, inForce }, filters, sorts, offset, limit, count: true })
  return {
    items: answer.items.map((row) => ({
      exceptionId: String(row.exceptionId ?? ''),
      kind: row.kind === 'SPONSOR' ? 'SPONSOR' : 'PUBLICATION',
      publicationId: str(row.publicationId),
      publicationName: str(row.publicationName),
      foreignVideo: row.foreignVideo == null ? null : bool(row.foreignVideo),
      sponsorId: str(row.sponsorId),
      ownerId: str(row.ownerId),
      company: str(row.company),
      operatorId: str(row.operatorId),
      operatorName: str(row.operatorName),
      reason: String(row.reason ?? ''),
      expiresOn: str(row.expiresOn),
      revoked: bool(row.revoked),
      revokeReason: str(row.revokeReason),
      inForce: bool(row.inForce),
      createdAt: str(row.createdAt),
      createdBy: str(row.createdBy),
      changedAt: str(row.changedAt),
      changedBy: str(row.changedBy),
    })),
    total: answer.total,
  }
}

/** The refused starts of the days from `from` (a UTC date), newest first. */
export async function readRefusalDays(from: string): Promise<RefusalDay[]> {
  const answer = await runQuery<Row>(TEMPLATE.regionRefusals, { params: { from }, limit: 100 })
  return answer.items.map((row) => ({ day: String(row.day ?? ''), refused: Number(row.refused ?? 0) }))
}

/** The sites mainland China plays, as the controlled parameter says; null before its first value is published. */
export async function readVideoSites(): Promise<string | null> {
  const answer = await runQuery<Row>(TEMPLATE.videoSites, { limit: 1 })
  const value = str(answer.items[0]?.sites)
  if (value === null) return null
  return value.length >= 2 && value.startsWith('"') && value.endsWith('"') ? value.slice(1, -1) : value
}
