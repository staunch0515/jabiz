import { sessionFetch } from './client'
import { saveResponse } from './reports'

/** What a preview reads: the layout's parameters, the point in time and the language (docs/design/22-documents.md). */
export interface DocumentPreview {
  params: Record<string, unknown>
  asOf?: string
  knownAt?: string
  language?: string
}

/**
 * Saves an issued document exactly as it was issued (GET /api/documents/runs/{id}/pdf, 22 section 4): the kept bytes,
 * never laid out again. Throws an ApiError when the server refuses.
 */
export async function downloadDocument(runId: string): Promise<void> {
  const response = await sessionFetch(`/api/documents/runs/${encodeURIComponent(runId)}/pdf`)
  await saveResponse(response, `${runId}.pdf`)
}

/** Saves a preview of a document as it would be issued now, marked as a preview and not kept. */
export async function previewDocument(layoutId: string, body: DocumentPreview): Promise<void> {
  const response = await sessionFetch(`/api/documents/${encodeURIComponent(layoutId)}/preview`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  await saveResponse(response, `${layoutId}-preview.pdf`)
}
