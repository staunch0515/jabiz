import { cn } from '@jabiz/ui'
import { useState } from 'react'
import { CoverPlaceholder } from '../Illustrations'
import { videoPoster } from '../video'
import { LazyImage } from './LazyImage'

/**
 * What a video's place shows until its player has its first frame (2026-11-28): the video's own picture, so that the
 * player fading in over it (the stage's `crossFade`) is the same picture moving, never a blank, a black or another
 * picture. YouTube: its picture of the video (`videoPoster`). A video file, the other sites and a YouTube picture that
 * does not load: the sponsor's cover (or the placeholder). Not a file's own first frame (2026-11-28, the requester): a
 * `<video>` that never plays is not painted reliably on an iPhone, and many of them on a page kept the playing one from
 * showing too (the video server allows no cross-origin reads, so a frame cannot become a picture in the browser).
 */
export function VideoPoster({ player, cover, seed, className }: {
  /** The player's address (`videoPlayer`'s). */
  player: string
  /** The sponsor's cover: only where the video has no picture of its own. */
  cover?: string | null
  /** The placeholder's seed (the publication) when there is no cover either. */
  seed: string
  className?: string
}) {
  const poster = videoPoster(player)
  const [failed, setFailed] = useState(false)
  const place = cn('absolute inset-0 size-full object-cover', className)
  if (poster && !failed) {
    return <img src={poster} alt="" loading="lazy" decoding="async" referrerPolicy="no-referrer" className={place}
      data-testid="video-poster" onError={() => setFailed(true)} />
  }
  return cover
    ? <LazyImage fileId={cover} variant="w640" alt="" className={place} />
    : <CoverPlaceholder seed={seed} className={place} data-testid="feed-card-placeholder" />
}
