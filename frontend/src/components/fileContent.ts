import { useEffect, useState } from 'react'
import { fetchFileContent, fetchFileDownload } from '../api/files'

/**
 * An object URL of a file's content, fetched with the session (the token cannot go into an <img src>), and revoked
 * when the component unmounts or the file changes.
 */
export function useFileUrl(fileId: string | undefined, variant: string | undefined): { url?: string; failed: boolean } {
  const [state, setState] = useState<{ key?: string; url?: string; failed: boolean }>({ failed: false })
  const key = fileId ? `${fileId}/${variant ?? ''}` : undefined
  useEffect(() => {
    if (!fileId) return undefined
    let cancelled = false
    let objectUrl: string | undefined
    fetchFileContent(fileId, variant)
      .then((blob) => {
        if (cancelled) return
        objectUrl = URL.createObjectURL(blob)
        setState({ key: `${fileId}/${variant ?? ''}`, url: objectUrl, failed: false })
      })
      .catch(() => {
        if (!cancelled) setState({ key: `${fileId}/${variant ?? ''}`, failed: true })
      })
    return () => {
      cancelled = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [fileId, variant])
  return state.key === key ? { url: state.url, failed: state.failed } : { failed: false }
}

/**
 * Saves a file under the name the server gives it: fetched with the session, handed to the browser through an object
 * URL.
 */
export async function downloadFile(fileId: string): Promise<void> {
  const { blob, fileName } = await fetchFileDownload(fileId)
  const url = URL.createObjectURL(blob)
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = fileName ?? fileId
    link.rel = 'noopener'
    link.click()
  } finally {
    // The click has started the download; the URL is no longer needed.
    setTimeout(() => URL.revokeObjectURL(url), 0)
  }
}
