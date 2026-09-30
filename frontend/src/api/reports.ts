import { fileNameOf } from './files'
import { sessionFetch } from './client'
import { toApiError } from './problem'
import type { components } from './schema'

/** An export format of POST /api/queries/{id}/export (docs/design/19-reports.md section 4). */
export type ExportFormat = 'csv' | 'xlsx' | 'pdf'

/** The request of a run, whose paging an export ignores. */
export type ExportRequest = Omit<components['schemas']['RunRequest'], 'offset' | 'limit' | 'count'>

/**
 * Exports a template's result and saves it under the name the server gives it. Outside the typed client: the answer
 * is a file, fetched with the session and handed to the browser through an object URL. Throws an ApiError when the
 * server refuses (for example REPORT_TOO_LARGE).
 */
export async function exportQuery(queryId: string, format: ExportFormat, body: ExportRequest): Promise<void> {
  const response = await sessionFetch(`/api/queries/${encodeURIComponent(queryId)}/export?format=${format}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!response.ok) throw await toApiError(response)
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = fileNameOf(response.headers.get('Content-Disposition')) ?? `${queryId}.${format}`
    link.rel = 'noopener'
    link.click()
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), 0)
  }
}
