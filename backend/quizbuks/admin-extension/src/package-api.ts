import { api, unwrap } from '@jabiz/admin'
import type { Page } from './api'
import { DATASET } from './ids'

/*
 * A Sponsor's imports of sponsor packages as support reads them (F-C4, docs/quizbuks/plans/impl/F-C4.md): the
 * administrators' default dataset of `QbPackageImport`, read only, newest first. An operator's session reads its own
 * Sponsors' only (the server's OperatorScopes); nothing here writes.
 */

type Row = Record<string, unknown>

/** One import: how, its state, how far it got and when; what it made stays with the Sponsor's own pages. */
export interface PackageImportRow {
  importId: string
  mode: string
  status: string
  formatVersion: number
  stepsDone: number
  stepsTotal: number
  lastStep: string | null
  packageHash: string
  startedAt: string | null
  updatedAt: string | null
  finishedAt: string | null
}

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const num = (value: unknown): number => {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}

export const importOf = (row: Row): PackageImportRow => ({
  importId: String(row.importId ?? ''),
  mode: String(row.mode ?? ''),
  status: String(row.status ?? ''),
  formatVersion: num(row.formatVersion),
  stepsDone: num(row.stepsDone),
  stepsTotal: num(row.stepsTotal),
  lastStep: str(row.lastStep),
  packageHash: String(row.packageHash ?? ''),
  startedAt: str(row.startedAt),
  updatedAt: str(row.updatedAt),
  finishedAt: str(row.finishedAt),
})

/** One page of a Sponsor's imports, the newest first. */
export async function readPackageImports(sponsorId: string, offset: number, limit: number):
  Promise<Page<PackageImportRow>> {
  const answer = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: DATASET.packageImport } },
    body: { offset, limit, filters: [{ field: 'sponsorId', op: 'eq', value: sponsorId }],
      sorts: [{ field: 'startedAt', asc: false }] },
  }))
  return { items: (answer.items ?? []).map((item) => importOf((item.attributes ?? {}) as Row)) }
}
