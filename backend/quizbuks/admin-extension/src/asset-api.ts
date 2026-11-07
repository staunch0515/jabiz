import { api, unwrap, type Filter } from '@jabiz/admin'
import type { Page, SortSpec } from './api'
import { DATASET, PAGE_LIMIT } from './ids'

/*
 * A Sponsor's asset library as support reads it (F-C1d, docs/quizbuks/plans/impl/F-C1.md): the administrators'
 * default datasets of `QbAsset` and `QbAssetUse`, read only. An operator's session reads its own Sponsors' only (the
 * server's OperatorScopes); nothing here writes.
 */

type Row = Record<string, unknown>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const num = (value: unknown): number | null => {
  if (value == null || value === '') return null
  const n = Number(value)
  return Number.isFinite(n) ? n : null
}

/** One asset of a Sponsor's library; its content stays with the Sponsor's own pages. */
export interface AssetRow {
  assetId: string
  kind: string
  name: string
  description: string | null
  /** What a text asset is for (`asset-text-usage`); none for the other kinds. */
  usage: string | null
  tags: string[]
  revision: number
  editedAt: string | null
}

/** Where an asset was copied to, and the asset's revision when it was. */
export interface AssetUseRow {
  useId: string
  assetId: string
  targetKind: string
  targetId: string
  assetRevision: number
  usedAt: string | null
}

/** The stored tags (a JSON list of texts); anything else reads as no tags. */
export function parseTags(value: unknown): string[] {
  if (typeof value !== 'string' || value.trim() === '') return []
  try {
    const parsed: unknown = JSON.parse(value)
    return Array.isArray(parsed) ? parsed.filter((tag): tag is string => typeof tag === 'string') : []
  } catch {
    return []
  }
}

const assetOf = (row: Row): AssetRow => ({
  assetId: String(row.assetId ?? ''),
  kind: String(row.kind ?? ''),
  name: String(row.name ?? ''),
  description: str(row.description),
  usage: str(row.usage),
  tags: parseTags(row.tags),
  revision: num(row.revision) ?? 0,
  editedAt: str(row.editedAt),
})

const useOf = (row: Row): AssetUseRow => ({
  useId: String(row.useId ?? ''),
  assetId: String(row.assetId ?? ''),
  targetKind: String(row.targetKind ?? ''),
  targetId: String(row.targetId ?? ''),
  assetRevision: num(row.assetRevision) ?? 0,
  usedAt: str(row.usedAt),
})

async function query(dataset: string, body: { offset: number; limit: number; filters: Filter[]; sorts?: SortSpec[] }) {
  const answer = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: dataset } },
    body,
  }))
  return (answer.items ?? []).map((item) => (item.attributes ?? {}) as Row)
}

/** One page of a Sponsor's assets, of one kind when given; the dataset's order (last changed first) unless sorted. */
export async function readSponsorAssets(sponsorId: string, offset: number, limit: number, sorts?: SortSpec[],
  kind?: string | null): Promise<Page<AssetRow>> {
  const filters: Filter[] = [{ field: 'sponsorId', op: 'eq', value: sponsorId }]
  if (kind) filters.push({ field: 'kind', op: 'eq', value: kind })
  const rows = await query(DATASET.asset, { offset, limit, filters, ...(sorts ? { sorts } : {}) })
  return { items: rows.map(assetOf) }
}

/**
 * How many places each of these assets was copied to, in one read of at most {@link PAGE_LIMIT} rows. When it read
 * that many, the counts are only at least these (`complete` is false).
 */
export async function countAssetUses(assetIds: readonly string[]): Promise<{ counts: Map<string, number>;
  complete: boolean }> {
  const counts = new Map<string, number>()
  if (assetIds.length === 0) return { counts, complete: true }
  const rows = await query(DATASET.assetUse, { offset: 0, limit: PAGE_LIMIT,
    filters: [{ field: 'assetId', op: 'in', values: [...assetIds] }] })
  for (const row of rows) {
    const id = String(row.assetId ?? '')
    counts.set(id, (counts.get(id) ?? 0) + 1)
  }
  return { counts, complete: rows.length < PAGE_LIMIT }
}

/** One page of the places one asset was copied to, the latest first. */
export async function readAssetUses(assetId: string, offset: number, limit: number): Promise<Page<AssetUseRow>> {
  const rows = await query(DATASET.assetUse, { offset, limit,
    filters: [{ field: 'assetId', op: 'eq', value: assetId }] })
  return { items: rows.map(useOf) }
}

/** The places that are a quiz (its id is the quiz's) and those that are a publication's. */
export const QUIZ_TARGETS: ReadonlySet<string> = new Set(['QUIZ_COVER', 'QUIZ_INTRO'])
export const PUBLICATION_TARGETS: ReadonlySet<string> = new Set(['PUBLICATION_COVER', 'PUBLICATION_REDEEM',
  'PUBLICATION_DIGITAL', 'PUBLICATION_REFERRAL', 'PUBLICATION_PRIZE'])

/** The asset kinds, in the library's order (`AssetKind`). */
export const ASSET_KINDS = ['IMAGE', 'PDF', 'AUDIO', 'VIDEO_LINK', 'LINK', 'ARTICLE', 'TEXT', 'PRIZE'] as const
