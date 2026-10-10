import { api, sessionFetch, unwrap } from './client'
import { ApiError, toApiError } from './problem'
import { saveResponse } from './reports'
import type { components } from '@jabiz/client'

/** Imports (docs/design/20-imports.md section 6). */
export type ImportEntry = components['schemas']['ImportEntry']
export type ImportReport = components['schemas']['ImportReportResponse']
export type ImportIssue = components['schemas']['ImportIssueEntry']
export type ImportMapping = components['schemas']['ImportMapping']
export type ImportOptions = NonNullable<ImportMapping['options']>
export type ImportInspection = components['schemas']['ImportInspectResponse']
export type ImportRunSummary = components['schemas']['ImportRunSummary']
export type ImportRunDetail = components['schemas']['ImportRunDetail']

/** The outcome of a commit: imported, or rejected with every problem (nothing was imported). */
export type CommitOutcome = { committed: true; report: ImportReport } | { committed: false; report: ImportReport }

export async function inspectFile(importId: string, fileId: string, options?: ImportOptions): Promise<ImportInspection> {
  return unwrap(api.POST('/api/imports/{importId}/inspect', {
    params: { path: { importId } },
    body: { fileId, options },
  }))
}

export async function previewImport(importId: string, fileId: string, mapping: ImportMapping,
  params: Record<string, unknown> | undefined): Promise<ImportReport> {
  return unwrap(api.POST('/api/imports/{importId}/preview', {
    params: { path: { importId } },
    body: { fileId, mapping, params },
  }))
}

/**
 * Commits the import. A rejected commit (422 IMPORT_REJECTED) is an outcome, not a failure: its problem carries the
 * report, recorded under its run id. Other refusals (409 already imported, 403) are thrown as ApiError.
 */
export async function commitImport(importId: string, fileId: string, mapping: ImportMapping,
  params: Record<string, unknown> | undefined, notes: string | undefined): Promise<CommitOutcome> {
  const response = await sessionFetch(`/api/imports/${encodeURIComponent(importId)}/commit`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fileId, mapping, params, notes }),
  })
  if (response.ok) return { committed: true, report: (await response.json()) as ImportReport }
  const error = await toApiError(response)
  if (error instanceof ApiError && error.status === 422 && error.problem.report) {
    return { committed: false, report: error.problem.report as ImportReport }
  }
  throw error
}

export type ExportFormat = 'csv' | 'xlsx' | 'pdf'

/** Saves the report of an import run (GET /api/imports/runs/{id}/export). */
export async function exportImportRun(runId: string, format: ExportFormat): Promise<void> {
  const response = await sessionFetch(`/api/imports/runs/${encodeURIComponent(runId)}/export?format=${format}`)
  await saveResponse(response, `import-${runId}.${format}`)
}
