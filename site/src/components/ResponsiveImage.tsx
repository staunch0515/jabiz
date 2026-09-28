import { useState } from 'react'
import { fileUrl } from '../api/client'
import { srcSet } from '../lib/images'
import styles from './ResponsiveImage.module.css'

export type Ratio = 'portrait' | 'wide' | 'square' | 'round'

const SIZE: Record<Ratio, [number, number]> = { portrait: [640, 800], wide: [1280, 720], square: [640, 640], round: [320, 320] }

interface Props {
  fileId: string | null | undefined
  /** Required: an empty string marks a decorative image (design section 9.6). */
  alt: string
  ratio: Ratio
  /** The rendered width, for the browser to choose a variant. */
  sizes: string
  /** Above the fold: loaded at once. */
  eager?: boolean
  /** Shown in a frame without an image, e.g. a participant's initial. */
  placeholder?: string
  className?: string
}

/**
 * An uploaded image in a frame of fixed proportions, so that nothing moves while it loads (design sections 9.3, 16).
 * A variant is missing when the original is narrower than it (docs/design/14-files.md section 3): the first failure
 * switches to the original.
 */
export function ResponsiveImage({ fileId, alt, ratio, sizes, eager, placeholder, className }: Props) {
  const [original, setOriginal] = useState(false)
  const [width, height] = SIZE[ratio]
  const frame = `${styles.frame} ${styles[ratio]} ${className ?? ''}`
  if (!fileId) {
    return (
      <span className={frame} aria-hidden="true">
        {placeholder ? <span className={styles.initial}>{placeholder.slice(0, 1)}</span> : null}
      </span>
    )
  }
  return (
    <span className={frame}>
      <img
        className={styles.img}
        src={original ? fileUrl(fileId) : fileUrl(fileId, 'w640')}
        srcSet={original ? undefined : srcSet(fileId)}
        sizes={original ? undefined : sizes}
        width={width}
        height={height}
        alt={alt}
        loading={eager ? 'eager' : 'lazy'}
        decoding="async"
        onError={() => setOriginal(true)}
      />
    </span>
  )
}
