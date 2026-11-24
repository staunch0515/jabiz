import { fetchFileContent } from '@jabiz/client'
import { cn } from '@jabiz/ui'
import { useEffect, useState } from 'react'

/**
 * The object URLs of the files' images shown, shared by every image of the same file and variant: a cover shown again
 * (a card that starts or stops its video, the stage's copy of it) paints at once rather than as an empty box while it
 * is fetched again. An URL nothing shows any more is released after a while (`RELEASE_MS`).
 */
interface Entry {
  url: string | null
  pending: Promise<string> | null
  users: number
  timer: ReturnType<typeof setTimeout> | null
}

const images = new Map<string, Entry>()
const RELEASE_MS = 60_000

function keyOf(fileId: string, variant: string | undefined): string {
  return `${fileId}/${variant ?? ''}`
}

/** The image's URL when it is already here (an image of the same file shown, or just released). */
export function cachedImage(fileId: string, variant?: string): string | null {
  return images.get(keyOf(fileId, variant))?.url ?? null
}

function acquire(key: string, fileId: string, variant: string | undefined): Entry {
  let entry = images.get(key)
  if (!entry) {
    entry = { url: null, pending: null, users: 0, timer: null }
    images.set(key, entry)
  }
  if (entry.timer) {
    clearTimeout(entry.timer)
    entry.timer = null
  }
  entry.users += 1
  if (!entry.url && !entry.pending) {
    const current = entry
    current.pending = fetchFileContent(fileId, variant).then((blob) => {
      current.pending = null
      // Released while it came (no one shows it any more): not kept.
      if (images.get(key) !== current) throw new Error('released')
      current.url = URL.createObjectURL(blob)
      return current.url
    }, (error) => {
      current.pending = null
      if (images.get(key) === current && current.users === 0) images.delete(key)
      throw error
    })
  }
  return entry
}

function releaseImage(key: string, entry: Entry) {
  entry.users -= 1
  if (entry.users > 0) return
  entry.timer = setTimeout(() => {
    entry.timer = null
    if (entry.users > 0 || images.get(key) !== entry) return
    images.delete(key)
    if (entry.url) URL.revokeObjectURL(entry.url)
  }, RELEASE_MS)
}

/**
 * An image of an uploaded file (an avatar, a cover). Files need the session (the access token lives in memory, never
 * in a cookie), so the content is fetched and shown through an object URL (shared, see `images`); the default content
 * security policy allows `img-src blob:`. A variant (`w256`) when given, the original otherwise; nothing while loading
 * or when the file cannot be read.
 */
export function FileImage({ fileId, variant, alt, className, ...rest }: {
  fileId: string
  variant?: string
  alt: string
  className?: string
  'data-testid'?: string
}) {
  const [loaded, setLoaded] = useState<{ key: string; url: string | null } | null>(null)
  const key = keyOf(fileId, variant)
  useEffect(() => {
    let live = true
    const entry = acquire(key, fileId, variant)
    if (!entry.url) {
      entry.pending?.then(
        (url) => {
          if (live) setLoaded({ key, url })
        },
        () => {
          if (live) setLoaded({ key, url: null })
        },
      )
    }
    return () => {
      live = false
      releaseImage(key, entry)
    }
  }, [key, fileId, variant])
  // An image already here shows from the first paint; one still coming shows once it is here.
  const url = loaded?.key === key ? loaded.url : cachedImage(fileId, variant)
  if (!url) return <span aria-hidden data-slot="image-loading" className={cn('bg-muted inline-block', className)} />
  return <img src={url} alt={alt} className={cn('object-cover', className)} {...rest} />
}
