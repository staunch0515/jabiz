import { autoplaying } from '../video'

/**
 * A video's own player in the page (Q19): YouTube's no-cookie player or Vimeo's, loaded when it comes near the
 * screen. It plays when the viewer presses its play button, or at once and muted when `autoplay` (Q63: the home's card
 * in the middle of the screen, the start page). The frame is sandboxed to what the players need; it cannot navigate
 * the App. Its address is one of the two players (`qb/video.ts`), never anything else.
 */
export function VideoPlayer({ player, title, autoplay = false, className }: { player: string; title: string;
  autoplay?: boolean; className?: string }) {
  return (
    <div className={className ?? 'bg-muted aspect-video w-full overflow-hidden rounded-md'} data-testid="video-player"
      data-autoplay={autoplay || undefined}>
      <iframe
        src={autoplay ? autoplaying(player) : player}
        title={title}
        loading={autoplay ? undefined : 'lazy'}
        className="size-full border-0"
        allow={autoplay ? 'autoplay; encrypted-media; picture-in-picture; fullscreen'
          : 'encrypted-media; picture-in-picture; fullscreen'}
        allowFullScreen
        // YouTube's player refuses to play without the page's origin as the referrer.
        referrerPolicy="strict-origin-when-cross-origin"
        sandbox="allow-scripts allow-same-origin allow-presentation allow-popups allow-popups-to-escape-sandbox"
      />
    </div>
  )
}
