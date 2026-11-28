import { cn } from '@jabiz/ui'
import { useState } from 'react'
import { CoverPlaceholder } from '../Illustrations'
import { videoKind, videoPoster } from '../video'
import { LazyImage } from './LazyImage'

/**
 * What a video's place shows until its player has its first frame (2026-11-28): the video's own picture, so that the
 * player fading in over it (the stage's `crossFade`) is the same picture moving, never a blank, a black or another
 * picture. YouTube: its picture of the video (`videoPoster`); a video file: its own first frame (a muted `<video>` that
 * loads only its beginning, never plays). The sites without a picture of their own, and a YouTube picture that does not
 * load, fall back to the sponsor's cover (or the placeholder).
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
  if (videoKind(player) === 'file') {
    // `#t=0.1`: the frame a moment in (the very first is often black); `metadata`: only what that frame needs.
    return <video src={`${player}#t=0.1`} muted playsInline preload="metadata" aria-hidden tabIndex={-1}
      className={place} data-testid="video-poster-frame" />
  }
  return cover
    ? <LazyImage fileId={cover} variant="w640" alt="" className={place} />
    : <CoverPlaceholder seed={seed} className={place} data-testid="feed-card-placeholder" />
}
