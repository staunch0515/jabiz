import { cn } from '@jabiz/ui'
import { useEffect, useRef, useState } from 'react'
import { cachedImage, FileImage } from '../FileImage'

/** How near the screen an image is fetched (px), as the observer's margin. */
const MARGIN = 200

/** Whether a box is on the screen or within {@link MARGIN} of it (and has a size: a hidden one is not). */
export function isNear(box: DOMRect, width = window.innerWidth, height = window.innerHeight): boolean {
  if (box.width === 0 && box.height === 0) return false
  return box.bottom >= -MARGIN && box.top <= height + MARGIN && box.right >= -MARGIN && box.left <= width + MARGIN
}

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
    // Already near the screen when it comes: shown at once, not left to the observer's first answer (on CI's headless
    // browser that answer sometimes never came for a cover in the home's row of shorts, and the box stayed grey).
    if (isNear(ref.current.getBoundingClientRect())) {
      setNear(true)
      return undefined
    }
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) setNear(true)
    }, { rootMargin: `${MARGIN}px` })
    observer.observe(ref.current)
    return () => observer.disconnect()
  }, [near])
  if (near) return <FileImage fileId={fileId} variant={variant} alt={alt} className={className} />
  // `image-loading` (as FileImage's while it fetches): tests wait until no picture is still to come.
  return <span ref={ref} aria-hidden data-slot="image-loading" className={cn('bg-muted inline-block', className)} />
}
