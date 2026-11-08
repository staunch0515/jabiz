import { autoplaying, PAGE_ALLOW, PAGE_SANDBOX, videoKind } from '../video'
import { markVideoFailed } from '../videoFailures'

/**
 * A video's own player in the page (Q19): YouTube's no-cookie player or Vimeo's, loaded when it comes near the
 * screen. It plays when the viewer presses its play button, or at once and muted when `autoplay` (Q63: the home's card
 * in the middle of the screen, the start page). The frame is sandboxed to what the players need; it cannot navigate
 * the App. Its address is one of the players of `qb/video.ts`, never anything else. Q99b: every site's player starts
 * the same way (muted, where the site takes the parameters for it; `autoplaying`).
 *
 * F-S15: a video file plays in a `<video>` with the browser's controls (muted when it starts by itself); one that fails
 * is marked (`videoFailures`), and the card or page shows its cover and link instead. A page of another site is framed
 * in a stricter sandbox: no pop-ups, no navigation, no forms, no referrer.
 */
export function VideoPlayer({ player, title, autoplay = false, className }: { player: string; title: string;
  autoplay?: boolean; className?: string }) {
  const kind = videoKind(player)
  const box = className ?? 'bg-muted aspect-video w-full overflow-hidden rounded-md'
  if (kind === 'file') {
    return (
      <div className={box} data-testid="video-player" data-kind="file" data-autoplay={autoplay || undefined}>
        <video src={player} title={title} aria-label={title} className="size-full bg-black object-contain"
          controls playsInline preload="metadata" autoPlay={autoplay} muted={autoplay}
          onError={() => markVideoFailed(player)} />
      </div>
    )
  }
  const page = kind === 'page'
  return (
    <div className={box} data-testid="video-player" data-kind={kind ?? undefined}
      data-autoplay={autoplay || undefined}>
      <iframe
        src={autoplay && !page ? autoplaying(player) : player}
        title={title}
        loading={autoplay ? undefined : 'lazy'}
        className="size-full border-0"
        allow={page ? PAGE_ALLOW : autoplay ? 'autoplay; encrypted-media; picture-in-picture; fullscreen'
          : 'encrypted-media; picture-in-picture; fullscreen'}
        allowFullScreen
        // YouTube's player refuses to play without the page's origin as the referrer; another site gets none.
        referrerPolicy={page ? 'no-referrer' : 'strict-origin-when-cross-origin'}
        sandbox={page ? PAGE_SANDBOX
          : 'allow-scripts allow-same-origin allow-presentation allow-popups allow-popups-to-escape-sandbox'}
      />
    </div>
  )
}
