import { cn } from '@jabiz/ui'
import { useEffect, useRef, useState } from 'react'
import { FileImage } from '../qb/FileImage'

/**
 * A file's image that is fetched only once it comes near the screen (docs/quizbuks/plans/QP-pages.md section 10:
 * every card of the feed is a read with the session). Browsers without IntersectionObserver load it at once.
 */
export function LazyImage({ fileId, variant, alt, className }: {
  fileId: string
  variant?: string
  alt: string
  className?: string
}) {
  const ref = useRef<HTMLSpanElement>(null)
  const [near, setNear] = useState(() => typeof IntersectionObserver === 'undefined')
  useEffect(() => {
    if (near || !ref.current) return undefined
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) setNear(true)
    }, { rootMargin: '200px' })
    observer.observe(ref.current)
    return () => observer.disconnect()
  }, [near])
  if (near) return <FileImage fileId={fileId} variant={variant} alt={alt} className={className} />
  return <span ref={ref} aria-hidden className={cn('bg-muted inline-block', className)} />
}
