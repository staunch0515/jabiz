import { sessionFetch } from './client'
import { toApiError } from './problem'
import type { components } from './schema'

/**
 * Uploading and reading files (docs/design/14-files.md section 5). Outside the typed client: an upload is multipart and
 * a content is binary. The access token lives in memory only (decision D15), so an image cannot be an <img src> of
 * the API: previews fetch the content with the session and show it through an object URL.
 */

/** What an upload answers with (the OpenAPI schema FileInfo). */
export type UploadedFile = components['schemas']['FileInfo']

/** Uploads one file under a policy; throws an ApiError with the server's violations when it is refused. */
export async function uploadFile(policy: string, file: Blob, fileName: string): Promise<UploadedFile> {
  const form = new FormData()
  form.append('file', file, fileName)
  const response = await sessionFetch(`/api/files?policy=${encodeURIComponent(policy)}`, { method: 'POST', body: form })
  if (!response.ok) throw await toApiError(response)
  return (await response.json()) as UploadedFile
}

/** The content of a file (or of one of its variants) as a blob. */
export async function fetchFileContent(fileId: string, variant?: string): Promise<Blob> {
  const path = `/api/files/${encodeURIComponent(fileId)}/content${variant ? `/${encodeURIComponent(variant)}` : ''}`
  const response = await sessionFetch(path)
  if (!response.ok) throw await toApiError(response)
  return response.blob()
}

/** The original's content and the file name the server suggests (Content-Disposition). */
export async function fetchFileDownload(fileId: string): Promise<{ blob: Blob; fileName?: string }> {
  const response = await sessionFetch(`/api/files/${encodeURIComponent(fileId)}/content`)
  if (!response.ok) throw await toApiError(response)
  return { blob: await response.blob(), fileName: fileNameOf(response.headers.get('Content-Disposition')) }
}

/** The file name of a Content-Disposition header: the UTF-8 form (RFC 6266) first, else the plain one. */
export function fileNameOf(header: string | null): string | undefined {
  if (!header) return undefined
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(header)
  if (encoded) {
    try {
      return decodeURIComponent(encoded[1].trim())
    } catch {
      // fall back to the plain name
    }
  }
  const plain = /filename="([^"]*)"/i.exec(header)
  return plain ? plain[1] : undefined
}

/** The narrowest variant at least `width` pixels wide, else the original (undefined). */
export function previewVariant(variants: readonly string[] | undefined, width: number): string | undefined {
  const widths = (variants ?? [])
    .map((name) => ({ name, width: Number(/^w(\d+)$/.exec(name)?.[1]) }))
    .filter((v) => Number.isFinite(v.width))
    .sort((a, b) => a.width - b.width)
  return widths.find((v) => v.width >= width)?.name
}

/** A size for people, in binary units like the server's limits. */
export function formatBytes(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${Math.round((bytes / (1024 * 1024)) * 10) / 10} MB`
  if (bytes >= 1024) return `${Math.round((bytes / 1024) * 10) / 10} KB`
  return `${bytes} B`
}
