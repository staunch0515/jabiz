import { fetchFileContent } from '@jabiz/client'
import { cn } from '@jabiz/ui'
import { useEffect, useState } from 'react'

/**
 * An image of an uploaded file (an avatar, a cover). Files need the session (the access token lives in memory, never
 * in a cookie), so the content is fetched and shown through an object URL, released again when the image goes; the
 * default content security policy allows `img-src blob:`. A variant (`w256`) when given, the original otherwise;
 * nothing while loading or when the file cannot be read.
 */
export function FileImage({ fileId, variant, alt, className, ...rest }: {
  fileId: string
  variant?: string
  alt: string
  className?: string
  'data-testid'?: string
}) {
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    let live = true
    let created: string | null = null
    fetchFileContent(fileId, variant).then(
      (blob) => {
        if (!live) return
        created = URL.createObjectURL(blob)
        setUrl(created)
      },
      () => {
        if (live) setUrl(null)
      },
    )
    return () => {
      live = false
      if (created) URL.revokeObjectURL(created)
      setUrl(null)
    }
  }, [fileId, variant])
  if (!url) return <span aria-hidden className={cn('bg-muted inline-block', className)} />
  return <img src={url} alt={alt} className={cn('object-cover', className)} {...rest} />
}
