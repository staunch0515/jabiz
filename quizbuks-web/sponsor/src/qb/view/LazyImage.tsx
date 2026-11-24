import { cn } from '@jabiz/ui'
import { useEffect, useRef, useState } from 'react'
import { cachedImage, FileImage } from '../FileImage'

/**
 * A file's image that is fetched only once it comes near the screen (docs/quizbuks/plans/QP-pages.md section 10:
 * every card of the feed is a read with the session). Browsers without IntersectionObserver, and an image already
 * fetched, show it at once.
 */
export function LazyImage({ fileId, variant, alt, className }: {
  fileId: string
  variant?: string
  alt: string
  className?: string
}) {
  const ref = useRef<HTMLSpanElement>(null)
  // An image already here (the same cover shown a moment ago) shows at once, not first as an empty box.
  const [near, setNear] = useState(() => typeof IntersectionObserver === 'undefined' || !!cachedImage(fileId, variant))
  useEffect(() => {
    if (near || !ref.current) return undefined
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) setNear(true)
    }, { rootMargin: '200px' })
    observer.observe(ref.current)
    return () => observer.disconnect()
  }, [near])
  if (near) return <FileImage fileId={fileId} variant={variant} alt={alt} className={className} />
  // `image-loading` (as FileImage's while it fetches): tests wait until no picture is still to come.
  return <span ref={ref} aria-hidden data-slot="image-loading" className={cn('bg-muted inline-block', className)} />
}
